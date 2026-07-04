import Foundation

struct MeetingProcessingResult: Codable, Equatable, Sendable {
    var taskId: String
    var sourceFilePath: String
    var participants: String?
    var tags: [String]
    var summary: String
    var topics: [TopicItem]
    var decisions: [String]
    var todos: [TodoItem]
    var risks: [RiskItem]
    var transcripts: [TranscriptSegment]
    var generatedAt: String
    var remoteTaskId: String?

    enum CodingKeys: String, CodingKey {
        case taskId = "task_id"
        case sourceFilePath = "source_file_path"
        case participants
        case tags
        case summary
        case topics
        case decisions
        case todos
        case risks
        case transcripts
        case generatedAt = "generated_at"
        case remoteTaskId = "remote_task_id"
    }

    init(
        taskId: String,
        sourceFilePath: String,
        participants: String? = nil,
        tags: [String] = [],
        summary: String,
        topics: [TopicItem] = [],
        decisions: [String] = [],
        todos: [TodoItem] = [],
        risks: [RiskItem] = [],
        transcripts: [TranscriptSegment],
        generatedAt: String = "",
        remoteTaskId: String? = nil
    ) {
        self.taskId = taskId
        self.sourceFilePath = sourceFilePath
        self.participants = participants?.nonEmptyResultString
        self.tags = tags
        self.summary = summary
        self.topics = topics
        self.decisions = decisions
        self.todos = todos
        self.risks = risks
        self.transcripts = transcripts
        self.generatedAt = generatedAt
        self.remoteTaskId = remoteTaskId?.nonEmptyResultString
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            taskId: container.decodeStringIfPresent(.taskId) ?? "",
            sourceFilePath: container.decodeStringIfPresent(.sourceFilePath) ?? "",
            participants: container.decodeStringIfPresent(.participants),
            tags: (try? container.decode([String].self, forKey: .tags)) ?? [],
            summary: container.decodeStringIfPresent(.summary) ?? "",
            topics: (try? container.decode([TopicItem].self, forKey: .topics)) ?? [],
            decisions: (try? container.decode([String].self, forKey: .decisions)) ?? [],
            todos: (try? container.decode([TodoItem].self, forKey: .todos)) ?? [],
            risks: (try? container.decode([RiskItem].self, forKey: .risks)) ?? [],
            transcripts: (try? container.decode([TranscriptSegment].self, forKey: .transcripts)) ?? [],
            generatedAt: container.decodeStringIfPresent(.generatedAt) ?? "",
            remoteTaskId: container.decodeStringIfPresent(.remoteTaskId)
        )
    }
}

struct TranscriptSegment: Codable, Equatable, Sendable {
    var speaker: String
    var text: String
    var timestamp: String
    var startMs: Int64?
    var endMs: Int64?
    var speakerId: String?
    var confidence: Double?

    enum CodingKeys: String, CodingKey {
        case speaker
        case text
        case timestamp
        case startMs = "start_ms"
        case endMs = "end_ms"
        case speakerId = "speaker_id"
        case confidence
    }

    init(speaker: String, text: String, timestamp: String, startMs: Int64? = nil, endMs: Int64? = nil, speakerId: String? = nil, confidence: Double? = nil) {
        self.speaker = speaker.isEmpty ? "说话人" : speaker
        self.text = text
        self.timestamp = timestamp.isEmpty ? "00:00" : timestamp
        self.startMs = startMs
        self.endMs = endMs
        self.speakerId = speakerId?.nonEmptyResultString
        self.confidence = confidence
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            speaker: container.decodeStringIfPresent(.speaker) ?? "说话人",
            text: container.decodeStringIfPresent(.text) ?? "",
            timestamp: container.decodeStringIfPresent(.timestamp) ?? "00:00",
            startMs: container.decodeInt64IfPresent(.startMs),
            endMs: container.decodeInt64IfPresent(.endMs),
            speakerId: container.decodeStringIfPresent(.speakerId),
            confidence: container.decodeDoubleIfPresent(.confidence)
        )
    }
}

struct SpeakerIdentity: Identifiable, Equatable, Sendable {
    let id: String
    let displayName: String
}

extension TranscriptSegment {
    func normalizedSpeakerIdentity() -> TranscriptSegment {
        var copy = self
        let cleanSpeaker = speaker.trimmingCharacters(in: .whitespacesAndNewlines)
        copy.speaker = cleanSpeaker.isEmpty ? "说话人" : cleanSpeaker
        let cleanSpeakerId = speakerId?.trimmingCharacters(in: .whitespacesAndNewlines)
        copy.speakerId = cleanSpeakerId?.isEmpty == false ? cleanSpeakerId : speakerIdentityId(for: copy.speaker)
        return copy
    }

