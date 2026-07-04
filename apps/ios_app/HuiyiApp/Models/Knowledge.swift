import Foundation

enum KnowledgeQueryScope: String, Codable, CaseIterable, Sendable {
    case local
    case cloud
    case all

    var label: String {
        switch self {
        case .local:
            return "本机"
        case .cloud:
            return "云端"
        case .all:
            return "全部"
        }
    }

    init(from decoder: Decoder) throws {
        let raw = (try? decoder.singleValueContainer().decode(String.self))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        switch raw {
        case "cloud":
            self = .cloud
        case "all":
            self = .all
        default:
            self = .local
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

struct KnowledgeTopic: Identifiable, Equatable, Sendable {
    var meetingId: String
    var title: String
    var subtitle: String

    var id: String { meetingId }
}

struct KnowledgeAskRequest: Codable, Sendable {
    var question: String
    var userId: String
    var userName: String?
    var limit: Int
    var taskIds: [String]
    var contextTaskIds: [String]
    var contextMessages: [KnowledgeContextItem]
    var scope: KnowledgeQueryScope
    var localSources: [LocalKnowledgeSource]

    enum CodingKeys: String, CodingKey {
        case question
        case userId = "user_id"
        case userName = "user_name"
        case limit
        case taskIds = "task_ids"
        case contextTaskIds = "context_task_ids"
        case contextMessages = "context_messages"
        case scope
        case localSources = "local_sources"
    }
}

struct KnowledgeContextItem: Codable, Equatable, Sendable {
    var role: String
    var text: String
    var sources: [KnowledgeSource]
}

struct LocalKnowledgeSource: Codable, Equatable, Sendable {
    var chunkId: String
    var taskId: String
    var title: String
    var text: String
    var chunkType: String?
    var meetingDate: String?
    var createdAt: String?
    var speaker: String?
    var timestamp: String?
    var startMs: Int64?
    var endMs: Int64?
    var score: Double

    enum CodingKeys: String, CodingKey {
        case chunkId = "chunk_id"
        case taskId = "task_id"
        case title
        case text
        case chunkType = "chunk_type"
        case meetingDate = "meeting_date"
        case createdAt = "created_at"
        case speaker
        case timestamp
        case startMs = "start_ms"
        case endMs = "end_ms"
        case score
    }
}

struct KnowledgeAskResponse: Codable, Equatable, Sendable {
    var question: String
    var answer: String
    var sources: [KnowledgeSource]
    var model: String?

    enum CodingKeys: String, CodingKey {
        case question
        case answer
        case sources
        case model
    }

    init(question: String, answer: String, sources: [KnowledgeSource], model: String? = nil) {
        self.question = question
        self.answer = answer.userFacingKnowledgeAnswer(question: question)
        self.sources = sources
        self.model = model?.nilIfBlank
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let decodedQuestion = container.decodeStringIfPresent(.question) ?? ""
        let decodedAnswer = container.decodeStringIfPresent(.answer)
        self.init(
            question: decodedQuestion,
            answer: decodedAnswer ?? decodedQuestion.defaultKnowledgeFallback(),
            sources: container.decodeArrayIfPresent(.sources),
            model: container.decodeStringIfPresent(.model)
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(question, forKey: .question)
        try container.encode(answer, forKey: .answer)
        try container.encode(sources, forKey: .sources)
        try container.encodeIfPresent(model, forKey: .model)
    }
}

struct KnowledgeSource: Codable, Equatable, Sendable {
    var chunkId: String
    var taskId: String
    var title: String
    var text: String
    var chunkType: String?
    var meetingDate: String?
    var speaker: String?
    var timestamp: String?
    var startMs: Int64?
    var endMs: Int64?
    var score: Double
    var scope: String

    enum CodingKeys: String, CodingKey {
        case chunkId = "chunk_id"
        case taskId = "task_id"
        case title
        case text
        case chunkType = "chunk_type"
        case meetingDate = "meeting_date"
        case speaker
        case timestamp
        case startMs = "start_ms"
        case endMs = "end_ms"
        case score
        case scope
    }

    init(
        chunkId: String,
        taskId: String,
        title: String,
        text: String,
        chunkType: String? = nil,
        meetingDate: String? = nil,
        speaker: String? = nil,
        timestamp: String? = nil,
        startMs: Int64? = nil,
        endMs: Int64? = nil,
        score: Double = 0,
        scope: String = "cloud"
    ) {
        self.chunkId = chunkId.nilIfBlank ?? ""
        self.taskId = taskId.nilIfBlank ?? ""
        self.title = title.nilIfBlank ?? "会议记录"
        self.text = text.nilIfBlank ?? ""
        self.chunkType = chunkType?.nilIfBlank
        self.meetingDate = meetingDate?.nilIfBlank
        self.speaker = speaker?.nilIfBlank
        self.timestamp = timestamp?.nilIfBlank
        self.startMs = startMs
        self.endMs = endMs
        self.score = score
        self.scope = scope.nilIfBlank ?? "cloud"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            chunkId: container.decodeStringIfPresent(.chunkId) ?? "",
            taskId: container.decodeStringIfPresent(.taskId) ?? "",
            title: container.decodeStringIfPresent(.title) ?? "会议记录",
            text: container.decodeStringIfPresent(.text) ?? "",
            chunkType: container.decodeStringIfPresent(.chunkType),
            meetingDate: container.decodeStringIfPresent(.meetingDate),
            speaker: container.decodeStringIfPresent(.speaker),
            timestamp: container.decodeStringIfPresent(.timestamp),
            startMs: container.decodeInt64IfPresent(.startMs),
            endMs: container.decodeInt64IfPresent(.endMs),
            score: container.decodeDoubleIfPresent(.score) ?? 0,
            scope: container.decodeStringIfPresent(.scope) ?? "cloud"
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(chunkId, forKey: .chunkId)
        try container.encode(taskId, forKey: .taskId)
        try container.encode(title, forKey: .title)
        try container.encode(text, forKey: .text)
        try container.encodeIfPresent(chunkType, forKey: .chunkType)
        try container.encodeIfPresent(meetingDate, forKey: .meetingDate)
        try container.encodeIfPresent(speaker, forKey: .speaker)
        try container.encodeIfPresent(timestamp, forKey: .timestamp)
        try container.encodeIfPresent(startMs, forKey: .startMs)
        try container.encodeIfPresent(endMs, forKey: .endMs)
        try container.encode(score, forKey: .score)
        try container.encode(scope, forKey: .scope)
    }
}

private extension String {
    var nilIfBlank: String? {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty || clean.lowercased() == "null" ? nil : clean
    }

    func userFacingKnowledgeAnswer(question: String) -> String {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "。"))
        let normalized: String
        if ["当前会议记录中没有找到明确依据", "未检索到相关内容", "未在当前范围找到依据"].contains(clean) {
            normalized = "没有找到相关会议内容。"
        } else if clean.contains("当前范围内没有会议记录") {
            normalized = clean.replacingOccurrences(of: "当前范围内", with: "").withChinesePeriod()
        } else if clean.contains("当前范围内没有会议内容") {
            normalized = clean.replacingOccurrences(of: "当前范围内", with: "").withChinesePeriod()
        } else if clean.contains("当前范围") || clean.contains("项目/客户筛选") {
            normalized = clean
                .replacingOccurrences(of: "未在当前范围找到依据", with: "没有找到相关会议内容")
                .replacingOccurrences(of: "。可以扩大时间范围或调整项目/客户筛选后重试", with: "")
                .withChinesePeriod()
        } else {
            normalized = self.withChinesePeriod()
        }
        return question.contextualizeKnowledgeFallback(answer: normalized)
    }

    func defaultKnowledgeFallback() -> String {
        contextualizeKnowledgeFallback(answer: "没有找到相关会议内容。")
    }

    func contextualizeKnowledgeFallback(answer: String) -> String {
        let cleanAnswer = answer.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "。"))
        let genericNoContent = [
            "没有找到相关会议内容",
            "没有找到相关会议记录",
            "没有会议内容",
            "没有会议记录",
            "未检索到相关内容"
        ].contains(cleanAnswer) || cleanAnswer.hasSuffix("没有找到相关会议内容") || cleanAnswer.hasSuffix("没有找到相关会议记录")
        guard genericNoContent else {
            return answer.withChinesePeriod()
        }
        let period = knowledgeQuestionPeriod()
        if asksSelfMeeting() {
            return "\(period)没有找到你参加过的会议记录。"
        }
        if asksMeetingLookup() {
            return "\(period)没有找到相关会议记录。"
        }
        if asksSelfTodo() {
            return "\(period)没有找到分配给你的待办。"
        }
        if asksTodo() {
            return "\(period)没有找到相关待办。"
        }
        if asksRisk() {
            return "\(period)没有找到相关风险。"
        }
        if asksDecision() {
            return "\(period)没有找到相关决策。"
        }
        return answer.withChinesePeriod()
    }

