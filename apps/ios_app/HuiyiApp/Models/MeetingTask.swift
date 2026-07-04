import Foundation

enum MeetingTaskSource: String, Codable, Sendable {
    case recording
    case importFile = "import"

    var displayName: String {
        switch self {
        case .recording:
            return "录音"
        case .importFile:
            return "导入"
        }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let value = (try? container.decode(String.self))?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        self = value == "recording" ? .recording : .importFile
    }
}

enum RemoteMeetingTaskStatus: String, Codable, Sendable {
    case waitingProcess = "waiting_process"
    case processing
    case finished
    case failed
    case canceled

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        switch (try? container.decode(String.self))?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "processing":
            self = .processing
        case "finished":
            self = .finished
        case "failed":
            self = .failed
        case "canceled", "cancelled":
            self = .canceled
        default:
            self = .waitingProcess
        }
    }
}

extension RemoteMeetingTaskStatus {
    init(clientStatus: ClientMeetingTaskStatus) {
        switch clientStatus {
        case .localSaved, .waitingProcess, .waitingRetry:
            self = .waitingProcess
        case .processing:
            self = .processing
        case .finished:
            self = .finished
        case .failed:
            self = .failed
        case .canceled:
            self = .canceled
        }
    }

    var displayName: String {
        switch self {
        case .waitingProcess:
            return "待处理"
        case .processing:
            return "正在处理"
        case .finished:
            return "已完成"
        case .failed:
            return "处理失败"
        case .canceled:
            return "已终止"
        }
    }
}

enum ClientMeetingTaskStatus: String, Codable, Sendable {
    case localSaved
    case waitingProcess
    case processing
    case waitingRetry
    case failed
    case canceled
    case finished

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        switch (try? container.decode(String.self))?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "local_saved", "localsaved":
            self = .localSaved
        case "processing":
            self = .processing
        case "waiting_retry", "waitingretry":
            self = .waitingRetry
        case "failed":
            self = .failed
        case "canceled", "cancelled":
            self = .canceled
        case "finished":
            self = .finished
        default:
            self = .waitingProcess
        }
    }

    var displayName: String {
        switch self {
        case .localSaved:
            return "已保存本地"
        case .waitingProcess:
            return "待处理"
        case .processing:
            return "正在处理"
        case .waitingRetry:
            return "可继续处理"
        case .failed:
            return "处理失败"
        case .canceled:
            return "已终止"
        case .finished:
            return "已完成"
        }
    }
}

enum RecognitionLanguage: String, Codable, CaseIterable, Sendable {
    case chinese = "zh-CN"
    case english = "en-US"
    case auto = "auto"

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        switch (try? container.decode(String.self))?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "en", "en-us", "english":
            self = .english
        case "auto", "mixed", "zh-en":
            self = .auto
        default:
            self = .chinese
        }
    }

    var displayName: String {
        switch self {
        case .chinese:
            return "中文"
        case .english:
            return "英文"
        case .auto:
            return "中英自由说"
        }
    }

    var remoteValue: String {
        rawValue
    }

    var realtimeLanguageCode: String? {
        switch self {
        case .chinese:
            return "zh"
        case .english:
            return "en"
        case .auto:
            return nil
        }
    }
}

enum KnowledgeIndexScope: String, Codable, Sendable {
    case local
    case cloud
    case all
    case excluded

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        switch (try? container.decode(String.self))?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "cloud":
            self = .cloud
        case "all":
            self = .all
        case "excluded":
            self = .excluded
        default:
            self = .local
        }
    }
}

enum TaskSyncScope: String, Codable, Sendable {
    case cloud
    case localProcessing = "local_processing"

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let value = (try? container.decode(String.self))?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        self = value == "local_processing" ? .localProcessing : .cloud
    }
}

enum CloudSyncStatus: String, Codable, Sendable {
    case localOnly
    case pendingUpload
    case synced
    case syncFailed
    case localProcessing

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        switch (try? container.decode(String.self))?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "pending_upload", "pendingupload":
            self = .pendingUpload
        case "synced":
            self = .synced
        case "sync_failed", "syncfailed":
            self = .syncFailed
        case "local_processing", "localprocessing":
            self = .localProcessing
        default:
            self = .localOnly
        }
    }

    var displayName: String {
        switch self {
        case .localOnly:
            return "仅本机"
        case .pendingUpload:
            return "待上传"
        case .synced:
            return "已同步"
        case .syncFailed:
            return "同步失败"
        case .localProcessing:
            return "本机处理中"
        }
    }
}