    var stableSpeakerId: String {
        if let speakerId, !speakerId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return speakerId
        }
        return speakerIdentityId(for: speaker)
    }

    var timeRangeLabel: String {
        if let startMs, let endMs, endMs > startMs {
            return "\(Self.clockLabel(startMs))-\(Self.clockLabel(endMs))"
        }
        if !timestamp.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return timestamp
        }
        return "00:00"
    }

    private static func clockLabel(_ millis: Int64) -> String {
        let totalSeconds = max(0, millis / 1000)
        return String(format: "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}

func speakerIdentityId(for name: String) -> String {
    let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
    let source = clean.isEmpty ? "speaker" : clean
    let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
    let mapped = source.unicodeScalars.map { scalar in
        allowed.contains(scalar) ? Character(scalar).description.lowercased() : "-"
    }.joined()
    let collapsed = mapped
        .split(separator: "-")
        .joined(separator: "-")
        .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
    return collapsed.isEmpty ? "speaker" : collapsed
}

extension Array where Element == TranscriptSegment {
    var speakerIdentities: [SpeakerIdentity] {
        var seen: Set<String> = []
        var output: [SpeakerIdentity] = []
        for segment in self {
            let id = segment.stableSpeakerId
            guard !seen.contains(id) else { continue }
            seen.insert(id)
            output.append(SpeakerIdentity(id: id, displayName: segment.speaker))
        }
        return output
    }
}

struct TopicItem: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var title: String
    var summary: String
    var source: String
    var sourceTimestamp: String?

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case summary
        case source
        case sourceTimestamp = "source_timestamp"
    }

    init(id: String, title: String, summary: String, source: String, sourceTimestamp: String? = nil) {
        self.id = id.isEmpty ? "topic-\(UUID().uuidString)" : id
        self.title = title
        self.summary = summary
        self.source = source
        self.sourceTimestamp = sourceTimestamp?.nonEmptyResultString
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: container.decodeStringIfPresent(.id) ?? "",
            title: container.decodeStringIfPresent(.title) ?? "",
            summary: container.decodeStringIfPresent(.summary) ?? "",
            source: container.decodeStringIfPresent(.source) ?? "",
            sourceTimestamp: container.decodeStringIfPresent(.sourceTimestamp)
        )
    }
}

struct TodoItem: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var title: String
    var source: String
    var done: Bool
    var sourceTimestamp: String?
    var meetingId: String?
    var meetingTitle: String?
    var description: String
    var assignee: String?
    var assigneeId: String?
    var dueAt: String?
    var dueAtMillis: Int64?
    var priority: String
    var status: String
    var completedAt: String?
    var completedAtMillis: Int64?
    var sourceSegmentIndex: Int?
    var lockedFields: [String]

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case source
        case done
        case sourceTimestamp = "source_timestamp"
        case meetingId = "meeting_id"
        case meetingTitle = "meeting_title"
        case description
        case assignee
        case assigneeId = "assignee_id"
        case dueAt = "due_at"
        case dueAtMillis = "due_at_millis"
        case priority
        case status
        case completedAt = "completed_at"
        case completedAtMillis = "completed_at_millis"
        case sourceSegmentIndex = "source_segment_index"
        case lockedFields = "locked_fields"
    }

    init(
        id: String,
        title: String,
        source: String,
        done: Bool,
        sourceTimestamp: String? = nil,
        meetingId: String? = nil,
        meetingTitle: String? = nil,
        description: String = "",
        assignee: String? = nil,
        assigneeId: String? = nil,
        dueAt: String? = nil,
        dueAtMillis: Int64? = nil,
        priority: String = "medium",
        status: String? = nil,
        completedAt: String? = nil,
        completedAtMillis: Int64? = nil,
        sourceSegmentIndex: Int? = nil,
        lockedFields: [String] = []
    ) {
        self.id = id.isEmpty ? "todo-\(UUID().uuidString)" : id
        self.title = title
        self.source = source
        self.done = done
        self.sourceTimestamp = sourceTimestamp?.nonEmptyResultString
        self.meetingId = meetingId?.nonEmptyResultString
        self.meetingTitle = meetingTitle?.nonEmptyResultString
        self.description = description
        self.assignee = assignee?.nonEmptyResultString
        self.assigneeId = assigneeId?.nonEmptyResultString
        self.dueAt = dueAt?.nonEmptyResultString
        self.dueAtMillis = dueAtMillis
        self.priority = priority.normalizedTodoPriority
        self.status = status?.normalizedTodoStatus(done: done) ?? (done ? "done" : "pending_confirm")
        self.completedAt = completedAt?.nonEmptyResultString
        self.completedAtMillis = completedAtMillis
        self.sourceSegmentIndex = sourceSegmentIndex
        self.lockedFields = lockedFields
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let done = container.decodeBoolIfPresent(.done) ?? false
        self.init(
            id: container.decodeStringIfPresent(.id) ?? "",
            title: container.decodeStringIfPresent(.title) ?? "",
            source: container.decodeStringIfPresent(.source) ?? "",
            done: done,
            sourceTimestamp: container.decodeStringIfPresent(.sourceTimestamp),
            meetingId: container.decodeStringIfPresent(.meetingId),
            meetingTitle: container.decodeStringIfPresent(.meetingTitle),
            description: container.decodeStringIfPresent(.description) ?? "",
            assignee: container.decodeStringIfPresent(.assignee),
            assigneeId: container.decodeStringIfPresent(.assigneeId),
            dueAt: container.decodeStringIfPresent(.dueAt),
            dueAtMillis: container.decodeInt64IfPresent(.dueAtMillis),
            priority: container.decodeStringIfPresent(.priority) ?? "medium",
            status: container.decodeStringIfPresent(.status),
            completedAt: container.decodeStringIfPresent(.completedAt),
            completedAtMillis: container.decodeInt64IfPresent(.completedAtMillis),
            sourceSegmentIndex: container.decodeIntIfPresent(.sourceSegmentIndex),
            lockedFields: (try? container.decode([String].self, forKey: .lockedFields)) ?? []
        )
    }
}

