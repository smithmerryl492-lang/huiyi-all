import Foundation

final class RealtimeASRClient {
    typealias EventHandler = @MainActor (RealtimeTranscriptEvent) -> Void
    typealias StateHandler = @MainActor (RealtimeASRState) -> Void

    private let apiClient: APIClient
    private let urlSession: URLSession
    private var webSocket: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?
    private var session: LiveDirectSession?
    private var started = false
    private var opened = false
    private var released = false
    private var stopped = false
    private var connectionGeneration = 0
    private var reconnectAttempts = 0
    private var reconnectTask: Task<Void, Never>?
    private var latestFrameEndBytes: Int64 = 0
    private var lastFinalEndBytes: Int64 = 0
    private var missingRanges: [MissingAudioRange] = []
    private var language: RecognitionLanguage = .chinese
    private var user: CloudUser?
    private var liveItems = LiveItemState()
    private let eventHandler: EventHandler
    private let stateHandler: StateHandler

    init(
        apiClient: APIClient,
        urlSession: URLSession = .shared,
        onEvent: @escaping EventHandler,
        onState: @escaping StateHandler
    ) {
        self.apiClient = apiClient
        self.urlSession = urlSession
        eventHandler = onEvent
        stateHandler = onState
    }

    func prepare(user: CloudUser, language: RecognitionLanguage) async {
        self.user = user
        self.language = language
        released = false
        stopped = false
        started = false
        opened = false
        reconnectAttempts = 0
        reconnectTask?.cancel()
        reconnectTask = nil
        latestFrameEndBytes = 0
        lastFinalEndBytes = 0
        missingRanges = []
        liveItems = LiveItemState()
        await emit(.connecting)
        await connect(reconnect: false)
    }

    func startStreaming() async {
        started = true
        await emit(.streaming)
    }

    func sendPCMFrame(_ frame: Data, endBytes: Int64) async {
        latestFrameEndBytes = Swift.max(latestFrameEndBytes, endBytes)
        guard started, opened, let webSocket else {
            rememberMissingAudio(startBytes: endBytes - Int64(frame.count), endBytes: endBytes)
            return
        }
        do {
            let payload: [String: Any] = [
                "event_id": "audio_append_\(endBytes)",
                "type": "input_audio_buffer.append",
                "audio": frame.base64EncodedString()
            ]
            try await sendText(jsonString(payload), webSocket: webSocket)
        } catch {
            rememberMissingAudio(startBytes: endBytes - Int64(frame.count), endBytes: endBytes)
            await handleDisconnected("实时转写连接恢复中，录音会继续本地保存")
        }
    }

    func finish(audioFileURL: URL? = nil) async -> [MissingAudioRange] {
        stopped = true
        await emit(.finishing)
        do {
            try await sendText(jsonString(["event_id": "audio_commit", "type": "input_audio_buffer.commit"]))
            try await sendText(jsonString(["event_id": "session_finish", "type": "session.finish"]))
        } catch {
            // Processing can still continue with the saved audio file.
        }
        try? await Task.sleep(nanoseconds: Constants.finishSettleNanoseconds)
        rememberUnfinalizedTailAudio()
        await backfillMissingAudioIfNeeded(audioFileURL: audioFileURL)
        webSocket?.cancel(with: .normalClosure, reason: nil)
        receiveTask?.cancel()
        reconnectTask?.cancel()
        reconnectTask = nil
        webSocket = nil
        opened = false
        await emit(.finished)
        return missingRanges
    }

    func cancel() {
        released = true
        stopped = true
        webSocket?.cancel(with: .goingAway, reason: nil)
        receiveTask?.cancel()
        reconnectTask?.cancel()
        reconnectTask = nil
        webSocket = nil
        opened = false
        started = false
    }