struct MeetingTask: Codable, Identifiable, Equatable, Sendable {
    let id: String
    var remoteTaskId: String?
    var fileId: String?
    var title: String
    var source: MeetingTaskSource
    var status: ClientMeetingTaskStatus
    var localFilePath: String
    var createdAtMillis: Int64
    var sizeLabel: String?
    var errorMessage: String?
    var progressPercent: Double
    var progressLabel: String?
    var progressStage: String?
    var syncStatus: CloudSyncStatus
    var confirmed: Bool
    var knowledgeScope: KnowledgeIndexScope
    var isPrivate: Bool
    var deviceId: String?
    var scheduleId: String?
    var scheduleNote: String?
    var recognitionLanguage: RecognitionLanguage
    var liveTranscripts: [TranscriptSegment] = []
    var missingAudioRanges: [MissingAudioRange]? = nil

    enum CodingKeys: String, CodingKey {
        case id
        case remoteTaskId
        case fileId
        case title
        case source
        case status
        case localFilePath
        case createdAtMillis
        case sizeLabel
        case errorMessage
        case progressPercent
        case progressLabel
        case progressStage
        case syncStatus
        case confirmed
        case knowledgeScope
        case isPrivate
        case deviceId
        case scheduleId
        case scheduleNote
        case recognitionLanguage
        case liveTranscripts
        case missingAudioRanges
    }