    func withChinesePeriod() -> String {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
        guard let last = clean.last else { return clean }
        return "。？！?!.;；".contains(last) ? clean : "\(clean)。"
    }

    func knowledgeQuestionPeriod() -> String {
        let clean = removingWhitespace()
        if clean.contains("昨天") { return "昨天" }
        if clean.contains("今天") { return "今天" }
        if clean.contains("本周") { return "本周" }
        if clean.contains("上周") { return "上周" }
        if clean.contains("本月") { return "本月" }
        if clean.contains("上月") { return "上月" }
        return ""
    }

    func asksSelfMeeting() -> Bool {
        let clean = removingWhitespace()
        return clean.range(of: "我|本人|自己", options: .regularExpression) != nil && (asksParticipant() || asksMeetingLookup())
    }

    func asksParticipant() -> Bool {
        let clean = removingWhitespace()
        return ["参会", "参加", "参与", "出席", "到会"].contains { clean.contains($0) }
    }

    func asksMeetingLookup() -> Bool {
        let clean = removingWhitespace()
        let asksMeeting = clean.contains("会议") || clean.contains("开会") || clean.contains("会吗")
        let lookupWords = ["有没有", "有开", "开会吗", "有会", "会议记录", "哪些会议", "有哪些会议", "几场", "多少场"]
        return (asksMeeting && lookupWords.contains { clean.contains($0) }) || asksParticipant()
    }