    private func connect(reconnect: Bool) async {
        guard !released, !stopped, let user else { return }
        connectionGeneration += 1
        let generation = connectionGeneration
        receiveTask?.cancel()
        webSocket?.cancel(with: .goingAway, reason: nil)
        webSocket = nil
        opened = false
        if reconnect {
            await emit(.disconnectedRecovering("实时转写连接恢复中，录音会继续本地保存"))
        } else {
            await emit(.connecting)
        }
        do {
            let directSession = try await validDirectSession(user: user)
            var request = URLRequest(url: directSession.websocketURL)
            request.setValue("Bearer \(directSession.apiKey)", forHTTPHeaderField: "Authorization")
            request.setValue("huiyi-ios/0.1", forHTTPHeaderField: "User-Agent")
            let socket = urlSession.webSocketTask(with: request)
            webSocket = socket
            socket.resume()
            receiveTask = Task { [weak self] in
                await self?.receiveLoop(generation: generation)
            }
            try await sendText(sessionUpdatePayload(session: directSession, language: language), webSocket: socket)
        } catch {
            if started {
                await handleDisconnected("实时转写连接恢复中，录音会继续本地保存")
            } else {
                await emit(.failedBeforeStart(startupConnectionFailureMessage(error)))
            }
        }
    }

    private func receiveLoop(generation: Int) async {
        while !Task.isCancelled, let webSocket {
            do {
                let message = try await webSocket.receive()
                switch message {
                case let .string(text):
                    await handle(text: text, generation: generation)
                case .data:
                    break
                @unknown default:
                    break
                }
            } catch {
                guard generation == connectionGeneration, !released, !stopped else { return }
                if !started {
                    await emit(.failedBeforeStart(userMessage(error)))
                } else {
                    await handleDisconnected("实时转写连接恢复中，录音会继续本地保存")
                }
                return
            }
        }
    }

    private func handle(text: String, generation: Int) async {
        guard generation == connectionGeneration, !released else { return }
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        let type = object["type"] as? String ?? ""
        if type == "session.updated" {
            opened = true
            reconnectAttempts = 0
            await emit(started ? .streaming : .ready)
            return
        }
        if type == "session.created" || type == "session.finished" {
            return
        }
        if type == "error" || object["error"] != nil {
            await handleErrorEvent(object)
            return
        }
        if type == "quota.warning", let message = object["message"] as? String {
            await emit(.disconnectedRecovering(message))
            return
        }
        guard let event = parseTranscriptEvent(object) else { return }
        if event.isFinal {
            rememberFinalEnd(event.segments)
        }
        await eventHandler(event)
    }