    init(
        id: String,
        remoteTaskId: String? = nil,
        fileId: String? = nil,
        title: String,
        source: MeetingTaskSource,
        status: ClientMeetingTaskStatus,
        localFilePath: String,
        createdAtMillis: Int64,
        sizeLabel: String? = nil,
        errorMessage: String? = nil,
        progressPercent: Double = 0,
        progressLabel: String? = nil,
        progressStage: String? = nil,
        syncStatus: CloudSyncStatus = .localOnly,
        confirmed: Bool = false,
        knowledgeScope: KnowledgeIndexScope = .local,
        isPrivate: Bool = false,
        deviceId: String? = nil,
        scheduleId: String? = nil,
        scheduleNote: String? = nil,
        recognitionLanguage: RecognitionLanguage = .chinese,
        liveTranscripts: [TranscriptSegment] = [],
        missingAudioRanges: [MissingAudioRange]? = nil
    ) {
        self.id = id
        self.remoteTaskId = remoteTaskId
        self.fileId = fileId
        self.title = title
        self.source = source
        self.status = status
        self.localFilePath = localFilePath
        self.createdAtMillis = createdAtMillis
        self.sizeLabel = sizeLabel
        self.errorMessage = errorMessage
        self.progressPercent = progressPercent
        self.progressLabel = progressLabel
        self.progressStage = progressStage
        self.syncStatus = syncStatus
        self.confirmed = confirmed
        self.knowledgeScope = knowledgeScope
        self.isPrivate = isPrivate
        self.deviceId = deviceId
        self.scheduleId = scheduleId
        self.scheduleNote = scheduleNote
        self.recognitionLanguage = recognitionLanguage
        self.liveTranscripts = liveTranscripts
        self.missingAudioRanges = missingAudioRanges
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: container.decodeStringIfPresent(.id) ?? UUID().uuidString,
            remoteTaskId: container.decodeStringIfPresent(.remoteTaskId),
            fileId: container.decodeStringIfPresent(.fileId),
            title: container.decodeStringIfPresent(.title) ?? "会议记录",
            source: (try? container.decode(MeetingTaskSource.self, forKey: .source)) ?? .importFile,
            status: (try? container.decode(ClientMeetingTaskStatus.self, forKey: .status)) ?? .waitingProcess,
            localFilePath: container.decodeStringIfPresent(.localFilePath) ?? "",
            createdAtMillis: container.decodeInt64IfPresent(.createdAtMillis) ?? Int64(Date().timeIntervalSince1970 * 1000),
            sizeLabel: container.decodeStringIfPresent(.sizeLabel),
            errorMessage: container.decodeStringIfPresent(.errorMessage),
            progressPercent: container.decodeDoubleIfPresent(.progressPercent) ?? 0,
            progressLabel: container.decodeStringIfPresent(.progressLabel),
            progressStage: container.decodeStringIfPresent(.progressStage),
            syncStatus: (try? container.decode(CloudSyncStatus.self, forKey: .syncStatus)) ?? .localOnly,
            confirmed: container.decodeBoolIfPresent(.confirmed) ?? false,
            knowledgeScope: (try? container.decode(KnowledgeIndexScope.self, forKey: .knowledgeScope)) ?? .local,
            isPrivate: container.decodeBoolIfPresent(.isPrivate) ?? false,
            deviceId: container.decodeStringIfPresent(.deviceId),
            scheduleId: container.decodeStringIfPresent(.scheduleId),
            scheduleNote: container.decodeStringIfPresent(.scheduleNote),
            recognitionLanguage: (try? container.decode(RecognitionLanguage.self, forKey: .recognitionLanguage)) ?? .chinese,
            liveTranscripts: (try? container.decode([TranscriptSegment].self, forKey: .liveTranscripts)) ?? [],
            missingAudioRanges: try? container.decodeIfPresent([MissingAudioRange].self, forKey: .missingAudioRanges)
        )
    }

    var canOpenProcessingPage: Bool {
        switch status {
        case .waitingProcess, .processing, .waitingRetry, .failed, .canceled:
            return true
        case .localSaved, .finished:
            return false
        }
    }

    var requiresExplicitRetry: Bool {
        status == .failed || status == .waitingRetry || status == .canceled
    }

    func toRemoteTaskFallback(syncScope: TaskSyncScope? = nil, statusOverride: RemoteMeetingTaskStatus? = nil) -> RemoteMeetingTask {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ssZ"
        let dateText = formatter.string(from: Date(timeIntervalSince1970: TimeInterval(createdAtMillis) / 1000))
        let resolvedSyncScope = syncScope ?? (remoteTaskId == nil ? .localProcessing : .cloud)
        return RemoteMeetingTask(
            id: remoteTaskId ?? id,
            fileId: fileId ?? "",
            clientTaskId: id,
            title: title,
            source: source,
            status: statusOverride ?? RemoteMeetingTaskStatus(clientStatus: status),
            errorMessage: errorMessage,
            progressPercent: progressPercent,
            progressLabel: progressLabel,
            progressStage: progressStage,
            syncScope: resolvedSyncScope,
            knowledgeScope: knowledgeScope,
            isPrivate: isPrivate,
            deviceId: deviceId,
            confirmed: confirmed,
            recognitionLanguage: recognitionLanguage,
            createdAtMillis: createdAtMillis,
            createdAt: dateText,
            updatedAt: dateText
        )
    }

    func hasSyncContentChanged(since previous: MeetingTask) -> Bool {
        title != previous.title ||
            source != previous.source ||
            status != previous.status ||
            localFilePath != previous.localFilePath ||
            confirmed != previous.confirmed ||
            isPrivate != previous.isPrivate ||
            scheduleId != previous.scheduleId ||
            scheduleNote != previous.scheduleNote
    }
}

struct RemoteTaskSnapshot: Codable, Sendable {
    let id: String
    let status: RemoteMeetingTaskStatus
    let errorMessage: String?
    let progressPercent: Double
    let progressLabel: String?
    let progressStage: String?

    enum CodingKeys: String, CodingKey {
        case id
        case status
        case errorMessage = "error_message"
        case progressPercent = "progress_percent"
        case progressLabel = "progress_label"
        case progressStage = "progress_stage"
    }

    init(id: String, status: RemoteMeetingTaskStatus, errorMessage: String? = nil, progressPercent: Double = 0, progressLabel: String? = nil, progressStage: String? = nil) {
        self.id = id
        self.status = status
        self.errorMessage = errorMessage
        self.progressPercent = progressPercent
        self.progressLabel = progressLabel
        self.progressStage = progressStage
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: container.decodeStringIfPresent(.id) ?? UUID().uuidString,
            status: (try? container.decode(RemoteMeetingTaskStatus.self, forKey: .status)) ?? .waitingProcess,
            errorMessage: container.decodeStringIfPresent(.errorMessage),
            progressPercent: container.decodeDoubleIfPresent(.progressPercent) ?? 0,
            progressLabel: container.decodeStringIfPresent(.progressLabel),
            progressStage: container.decodeStringIfPresent(.progressStage)
        )
    }

    var clientStatus: ClientMeetingTaskStatus {
        if status == .waitingProcess, progressStage == "waiting_retry" {
            return .waitingRetry
        }
        switch status {
        case .waitingProcess:
            return .waitingProcess
        case .processing:
            return .processing
        case .finished:
            return .finished
        case .failed:
            return .failed
        case .canceled:
            return .canceled
        }
    }
}

