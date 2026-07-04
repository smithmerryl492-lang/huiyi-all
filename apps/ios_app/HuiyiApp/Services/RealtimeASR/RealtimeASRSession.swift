import Foundation

struct LiveDirectSession: Codable, Equatable, Sendable {
    let provider: String
    let apiKey: String
    let expiresAt: Int64
    let websocketURL: URL
    let model: String
    let sampleRate: Int
    let vadThreshold: Double
    let vadSilenceDurationMs: Int
    let filterFiller: Bool
    let fillerMaxDurationMs: Int
    let remainingMinutes: Int
    let graceMinutes: Int
    let lowRemainingWarningMinutes: Int

    enum CodingKeys: String, CodingKey {
        case provider
        case apiKey = "api_key"
        case expiresAt = "expires_at"
        case websocketURL = "websocket_url"
        case model
        case sampleRate = "sample_rate"
        case vadThreshold = "vad_threshold"
        case vadSilenceDurationMs = "vad_silence_duration_ms"
        case filterFiller = "filter_filler"
        case fillerMaxDurationMs = "filler_max_duration_ms"
        case remainingMinutes = "remaining_minutes"
        case graceMinutes = "grace_minutes"
        case lowRemainingWarningMinutes = "low_remaining_warning_minutes"
    }

    init(
        provider: String = "aliyun",
        apiKey: String,
        expiresAt: Int64,
        websocketURL: URL,
        model: String = "qwen3-asr-flash-realtime",
        sampleRate: Int = 16_000,
        vadThreshold: Double = 0.35,
        vadSilenceDurationMs: Int = 450,
        filterFiller: Bool = true,
        fillerMaxDurationMs: Int = 1200,
        remainingMinutes: Int = 0,
        graceMinutes: Int = 30,
        lowRemainingWarningMinutes: Int = 30
    ) {
        self.provider = provider.isEmpty ? "aliyun" : provider
        self.apiKey = apiKey
        self.expiresAt = expiresAt
        self.websocketURL = websocketURL
        self.model = model.isEmpty ? "qwen3-asr-flash-realtime" : model
        self.sampleRate = sampleRate
        self.vadThreshold = vadThreshold
        self.vadSilenceDurationMs = vadSilenceDurationMs
        self.filterFiller = filterFiller
        self.fillerMaxDurationMs = fillerMaxDurationMs
        self.remainingMinutes = remainingMinutes
        self.graceMinutes = graceMinutes
        self.lowRemainingWarningMinutes = lowRemainingWarningMinutes
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let websocketText = try container.decode(String.self, forKey: .websocketURL)
        guard let websocketURL = URL(string: websocketText) else {
            throw DecodingError.dataCorruptedError(forKey: .websocketURL, in: container, debugDescription: "Invalid websocket URL")
        }
        self.init(
            provider: container.decodeStringIfPresent(.provider) ?? "aliyun",
            apiKey: try container.decode(String.self, forKey: .apiKey),
            expiresAt: container.decodeInt64IfPresent(.expiresAt) ?? 0,
            websocketURL: websocketURL,
            model: container.decodeStringIfPresent(.model) ?? "qwen3-asr-flash-realtime",
            sampleRate: container.decodeIntIfPresent(.sampleRate) ?? 16_000,
            vadThreshold: container.decodeDoubleIfPresent(.vadThreshold) ?? 0.35,
            vadSilenceDurationMs: container.decodeIntIfPresent(.vadSilenceDurationMs) ?? 450,
            filterFiller: container.decodeBoolIfPresent(.filterFiller) ?? true,
            fillerMaxDurationMs: container.decodeIntIfPresent(.fillerMaxDurationMs) ?? 1200,
            remainingMinutes: container.decodeIntIfPresent(.remainingMinutes) ?? 0,
            graceMinutes: container.decodeIntIfPresent(.graceMinutes) ?? 30,
            lowRemainingWarningMinutes: container.decodeIntIfPresent(.lowRemainingWarningMinutes) ?? 30
        )
    }
}

enum RealtimeASRState: Equatable, Sendable {
    case idle
    case connecting
    case ready
    case streaming
    case finishing
    case failedBeforeStart(String)
    case disconnectedRecovering(String)
    case finished
}

struct RealtimeTranscriptEvent: Equatable, Sendable {
    var segments: [TranscriptSegment]
    var isFinal: Bool
    var replaceAll: Bool
}

struct MissingAudioRange: Codable, Equatable, Sendable {
    var startBytes: Int64
    var endBytes: Int64
}

private extension KeyedDecodingContainer {
    func decodeStringIfPresent(_ key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return clean.isEmpty || clean.lowercased() == "null" ? nil : clean
        }
        return nil
    }

    func decodeIntIfPresent(_ key: Key) -> Int? {
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return Int(value)
        }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return Int(value)
        }
        return nil
    }

    func decodeInt64IfPresent(_ key: Key) -> Int64? {
        if let value = try? decodeIfPresent(Int64.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return Int64(value)
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return Int64(value)
        }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return Int64(value)
        }
        return nil
    }

    func decodeDoubleIfPresent(_ key: Key) -> Double? {
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return Double(value)
        }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return Double(value)
        }
        return nil
    }

    func decodeBoolIfPresent(_ key: Key) -> Bool? {
        if let value = try? decodeIfPresent(Bool.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return value != 0
        }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return ["true", "1", "yes"].contains(value.lowercased())
        }
        return nil
    }
}
