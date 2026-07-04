import Foundation

@MainActor
final class RecordingViewModel: ObservableObject {
    enum State: Equatable {
        case idle
        case preparing
        case recording
        case paused
        case stopping
        case failedBeforeStart(String)
        case finished
    }

    @Published var selectedLanguage: RecognitionLanguage = .chinese
    @Published private(set) var state: State = .idle
    @Published private(set) var elapsedSeconds: Int = 0
    @Published private(set) var audioLevel: RecordingAudioLevel = RecordingAudioLevel(peak: 0, rms: 0)
    @Published private(set) var transcriptBuffer = RealtimeTranscriptBuffer()
    @Published private(set) var statusMessage: String?
    @Published private(set) var scheduleContext: ScheduledMeeting?
    @Published var errorMessage: String?

    private let recordingEngine: RecordingEngine
    private let audioFileStore: AudioFileStore
    private let settingsStore: SettingsStore
    private var asrClient: RealtimeASRClient?
    private var currentTaskId: String?
    private var currentOutputURL: URL?
    private var recordingStartedAt: Date?
    private var pausedAt: Date?
    private var accumulatedPausedSeconds: TimeInterval = 0
    private var timerTask: Task<Void, Never>?
    private var didStartRecording = false

    init(
        recordingEngine: RecordingEngine = RecordingEngine(),
        audioFileStore: AudioFileStore = AudioFileStore(),
        settingsStore: SettingsStore = SettingsStore()
    ) {
        self.recordingEngine = recordingEngine
        self.audioFileStore = audioFileStore
        self.settingsStore = settingsStore
        selectedLanguage = settingsStore.preferredRecognitionLanguage
    }

    var isBusy: Bool {
        switch state {
        case .preparing, .recording, .paused, .stopping:
            return true
        case .idle, .failedBeforeStart, .finished:
            return false
        }
    }

    var primaryActionTitle: String {
        switch state {
        case .idle, .failedBeforeStart, .finished:
            return "开始记录"
        case .preparing:
            return "取消"
        case .recording, .paused:
            return "结束"
        case .stopping:
            return "结束中"
        }
    }

    func configure(schedule: ScheduledMeeting?) {
        switch state {
        case .idle, .failedBeforeStart, .finished:
            scheduleContext = schedule
        case .preparing, .recording, .paused, .stopping:
            break
        }
    }

    func primaryAction(session: AppSession, router: AppRouter) {
        switch state {
        case .idle, .failedBeforeStart, .finished:
            Task { await start(session: session, router: router) }
        case .preparing:
            cancelBeforeStart()
        case .recording, .paused:
            Task { await stop(session: session, router: router) }
        case .stopping:
            break
        }
    }

    func start(session: AppSession, router: AppRouter) async {
        do {
            try await session.ensureTranscriptionAvailable()
        } catch {
            let message = userMessage(error)
            errorMessage = message
            if isQuotaExhausted(error, message: message) {
                router.go(.membership)
            }
            return
        }
        let remaining = session.membershipProfile.transcriptionMinutesRemaining
        if remaining <= 30 {
            statusMessage = "当前剩余转写时长 \(remaining) 分钟，本次实时记录已进入缓冲提醒"
        }
        settingsStore.preferredRecognitionLanguage = selectedLanguage
        resetForNewRecording()
        state = .preparing
        if statusMessage == nil {
            statusMessage = "正在准备实时转写"
        }
        do {
            let taskId = UUID().uuidString
            currentTaskId = taskId
            currentOutputURL = try audioFileStore.recordingURL(taskId: taskId)
            asrClient = session.makeRealtimeASRClient(
                onEvent: { [weak self] event in
                    self?.transcriptBuffer.apply(event)
                },
                onState: { [weak self] asrState in
                    self?.handleASRState(asrState)
                }
            )
            let user = try session.requireUser()
            await asrClient?.prepare(user: user, language: selectedLanguage)
        } catch {
            state = .failedBeforeStart(userMessage(error))
            errorMessage = userMessage(error)
        }
    }

    func stop(session: AppSession, router: AppRouter) async {
        guard didStartRecording else {
            cancelBeforeStart()
            return
        }
        state = .stopping
        statusMessage = "正在保存录音"
        let fileURL = await recordingEngine.stop()
        let missingAudioRanges = await asrClient?.finish(audioFileURL: fileURL) ?? []
        stopTimer()
        guard let task = makeRecordingTask(fileURL: fileURL, missingAudioRanges: missingAudioRanges) else {
            state = .failedBeforeStart("录音文件未生成，请重新开始。")
            return
        }
        session.enqueueTask(task)
        if let schedule = scheduleContext {
            try? await session.deleteSchedule(schedule.id)
        }
        state = .finished
        router.openProcessing(taskId: task.id, autoStart: true)
    }

    func cancelBeforeStart() {
        asrClient?.cancel()
        recordingEngine.cancel()
        stopTimer()
        resetForNewRecording()
        state = .idle
        statusMessage = nil
    }

    func pauseOrResume() {
        switch state {
        case .recording:
            Task { await pause() }
        case .paused:
            Task { await resume() }
        default:
            break
        }
    }