struct RemoteMeetingTask: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let fileId: String
    let clientTaskId: String?
    let title: String
    let source: MeetingTaskSource
    let status: RemoteMeetingTaskStatus
    let errorMessage: String?
    let progressPercent: Double
    let progressLabel: String?
    let progressStage: String?
    let syncScope: TaskSyncScope
    let knowledgeScope: KnowledgeIndexScope
    let isPrivate: Bool
    let deviceId: String?
    let confirmed: Bool
    let recognitionLanguage: RecognitionLanguage
    let createdAtMillis: Int64?
    let createdAt: String
    let updatedAt: String

    enum CodingKeys: String, CodingKey {
        case id
        case fileId = "file_id"
        case clientTaskId = "client_task_id"
        case title
        case source
        case status
        case errorMessage = "error_message"
        case progressPercent = "progress_percent"
        case progressLabel = "progress_label"
        case progressStage = "progress_stage"
        case syncScope = "sync_scope"
        case knowledgeScope = "knowledge_scope"
        case isPrivate = "is_private"
        case deviceId = "device_id"
        case confirmed
        case recognitionLanguage = "recognition_language"
        case createdAtMillis = "created_at_millis"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }

    init(
        id: String,
        fileId: String,
        clientTaskId: String? = nil,
        title: String,
        source: MeetingTaskSource,
        status: RemoteMeetingTaskStatus,
        errorMessage: String? = nil,
        progressPercent: Double = 0,
        progressLabel: String? = nil,
        progressStage: String? = nil,
        syncScope: TaskSyncScope = .cloud,
        knowledgeScope: KnowledgeIndexScope = .local,
        isPrivate: Bool = false,
        deviceId: String? = nil,
        confirmed: Bool = false,
        recognitionLanguage: RecognitionLanguage = .chinese,
        createdAtMillis: Int64? = nil,
        createdAt: String = "",
        updatedAt: String = ""
    ) {
        self.id = id
        self.fileId = fileId
        self.clientTaskId = clientTaskId
        self.title = title
        self.source = source
        self.status = status
        self.errorMessage = errorMessage
        self.progressPercent = progressPercent
        self.progressLabel = progressLabel
        self.progressStage = progressStage
        self.syncScope = syncScope
        self.knowledgeScope = knowledgeScope
        self.isPrivate = isPrivate
        self.deviceId = deviceId
        self.confirmed = confirmed
        self.recognitionLanguage = recognitionLanguage
        self.createdAtMillis = createdAtMillis
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let id = container.decodeStringIfPresent(.id) ?? UUID().uuidString
        self.init(
            id: id,
            fileId: container.decodeStringIfPresent(.fileId) ?? "",
            clientTaskId: container.decodeStringIfPresent(.clientTaskId),
            title: container.decodeStringIfPresent(.title) ?? "会议记录",
            source: (try? container.decode(MeetingTaskSource.self, forKey: .source)) ?? .importFile,
            status: (try? container.decode(RemoteMeetingTaskStatus.self, forKey: .status)) ?? .waitingProcess,
            errorMessage: container.decodeStringIfPresent(.errorMessage),
            progressPercent: container.decodeDoubleIfPresent(.progressPercent) ?? 0,
            progressLabel: container.decodeStringIfPresent(.progressLabel),
            progressStage: container.decodeStringIfPresent(.progressStage),
            syncScope: (try? container.decode(TaskSyncScope.self, forKey: .syncScope)) ?? .cloud,
            knowledgeScope: (try? container.decode(KnowledgeIndexScope.self, forKey: .knowledgeScope)) ?? .local,
            isPrivate: container.decodeBoolIfPresent(.isPrivate) ?? false,
            deviceId: container.decodeStringIfPresent(.deviceId),
            confirmed: container.decodeBoolIfPresent(.confirmed) ?? false,
            recognitionLanguage: (try? container.decode(RecognitionLanguage.self, forKey: .recognitionLanguage)) ?? .chinese,
            createdAtMillis: container.decodeInt64IfPresent(.createdAtMillis),
            createdAt: container.decodeStringIfPresent(.createdAt) ?? "",
            updatedAt: container.decodeStringIfPresent(.updatedAt) ?? ""
        )
    }

    var clientStatus: ClientMeetingTaskStatus {
        if status == .waitingProcess, progressStage == "waiting_retry" {
            return .waitingRetry
        }
        switch status {
        case .waitingProcess:
            return .waitingProcess
        case .processing:
            return .processing
        case .finished:
            return .finished
        case .failed:
            return .failed
        case .canceled:
            return .canceled
        }
    }

    func toClientTask(
        localFilePath: String = "",
        fallbackId: String? = nil,
        fallbackCreatedAtMillis: Int64? = nil,
        fallbackSizeLabel: String? = nil,
        fallbackScheduleId: String? = nil,
        fallbackScheduleNote: String? = nil,
        fallbackRecognitionLanguage: RecognitionLanguage? = nil,
        fallbackMissingAudioRanges: [MissingAudioRange]? = nil
    ) -> MeetingTask {
        let localId = clientTaskId ?? fallbackId ?? id
        let resolvedRemoteTaskId = syncScope == .localProcessing ? nil : id
        let resolvedSyncStatus: CloudSyncStatus = syncScope == .localProcessing ? .localProcessing : .synced
        return MeetingTask(
            id: localId,
            remoteTaskId: resolvedRemoteTaskId,
            fileId: fileId,
            title: title,
            source: source,
            status: clientStatus,
            localFilePath: localFilePath,
            createdAtMillis: createdAtMillis ?? fallbackCreatedAtMillis ?? 0,
            sizeLabel: fallbackSizeLabel,
            errorMessage: errorMessage,
            progressPercent: progressPercent,
            progressLabel: progressLabel,
            progressStage: progressStage,
            syncStatus: resolvedSyncStatus,
            confirmed: confirmed,
            knowledgeScope: knowledgeScope,
            isPrivate: isPrivate,
            deviceId: deviceId,
            scheduleId: fallbackScheduleId,
            scheduleNote: fallbackScheduleNote,
            recognitionLanguage: fallbackRecognitionLanguage ?? recognitionLanguage,
            liveTranscripts: [],
            missingAudioRanges: fallbackMissingAudioRanges
        )
    }
}