extension TodoItem {
    var effectiveStatus: String {
        done && status != "canceled" ? "done" : status
    }

    mutating func lockField(_ field: String) {
        if !lockedFields.contains(field) {
            lockedFields.append(field)
        }
    }

    mutating func appendLockedFields(
        from previous: TodoItem,
        title: String,
        assignee: String,
        dueAt: String,
        priority: String,
        description: String,
        status: String
    ) {
        if previous.title.trimmingCharacters(in: .whitespacesAndNewlines) != title {
            lockField("title")
        }
        if (previous.assignee ?? "").trimmingCharacters(in: .whitespacesAndNewlines) != assignee {
            lockField("assignee")
        }
        if (previous.dueAt ?? "").trimmingCharacters(in: .whitespacesAndNewlines) != dueAt {
            lockField("due")
        }
        if previous.priority.normalizedTodoPriority != priority.normalizedTodoPriority {
            lockField("priority")
        }
        if previous.description.trimmingCharacters(in: .whitespacesAndNewlines) != description {
            lockField("description")
        }
        if previous.effectiveStatus != status || previous.done != (status == "done") {
            lockField("status")
        }
    }

    func matchesCompletedRegeneratedTodo(_ regenerated: TodoItem) -> Bool {
        let titleScore = title.todoSimilarity(to: regenerated.title)
        if titleScore >= 0.96, todoAssigneeCompatible(regenerated) {
            return true
        }
        if let sourceSegmentIndex,
           let regeneratedIndex = regenerated.sourceSegmentIndex,
           sourceSegmentIndex == regeneratedIndex,
           titleScore >= 0.58 {
            return true
        }
        if let sourceTimestamp,
           let regeneratedTimestamp = regenerated.sourceTimestamp,
           sourceTimestamp == regeneratedTimestamp,
           titleScore >= 0.58 {
            return true
        }
        let sourceScore = [source, description].joined(separator: " ").todoSimilarity(
            to: [regenerated.source, regenerated.description].joined(separator: " ")
        )
        return titleScore >= 0.84 && sourceScore >= 0.36 && todoAssigneeCompatible(regenerated)
    }

    private func todoAssigneeCompatible(_ other: TodoItem) -> Bool {
        let left = (assignee ?? "").normalizedTodoMatchText
        let right = (other.assignee ?? "").normalizedTodoMatchText
        if left.isEmpty || right.isEmpty {
            return true
        }
        return left == right || left.contains(right) || right.contains(left)
    }
}