    private func pause() async {
        guard state == .recording else { return }
        pausedAt = Date()
        await recordingEngine.pause()
    }

    private func resume() async {
        guard state == .paused else { return }
        if let pausedAt {
            accumulatedPausedSeconds += Date().timeIntervalSince(pausedAt)
        }
        pausedAt = nil
        await recordingEngine.resume()
    }

    private func handleASRState(_ asrState: RealtimeASRState) {
        switch asrState {
        case .connecting:
            statusMessage = "正在连接实时转写"
        case .ready:
            statusMessage = "实时转写已就绪"
            Task { await beginRecordingIfNeeded() }
        case .streaming:
            statusMessage = nil
        case let .failedBeforeStart(message):
            if !didStartRecording {
                state = .failedBeforeStart(message)
                errorMessage = message
                statusMessage = nil
            }
        case .disconnectedRecovering:
            statusMessage = nil
        case .finishing:
            statusMessage = "正在结束实时转写"
        case .finished:
            break
        case .idle:
            break
        }
    }

    private func beginRecordingIfNeeded() async {
        guard state == .preparing, !didStartRecording, let outputURL = currentOutputURL else { return }
        didStartRecording = true
        await asrClient?.startStreaming()
        await recordingEngine.start(
            outputURL: outputURL,
            onPCM: { [weak self] frame, endBytes in
                await self?.asrClient?.sendPCMFrame(frame, endBytes: endBytes)
            },
            onLevel: { [weak self] level in
                self?.audioLevel = level
            },
            onState: { [weak self] recordingState in
                self?.handleRecordingState(recordingState)
            }
        )
    }

    private func handleRecordingState(_ recordingState: RecordingEngineState) {
        switch recordingState {
        case .recording:
            state = .recording
            if recordingStartedAt == nil {
                recordingStartedAt = Date()
            }
            startTimer()
        case .paused:
            state = .paused
            statusMessage = "录音已暂停"
        case let .failedBeforeStart(message):
            state = .failedBeforeStart(message)
            errorMessage = message
            stopTimer()
            asrClient?.cancel()
        case .stopping:
            state = .stopping
        case .finished:
            break
        case .idle, .preparingASR, .readyToRecord:
            break
        }
    }

    private func makeRecordingTask(fileURL: URL?, missingAudioRanges: [MissingAudioRange]) -> MeetingTask? {
        guard let taskId = currentTaskId, let fileURL else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return MeetingTask(
            id: taskId,
            remoteTaskId: nil,
            fileId: nil,
            title: scheduleContext?.title.isEmpty == false ? scheduleContext?.title ?? "录音 \(Self.timeLabel(Date()))" : "录音 \(Self.timeLabel(Date()))",
            source: .recording,
            status: .waitingProcess,
            localFilePath: fileURL.path,
            createdAtMillis: now,
            sizeLabel: nil,
            errorMessage: nil,
            progressPercent: 0,
            progressLabel: "正在准备音频处理",
            progressStage: "preparing_audio",
            confirmed: false,
            knowledgeScope: .local,
            isPrivate: false,
            deviceId: settingsStore.deviceId,
            scheduleId: scheduleContext?.id,
            scheduleNote: scheduleContext?.recordingNote,
            recognitionLanguage: selectedLanguage,
            liveTranscripts: transcriptBuffer.finalSegments,
            missingAudioRanges: missingAudioRanges.isEmpty ? nil : missingAudioRanges
        )
    }

    private func resetForNewRecording() {
        elapsedSeconds = 0
        audioLevel = RecordingAudioLevel(peak: 0, rms: 0)
        transcriptBuffer.reset()
        errorMessage = nil
        currentTaskId = nil
        currentOutputURL = nil
        recordingStartedAt = nil
        pausedAt = nil
        accumulatedPausedSeconds = 0
        didStartRecording = false
    }

    private func startTimer() {
        stopTimer()
        timerTask = Task { [weak self] in
            guard let model = self else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 250_000_000)
                await model.updateElapsedSecondsTick()
            }
        }
    }

    private func updateElapsedSecondsTick() {
        guard let started = recordingStartedAt else { return }
        let currentPause = pausedAt.map { Date().timeIntervalSince($0) } ?? 0
        elapsedSeconds = max(0, Int(Date().timeIntervalSince(started) - accumulatedPausedSeconds - currentPause))
    }

    private func stopTimer() {
        timerTask?.cancel()
        timerTask = nil
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }

    private func isQuotaExhausted(_ error: Error, message: String) -> Bool {
        if case let APIError.httpStatus(status, _) = error, status == 402 {
            return true
        }
        return message.contains("额度") || message.contains("转写时长不足")
    }

    private static func timeLabel(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: date)
    }
}

private extension ScheduledMeeting {
    var recordingNote: String? {
        let parts = [
            time.isEmpty ? nil : "预约时间：\(time)",
            participants.isEmpty ? nil : "参会人：\(participants)",
            note.isEmpty ? nil : "备注：\(note)"
        ].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: "\n")
    }
}