struct FileRecord: Codable, Equatable, Sendable {
    let id: String
    let originalName: String
    let storedPath: String
    let contentType: String
    let sizeBytes: Int64
    let createdAt: String

    enum CodingKeys: String, CodingKey {
        case id
        case originalName = "original_name"
        case storedPath = "stored_path"
        case contentType = "content_type"
        case sizeBytes = "size_bytes"
        case createdAt = "created_at"
    }

    init(id: String, originalName: String, storedPath: String, contentType: String, sizeBytes: Int64, createdAt: String) {
        self.id = id
        self.originalName = originalName
        self.storedPath = storedPath
        self.contentType = contentType
        self.sizeBytes = sizeBytes
        self.createdAt = createdAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: container.decodeStringIfPresent(.id) ?? "",
            originalName: container.decodeStringIfPresent(.originalName) ?? "",
            storedPath: container.decodeStringIfPresent(.storedPath) ?? "",
            contentType: container.decodeStringIfPresent(.contentType) ?? "application/octet-stream",
            sizeBytes: container.decodeInt64IfPresent(.sizeBytes) ?? 0,
            createdAt: container.decodeStringIfPresent(.createdAt) ?? ""
        )
    }
}

struct UploadResponse: Codable, Sendable {
    let file: FileRecord
    let task: RemoteMeetingTask
}

struct RemoteTaskDetail: Codable, Sendable {
    let task: RemoteMeetingTask
    let file: FileRecord?
    let result: MeetingProcessingResult?
}

private extension KeyedDecodingContainer {
    func decodeStringIfPresent(_ key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return clean.isEmpty || clean.lowercased() == "null" ? nil : clean
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return String(value)
        }
        if let value = try? decodeIfPresent(Int64.self, forKey: key) {
            return String(value)
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