extension String {
    var normalizedTodoPriority: String {
        switch trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "high", "urgent", "高": return "high"
        case "low", "低": return "low"
        default: return "medium"
        }
    }

    var normalizedTodoMatchText: String {
        lowercased()
            .replacingOccurrences(of: "[\\p{P}\\p{S}\\s]+", with: "", options: .regularExpression)
            .replacingOccurrences(of: "需要", with: "")
            .replacingOccurrences(of: "负责", with: "")
            .replacingOccurrences(of: "处理", with: "")
            .replacingOccurrences(of: "完成", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func todoSimilarity(to other: String) -> Double {
        let left = normalizedTodoMatchText
        let right = other.normalizedTodoMatchText
        guard !left.isEmpty, !right.isEmpty else { return 0 }
        if left == right { return 1 }
        let shorter = left.count <= right.count ? left : right
        let longer = left.count > right.count ? left : right
        if shorter.count >= 6, longer.contains(shorter) {
            return Double(shorter.count) / Double(longer.count)
        }
        let leftBigrams = left.todoBigrams
        let rightBigrams = right.todoBigrams
        guard !leftBigrams.isEmpty, !rightBigrams.isEmpty else { return 0 }
        let overlap = leftBigrams.reduce(0) { total, item in
            total + min(item.value, rightBigrams[item.key] ?? 0)
        }
        let denominator = leftBigrams.values.reduce(0, +) + rightBigrams.values.reduce(0, +)
        return denominator == 0 ? 0 : (2 * Double(overlap)) / Double(denominator)
    }

    private var todoBigrams: [String: Int] {
        let characters = Array(self)
        if characters.count <= 1 {
            return isEmpty ? [:] : [self: 1]
        }
        var counts: [String: Int] = [:]
        for index in 0..<(characters.count - 1) {
            let endIndex = index + 1
            let token = String(characters[index...endIndex])
            counts[token, default: 0] += 1
        }
        return counts
    }
}

extension MeetingProcessingResult {
    func normalized(taskId: String, remoteTaskId: String?, sourceFilePath: String) -> MeetingProcessingResult {
        var copy = self
        copy.taskId = taskId
        copy.remoteTaskId = remoteTaskId
        if !sourceFilePath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            copy.sourceFilePath = sourceFilePath
        }
        copy.transcripts = transcripts.map { $0.normalizedSpeakerIdentity() }
        copy.todos = copy.todos.map { todo in
            guard todo.sourceSegmentIndex == nil else { return todo }
            var normalizedTodo = todo
            normalizedTodo.sourceSegmentIndex = copy.sourceIndex(
                timestamp: todo.sourceTimestamp,
                text: [todo.title, todo.description, todo.source].joined(separator: " ")
            )
            return normalizedTodo
        }
        return copy
    }

    private func sourceIndex(timestamp: String?, text: String) -> Int? {
        guard !transcripts.isEmpty else { return nil }
        let cleanTimestamp = timestamp?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !cleanTimestamp.isEmpty,
           let timeMatch = transcripts.firstIndex(where: { segment in
               segment.timestamp == cleanTimestamp || segment.timeRangeLabel.contains(cleanTimestamp)
           }) {
            return timeMatch
        }
        let cleanText = text.normalizedTodoMatchText
        guard !cleanText.isEmpty else { return nil }
        let best = transcripts.enumerated()
            .map { index, segment in
                (index: index, score: cleanText.todoSimilarity(to: segment.text))
            }
            .max { $0.score < $1.score }
        guard let best, best.score > 0.18 else { return nil }
        return best.index
    }
}

struct RiskItem: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var title: String
    var level: String
    var description: String
    var recommendation: String
    var source: String
    var sourceTimestamp: String?

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case level
        case description
        case recommendation
        case source
        case sourceTimestamp = "source_timestamp"
    }

    init(id: String, title: String, level: String, description: String, recommendation: String, source: String, sourceTimestamp: String? = nil) {
        self.id = id.isEmpty ? "risk-\(UUID().uuidString)" : id
        self.title = title
        self.level = level
        self.description = description
        self.recommendation = recommendation
        self.source = source
        self.sourceTimestamp = sourceTimestamp?.nonEmptyResultString
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: container.decodeStringIfPresent(.id) ?? "",
            title: container.decodeStringIfPresent(.title) ?? "",
            level: container.decodeStringIfPresent(.level) ?? "",
            description: container.decodeStringIfPresent(.description) ?? "",
            recommendation: container.decodeStringIfPresent(.recommendation) ?? "",
            source: container.decodeStringIfPresent(.source) ?? "",
            sourceTimestamp: container.decodeStringIfPresent(.sourceTimestamp)
        )
    }
}

private extension String {
    var nonEmptyResultString: String? {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty || clean.lowercased() == "null" ? nil : clean
    }

    func normalizedTodoStatus(done: Bool) -> String {
        switch trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "todo": return "todo"
        case "in_progress": return "in_progress"
        case "done", "completed": return "done"
        case "canceled", "cancelled": return "canceled"
        case "pending_confirm": return "pending_confirm"
        default: return done ? "done" : "pending_confirm"
        }
    }
}

private extension KeyedDecodingContainer {
    func decodeStringIfPresent(_ key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return value.nonEmptyResultString
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return String(value)
        }
        if let value = try? decodeIfPresent(Int64.self, forKey: key) {
            return String(value)
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return String(value)
        }
        return nil
    }

    func decodeIntIfPresent(_ key: Key) -> Int? {
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Int64.self, forKey: key) {
            return Int(value)
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