    func asksTodo() -> Bool {
        let clean = removingWhitespace()
        return ["待办", "任务", "要做", "负责", "跟进"].contains { clean.contains($0) }
    }

    func asksSelfTodo() -> Bool {
        let clean = removingWhitespace()
        return clean.range(of: "我|本人|自己", options: .regularExpression) != nil && asksTodo()
    }

    func asksRisk() -> Bool {
        let clean = removingWhitespace()
        return ["风险", "问题", "阻塞", "延期"].contains { clean.contains($0) }
    }

    func asksDecision() -> Bool {
        let clean = removingWhitespace()
        return ["决策", "决定", "结论", "定了"].contains { clean.contains($0) }
    }

    func removingWhitespace() -> String {
        replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
    }
}

private extension KeyedDecodingContainer {
    func decodeStringIfPresent(_ key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return value.nilIfBlank
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return String(value)
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
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
        if let value = try? decodeIfPresent(String.self, forKey: key),
           let number = Int64(value.trimmingCharacters(in: .whitespacesAndNewlines)) {
            return number
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
        if let value = try? decodeIfPresent(String.self, forKey: key),
           let number = Double(value.trimmingCharacters(in: .whitespacesAndNewlines)) {
            return number
        }
        return nil
    }

    func decodeArrayIfPresent<T: Decodable>(_ key: Key) -> [T] {
        (try? decodeIfPresent([T].self, forKey: key)) ?? []
    }
}