    private func parseTranscriptEvent(_ object: [String: Any]) -> RealtimeTranscriptEvent? {
        let type = object["type"] as? String ?? ""
        let itemId = (object["item_id"] as? String)?.nonEmptyTrimmed
        if type == "input_audio_buffer.speech_started" {
            let startMs = int64Value(object["audio_start_ms"]) ?? bytesToMs(latestFrameEndBytes)
            _ = liveItems.touch(itemId, startMs: startMs)
            return nil
        }
        if type == "input_audio_buffer.speech_stopped" {
            let item = liveItems.touch(itemId)
            item?.endMs = int64Value(object["audio_end_ms"]) ?? bytesToMs(latestFrameEndBytes)
            return nil
        }
        guard type == "conversation.item.input_audio_transcription.text" ||
            type == "conversation.item.input_audio_transcription.completed" else {
            return nil
        }
        let item = liveItems.touch(itemId)
        let isFinal = type == "conversation.item.input_audio_transcription.completed"
        let text: String
        if isFinal {
            text = ((object["transcript"] as? String)?.nonEmptyTrimmed ?? item?.partialText ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            text = partialText(from: object)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        }
        guard !text.isEmpty else { return nil }
        let startMs = item?.startMs ?? bytesToMs(latestFrameEndBytes)
        let endMs = isFinal ? (item?.endMs ?? bytesToMs(latestFrameEndBytes)) : item?.endMs
        if shouldFilterLiveText(text, startMs: startMs, endMs: endMs) {
            if isFinal { item?.partialText = "" }
            return nil
        }
        if !isFinal {
            item?.partialText = text
        }
        let segment = TranscriptSegment(
            speaker: "发言",
            text: text,
            timestamp: timestamp(ms: startMs),
            startMs: startMs,
            endMs: endMs,
            speakerId: "live",
            confidence: nil
        )
        return RealtimeTranscriptEvent(segments: [segment], isFinal: isFinal, replaceAll: false)
    }

    private func backfillMissingAudioIfNeeded(audioFileURL: URL?) async {
        guard let audioFileURL,
              FileManager.default.fileExists(atPath: audioFileURL.path),
              !missingRanges.isEmpty,
              !released else {
            return
        }
        for range in backfillRanges() {
            guard !released else { return }
            try? await backfillRange(audioFileURL: audioFileURL, range: range)
        }
    }

    private func backfillRanges() -> [MissingAudioRange] {
        missingRanges
            .compactMap { range in
                let start = Swift.max(0, range.startBytes - Constants.backfillOverlapBytes).alignedPCMBytes
                let end = min(latestFrameEndBytes, range.endBytes + Constants.backfillOverlapBytes).alignedPCMBytes
                guard end - start >= Constants.minBackfillBytes else { return nil }
                return MissingAudioRange(startBytes: start, endBytes: end)
            }
            .mergedForBackfill()
    }

    private func backfillRange(audioFileURL: URL, range: MissingAudioRange) async throws {
        guard let user else { return }
        let directSession = try await validDirectSession(user: user)
        var request = URLRequest(url: directSession.websocketURL)
        request.setValue("Bearer \(directSession.apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("huiyi-ios/0.1", forHTTPHeaderField: "User-Agent")
        let socket = urlSession.webSocketTask(with: request)
        let itemState = LiveItemState()
        socket.resume()
        defer { socket.cancel(with: .normalClosure, reason: nil) }
        try await sendText(sessionUpdatePayload(session: directSession, language: language), webSocket: socket)

        let timeout = backfillTimeoutNanoseconds(range)
        let deadline = Date().addingTimeInterval(TimeInterval(timeout) / 1_000_000_000)
        while Date() < deadline, !released {
            let remainingSeconds = Swift.max(0.1, deadline.timeIntervalSinceNow)
            let remaining = UInt64(remainingSeconds * 1_000_000_000)
            let message = try await receiveMessage(from: socket, timeoutNanoseconds: remaining)
            guard case let .string(text) = message,
                  let data = text.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                continue
            }
            let type = object["type"] as? String ?? ""
            if type == "session.updated" {
                try await streamBackfillAudio(socket: socket, audioFileURL: audioFileURL, range: range)
                try await sendText(jsonString(["event_id": "backfill_commit", "type": "input_audio_buffer.commit"]), webSocket: socket)
                try await sendText(jsonString(["event_id": "backfill_finish", "type": "session.finish"]), webSocket: socket)
                continue
            }
            if type == "session.finished" || type == "error" || object["error"] != nil {
                return
            }
            if let event = parseBackfillTranscriptEvent(object, baseBytes: range.startBytes, fallbackEndBytes: range.endBytes, itemState: itemState),
               event.isFinal,
               !event.segments.isEmpty {
                await eventHandler(event)
            }
        }
    }

    private func receiveMessage(from socket: URLSessionWebSocketTask, timeoutNanoseconds: UInt64) async throws -> URLSessionWebSocketTask.Message {
        try await withThrowingTaskGroup(of: URLSessionWebSocketTask.Message.self) { group in
            group.addTask {
                try await socket.receive()
            }
            group.addTask {
                try await Task.sleep(nanoseconds: timeoutNanoseconds)
                throw URLError(.timedOut)
            }
            guard let message = try await group.next() else {
                throw URLError(.unknown)
            }
            group.cancelAll()
            return message
        }
    }

    private func streamBackfillAudio(socket: URLSessionWebSocketTask, audioFileURL: URL, range: MissingAudioRange) async throws {
        let handle = try FileHandle(forReadingFrom: audioFileURL)
        defer { try? handle.close() }
        var cursor = range.startBytes.alignedPCMBytes
        let end = range.endBytes.alignedPCMBytes
        while cursor < end, !released {
            let remaining = min(Constants.liveFrameBytes, end - cursor)
            guard remaining > 0 else { break }
            try handle.seek(toOffset: UInt64(Constants.wavHeaderBytes + cursor))
            let frame = try handle.read(upToCount: Int(remaining)) ?? Data()
            guard !frame.isEmpty else { break }
            if frame.hasNonZeroAudio {
                try await sendText(jsonString([
                    "event_id": "backfill_audio_\(cursor + Int64(frame.count))",
                    "type": "input_audio_buffer.append",
                    "audio": frame.base64EncodedString()
                ]), webSocket: socket)
            }
            cursor += Int64(frame.count)
        }
    }

    private func parseBackfillTranscriptEvent(
        _ object: [String: Any],
        baseBytes: Int64,
        fallbackEndBytes: Int64,
        itemState: LiveItemState
    ) -> RealtimeTranscriptEvent? {
        let type = object["type"] as? String ?? ""
        let itemId = (object["item_id"] as? String)?.nonEmptyTrimmed
        if type == "input_audio_buffer.speech_started" {
            let startMs = int64Value(object["audio_start_ms"]) ?? bytesToMs(fallbackEndBytes - baseBytes)
            _ = itemState.touch(itemId, startMs: startMs)
            return nil
        }
        if type == "input_audio_buffer.speech_stopped" {
            let item = itemState.touch(itemId)
            item?.endMs = int64Value(object["audio_end_ms"]) ?? bytesToMs(fallbackEndBytes - baseBytes)
            return nil
        }
        guard type == "conversation.item.input_audio_transcription.text" ||
            type == "conversation.item.input_audio_transcription.completed" else {
            return nil
        }
        let item = itemState.touch(itemId)
        let isFinal = type == "conversation.item.input_audio_transcription.completed"
        let text: String
        if isFinal {
            text = ((object["transcript"] as? String)?.nonEmptyTrimmed ?? item?.partialText ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            text = partialText(from: object)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        }
        guard !text.isEmpty else { return nil }
        let endMs = isFinal ? (item?.endMs ?? bytesToMs(fallbackEndBytes - baseBytes)) : item?.endMs
        if shouldFilterLiveText(text, startMs: item?.startMs, endMs: endMs) {
            if isFinal { item?.partialText = "" }
            return nil
        }
        if !isFinal {
            item?.partialText = text
        }
        let absoluteStartMs = (item?.startMs ?? 0) + bytesToMs(baseBytes)
        let absoluteEndMs = endMs.map { $0 + bytesToMs(baseBytes) }
        let segment = TranscriptSegment(
            speaker: "发言",
            text: text,
            timestamp: timestamp(ms: absoluteStartMs),
            startMs: absoluteStartMs,
            endMs: absoluteEndMs,
            speakerId: "live",
            confidence: nil
        )
        if isFinal {
            rememberFinalEnd([segment])
        }
        return RealtimeTranscriptEvent(segments: [segment], isFinal: isFinal, replaceAll: false)
    }

    private func backfillTimeoutNanoseconds(_ range: MissingAudioRange) -> UInt64 {
        let durationMs = bytesToMs(range.endBytes - range.startBytes)
        let timeoutMs = Swift.max(Constants.minBackfillTimeoutMilliseconds, durationMs * Constants.backfillTimeoutMultiplier)
        return UInt64(timeoutMs) * 1_000_000
    }

    private func validDirectSession(user: CloudUser) async throws -> LiveDirectSession {
        let now = Int64(Date().timeIntervalSince1970)
        if let session, session.expiresAt - now > 60 {
            return session
        }
        let directSession = try await apiClient.createLiveSession(user: user)
        session = directSession
        return directSession
    }

    private func handleDisconnected(_ message: String) async {
        guard started, !released, !stopped else { return }
        opened = false
        await emit(.disconnectedRecovering(message))
        scheduleReconnect()
    }

    private func scheduleReconnect() {
        guard !released, !stopped else { return }
        if reconnectTask != nil { return }
        reconnectAttempts += 1
        let exponent = min(reconnectAttempts - 1, 5)
        let delaySeconds = min(30, 1 << exponent)
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delaySeconds) * 1_000_000_000)
            guard let self, !Task.isCancelled else { return }
            self.reconnectTask = nil
            guard !self.released, !self.stopped else { return }
            await self.connect(reconnect: true)
        }
    }

    private func handleErrorEvent(_ object: [String: Any]) async {
        let code = errorCode(object)
        let message = extractErrorMessage(object)
        if code == "quota_exhausted" {
            stopped = true
            webSocket?.cancel(with: .goingAway, reason: nil)
            await emit(.failedBeforeStart(message))
            return
        }
        if started, code.isRecoverableLiveErrorCode || code.isTransientLiveErrorCode || message.isRecoverableLiveErrorMessage || message.isTransientLiveErrorMessage {
            await handleDisconnected("实时转写连接恢复中，录音会继续本地保存")
            return
        }
        if started {
            await handleDisconnected(message)
        } else {
            await emit(.failedBeforeStart(message))
        }
    }

    private func shouldFilterLiveText(_ text: String, startMs: Int64?, endMs: Int64?) -> Bool {
        guard session?.filterFiller != false else { return false }
        let normalized = text.replacingOccurrences(
            of: "[\\s，。！？、,.!?…~～]+",
            with: "",
            options: .regularExpression
        )
        guard !normalized.isEmpty,
              normalized.range(of: "^[嗯呃额啊唔]+$", options: .regularExpression) != nil else {
            return false
        }
        if let startMs, let endMs, endMs >= startMs {
            return endMs - startMs <= Swift.max(300, Int64(session?.fillerMaxDurationMs ?? 1200))
        }
        return normalized.count <= 3
    }

    private func errorCode(_ object: [String: Any]) -> String {
        if let code = object["code"] as? String, !code.isEmpty { return code }
        if let error = object["error"] as? [String: Any], let code = error["code"] as? String, !code.isEmpty { return code }
        return ""
    }

    private func startupConnectionFailureMessage(_ error: Error) -> String {
        let display = userMessage(error)
        if display.localizedCaseInsensitiveContains("network") ||
            display.contains("网络") ||
            display.localizedCaseInsensitiveContains("timeout") ||
            display.contains("超时") {
            return display
        }
        return "实时转写服务暂时不可用，请稍后重试"
    }

    private func int64Value(_ value: Any?) -> Int64? {
        if let value = value as? Int64 { return value }
        if let value = value as? Int { return Int64(value) }
        if let value = value as? Double { return Int64(value) }
        if let value = value as? String { return Int64(value) }
        return nil
    }

    private final class LiveItemState {
        private var items: [String: LiveItem] = [:]
        private var fallbackIndex = 0
        private var activeItemId: String?

        func touch(_ itemId: String?, startMs: Int64? = nil) -> LiveItem? {
            let id: String
            if let itemId, !itemId.isEmpty {
                id = itemId
            } else if let activeItemId {
                id = activeItemId
            } else {
                id = "item-\(fallbackIndex)"
                fallbackIndex += 1
            }
            let item = items[id] ?? LiveItem(startMs: startMs ?? 0)
            if let startMs {
                item.startMs = startMs
            }
            items[id] = item
            activeItemId = id
            return item
        }
    }

    private final class LiveItem {
        var startMs: Int64
        var endMs: Int64?
        var partialText = ""

        init(startMs: Int64) {
            self.startMs = startMs
        }
    }

    private func sessionUpdatePayload(session: LiveDirectSession, language: RecognitionLanguage) -> String {
        var transcription: [String: Any] = [:]
        if let code = language.realtimeLanguageCode {
            transcription["language"] = code
        }
        return jsonString([
            "event_id": "huiyi_session_update",
            "type": "session.update",
            "session": [
                "input_audio_format": "pcm",
                "sample_rate": session.sampleRate,
                "input_audio_transcription": transcription,
                "turn_detection": [
                    "type": "server_vad",
                    "threshold": session.vadThreshold,
                    "silence_duration_ms": session.vadSilenceDurationMs
                ]
            ]
        ])
    }

    private func sendText(_ text: String) async throws {
        guard let webSocket else { throw APIError.invalidResponse }
        try await sendText(text, webSocket: webSocket)
    }

    private func sendText(_ text: String, webSocket: URLSessionWebSocketTask) async throws {
        try await webSocket.send(.string(text))
    }

    private func rememberMissingAudio(startBytes: Int64, endBytes: Int64) {
        guard endBytes > startBytes else { return }
        let range = MissingAudioRange(startBytes: Swift.max(0, startBytes).alignedPCMBytes, endBytes: endBytes.alignedPCMBytes)
        if let last = missingRanges.last, range.startBytes <= last.endBytes + Constants.liveFrameBytes {
            missingRanges[missingRanges.count - 1] = MissingAudioRange(startBytes: last.startBytes, endBytes: Swift.max(last.endBytes, range.endBytes))
        } else {
            missingRanges.append(range)
        }
    }

    private func rememberUnfinalizedTailAudio() {
        let tailBytes = latestFrameEndBytes - lastFinalEndBytes
        if tailBytes >= Constants.minBackfillBytes {
            rememberMissingAudio(startBytes: lastFinalEndBytes, endBytes: latestFrameEndBytes)
        }
    }

    private func rememberFinalEnd(_ segments: [TranscriptSegment]) {
        let end = segments.compactMap(\.endMs).max() ?? bytesToMs(latestFrameEndBytes)
        lastFinalEndBytes = Swift.max(lastFinalEndBytes, msToBytes(end))
    }

    private func partialText(from object: [String: Any]) -> String? {
        let text = (object["text"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let stash = (object["stash"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return (text + stash).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func extractErrorMessage(_ object: [String: Any]) -> String {
        if let message = object["message"] as? String, !message.isEmpty { return message }
        if let error = object["error"] as? [String: Any], let message = error["message"] as? String, !message.isEmpty {
            return message
        }
        return "实时转写服务暂时不可用，请稍后重试"
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }

    @MainActor
    private func emit(_ state: RealtimeASRState) {
        stateHandler(state)
    }

    private func jsonString(_ object: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return text
    }

    private func timestamp(ms: Int64) -> String {
        let seconds = Swift.max(0, ms / 1000)
        return String(format: "%02lld:%02lld", seconds / 60, seconds % 60)
    }

    private func bytesToMs(_ bytes: Int64) -> Int64 {
        Swift.max(0, bytes) / 32
    }

    private func msToBytes(_ ms: Int64) -> Int64 {
        (ms * 32).alignedPCMBytes
    }
}

private enum Constants {
    static let liveFrameBytes: Int64 = 1_920
    static let wavHeaderBytes: Int64 = 44
    static let minBackfillBytes: Int64 = 9_600
    static let finishSettleNanoseconds: UInt64 = 1_500_000_000
    static let minBackfillTimeoutMilliseconds: Int64 = 60_000
    static let backfillTimeoutMultiplier: Int64 = 3
    static let backfillOverlapBytes: Int64 = Int64(500 * 32).alignedPCMBytes
}

private extension Int64 {
    var alignedPCMBytes: Int64 {
        self - (self % 2)
    }
}

private extension Array where Element == MissingAudioRange {
    func mergedForBackfill() -> [MissingAudioRange] {
        let sorted = sorted { $0.startBytes < $1.startBytes }
        var merged: [MissingAudioRange] = []
        for range in sorted {
            guard let last = merged.last else {
                merged.append(range)
                continue
            }
            if range.startBytes <= last.endBytes + Constants.liveFrameBytes {
                merged[merged.count - 1] = MissingAudioRange(startBytes: last.startBytes, endBytes: Swift.max(last.endBytes, range.endBytes))
            } else {
                merged.append(range)
            }
        }
        return merged
    }
}

private extension Data {
    var hasNonZeroAudio: Bool {
        contains { $0 != 0 }
    }
}

private extension String {
    var nonEmptyTrimmed: String? {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty ? nil : clean
    }

    var isRecoverableLiveErrorCode: Bool {
        self == "live_unavailable" || self == "upstream_unavailable"
    }

    var isTransientLiveErrorCode: Bool {
        isEmpty ||
            localizedCaseInsensitiveContains("unavailable") ||
            localizedCaseInsensitiveContains("timeout") ||
            localizedCaseInsensitiveContains("gateway") ||
            localizedCaseInsensitiveContains("server") ||
            localizedCaseInsensitiveContains("network")
    }

    var isRecoverableLiveErrorMessage: Bool {
        contains("实时转写服务暂时不可用") ||
            contains("连接已断开") ||
            contains("正在恢复") ||
            contains("暂时不可用")
    }

    var isTransientLiveErrorMessage: Bool {
        contains("实时转写") ||
            contains("语音识别") ||
            contains("服务暂时") ||
            contains("稍后重试") ||
            contains("请求超时") ||
            contains("服务器维护") ||
            contains("网络连接")
    }
}
