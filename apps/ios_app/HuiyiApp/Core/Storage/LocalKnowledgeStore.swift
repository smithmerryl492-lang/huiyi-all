import Foundation

struct StoredLocalKnowledgeChunk: Codable, Equatable, Sendable {
    var chunkId: String
    var taskId: String
    var title: String
    var text: String
    var chunkType: String
    var meetingDate: String
    var createdAt: String?
    var speaker: String?
    var timestamp: String?
    var startMs: Int64?
    var endMs: Int64?
    var vector: [Double]
    var knowledgeScope: KnowledgeIndexScope

    func toRequestSource(score: Double = 1) -> LocalKnowledgeSource {
        LocalKnowledgeSource(
            chunkId: chunkId,
            taskId: taskId,
            title: title,
            text: text,
            chunkType: chunkType,
            meetingDate: meetingDate,
            createdAt: createdAt,
            speaker: speaker,
            timestamp: timestamp,
            startMs: startMs,
            endMs: endMs,
            score: score
        )
    }
}

final class LocalKnowledgeStore {
    private let cacheStore: ClientCacheStore
    private let key = "local_knowledge_chunks"

    init(cacheStore: ClientCacheStore) {
        self.cacheStore = cacheStore
    }

    func replaceMeetingIndex(task: MeetingTask, result: MeetingProcessingResult) {
        deleteMeetingIndex(taskId: task.id)
        guard !task.isPrivate, task.knowledgeScope != .excluded else { return }
        let chunks = Self.buildChunks(task: task, result: result)
        guard !chunks.isEmpty else { return }
        let merged = Dictionary(grouping: loadAll() + chunks, by: \.chunkId)
            .compactMap { $0.value.last }
            .sorted { $0.chunkId < $1.chunkId }
        try? cacheStore.save(merged, key: key)
    }

    func deleteMeetingIndex(taskId: String) {
        let remaining = loadAll().filter { $0.taskId != taskId }
        try? cacheStore.save(remaining, key: key)
    }

    func list(scope: KnowledgeQueryScope) -> [StoredLocalKnowledgeChunk] {
        loadAll().filter { chunk in
            switch scope {
            case .local, .all:
                return true
            case .cloud:
                return false
            }
        }
    }

    func search(question: String, scope: KnowledgeQueryScope, limit: Int = 8) -> [StoredLocalKnowledgeChunk] {
        let queryVector = Self.embed(question)
        return list(scope: scope)
            .map { chunk in
                (chunk, Self.cosine(queryVector, chunk.vector) + Self.lexicalScore(question: question, chunk: chunk))
            }
            .filter { $0.1 > 0.03 }
            .sorted { $0.1 > $1.1 }
            .prefix(limit)
            .map(\.0)
    }

    func recentTopics(limit: Int = 5) -> [KnowledgeTopic] {
        let chunks = loadAll()
        let grouped = Dictionary(grouping: chunks, by: \.taskId)
        return grouped.compactMap { taskId, taskChunks -> KnowledgeTopic? in
            let sorted = taskChunks.sorted { ($0.createdAt ?? "") > ($1.createdAt ?? "") }
            guard let first = sorted.first else { return nil }
            let topic = sorted.first { $0.chunkType == "topic" } ?? sorted.first { $0.chunkType == "summary" } ?? first
            let subtitle = [
                topic.meetingDate,
                topic.speaker,
                topic.text.trimmingCharacters(in: .whitespacesAndNewlines)
            ]
                .compactMap { $0?.trimmedNilIfEmpty }
                .joined(separator: " · ")
            return KnowledgeTopic(
                meetingId: taskId,
                title: first.title,
                subtitle: subtitle.isEmpty ? "查看会议知识内容" : subtitle
            )
        }
        .sorted { left, right in
            let leftDate = chunks.first { $0.taskId == left.meetingId }?.createdAt ?? ""
            let rightDate = chunks.first { $0.taskId == right.meetingId }?.createdAt ?? ""
            return leftDate > rightDate
        }
        .prefix(limit)
        .map { $0 }
    }

    func clearAll() {
        cacheStore.remove(key: key)
    }

    private func loadAll() -> [StoredLocalKnowledgeChunk] {
        cacheStore.load([StoredLocalKnowledgeChunk].self, key: key) ?? []
    }

    private static func buildChunks(task: MeetingTask, result: MeetingProcessingResult) -> [StoredLocalKnowledgeChunk] {
        let title = task.title.removingLastExtension
        let meetingDate = task.createdAtMillis.meetingDateLabel
        let createdAt = iso8601String(from: task.createdAtMillis)
        let scope = task.knowledgeScope
        var chunks: [StoredLocalKnowledgeChunk] = []
        let metadataText = [
            result.participants.flatMap { $0.trimmedNilIfEmpty }.map { "参会人：\($0)" },
            result.tags.isEmpty ? nil : "标签：\(result.tags.joined(separator: "、"))"
        ].compactMap { $0 }.joined(separator: "。")
        if !metadataText.isEmpty {
            chunks.append(chunk(task: task, id: "\(task.id)-metadata", title: title, text: metadataText, type: "metadata", meetingDate: meetingDate, createdAt: createdAt, speaker: "会议信息", timestamp: nil, startMs: nil, endMs: nil, scope: scope))
        }
        for (index, segment) in result.transcripts.enumerated() {
            let text = segment.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { continue }
            chunks.append(chunk(task: task, id: "\(task.id)-transcript-\(index)", title: title, text: text, type: "transcript", meetingDate: meetingDate, createdAt: createdAt, speaker: segment.speaker, timestamp: segment.timestamp, startMs: segment.startMs, endMs: segment.endMs, scope: scope))
        }
        if !result.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            chunks.append(chunk(task: task, id: "\(task.id)-summary", title: title, text: result.summary, type: "summary", meetingDate: meetingDate, createdAt: createdAt, speaker: "AI 纪要", timestamp: nil, startMs: nil, endMs: nil, scope: scope))
        }
        for topic in result.topics {
            let text = [topic.title, topic.summary, topic.source].filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.joined(separator: "。")
            guard !text.isEmpty else { continue }
            chunks.append(chunk(task: task, id: "\(task.id)-topic-\(topic.id)", title: title, text: text, type: "topic", meetingDate: meetingDate, createdAt: createdAt, speaker: "AI 议题", timestamp: topic.sourceTimestamp, startMs: nil, endMs: nil, scope: scope))
        }
        for (index, decision) in result.decisions.enumerated() where !decision.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            chunks.append(chunk(task: task, id: "\(task.id)-decision-\(index)", title: title, text: decision, type: "decision", meetingDate: meetingDate, createdAt: createdAt, speaker: "AI 决策", timestamp: nil, startMs: nil, endMs: nil, scope: scope))
        }
        for todo in result.todos {
            let text = [
                todo.title,
                todo.description,
                todo.assignee.map { "负责人：\($0)" } ?? "",
                todo.dueAt.map { "截止时间：\($0)" } ?? "",
                "状态：\(todo.status)",
                todo.source
            ].filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.joined(separator: "。")
            guard !text.isEmpty else { continue }
            chunks.append(chunk(task: task, id: "\(task.id)-todo-\(todo.id)", title: title, text: text, type: "todo", meetingDate: meetingDate, createdAt: createdAt, speaker: "AI 待办", timestamp: todo.sourceTimestamp, startMs: nil, endMs: nil, scope: scope))
        }
        for risk in result.risks {
            let text = [risk.title, risk.description, risk.recommendation, risk.source].filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.joined(separator: "。")
            guard !text.isEmpty else { continue }
            chunks.append(chunk(task: task, id: "\(task.id)-risk-\(risk.id)", title: title, text: text, type: "risk", meetingDate: meetingDate, createdAt: createdAt, speaker: "AI 风险", timestamp: risk.sourceTimestamp, startMs: nil, endMs: nil, scope: scope))
        }
        return chunks
    }

    private static func chunk(
        task: MeetingTask,
        id: String,
        title: String,
        text: String,
        type: String,
        meetingDate: String,
        createdAt: String?,
        speaker: String?,
        timestamp: String?,
        startMs: Int64?,
        endMs: Int64?,
        scope: KnowledgeIndexScope
    ) -> StoredLocalKnowledgeChunk {
        StoredLocalKnowledgeChunk(
            chunkId: id,
            taskId: task.id,
            title: title,
            text: text,
            chunkType: type,
            meetingDate: meetingDate,
            createdAt: createdAt,
            speaker: speaker?.trimmedNilIfEmpty,
            timestamp: timestamp?.trimmedNilIfEmpty,
            startMs: startMs,
            endMs: endMs,
            vector: embed("\(title) \(speaker ?? "") \(text)"),
            knowledgeScope: scope
        )
    }

    private static func embed(_ text: String) -> [Double] {
        let vectorSize = 96
        var vector = Array(repeating: 0.0, count: vectorSize)
        let clean = text.lowercased().filter { !$0.isWhitespace }
        guard !clean.isEmpty else { return vector }
        let scalars = Array(clean.unicodeScalars)
        if scalars.count == 1 {
            let index = stableHash(String(scalars[0])) % vectorSize
            vector[index] += 1
        } else {
            for index in 0..<(scalars.count - 1) {
                let gram = String(String.UnicodeScalarView([scalars[index], scalars[index + 1]]))
                vector[stableHash(gram) % vectorSize] += 1
            }
        }
        for token in lexicalTokens(clean) where token.count >= 2 {
            vector[stableHash(token) % vectorSize] += 1
        }
        let norm = sqrt(vector.reduce(0) { $0 + $1 * $1 })
        return norm <= 0 ? vector : vector.map { $0 / norm }
    }

    private static func cosine(_ left: [Double], _ right: [Double]) -> Double {
        guard left.count == right.count, !left.isEmpty else { return 0 }
        return zip(left, right).reduce(0) { $0 + $1.0 * $1.1 }
    }

    private static func lexicalScore(question: String, chunk: StoredLocalKnowledgeChunk) -> Double {
        let keywords = lexicalTokens(question).filter { $0.count >= 2 }
        guard !keywords.isEmpty else { return 0 }
        let text = "\(chunk.title) \(chunk.speaker ?? "") \(chunk.text)"
        return Double(min(keywords.filter { text.contains($0) }.count, 5)) * 0.08
    }

    private static func lexicalTokens(_ text: String) -> [String] {
        var tokens: [String] = []
        var current = ""
        for scalar in text.unicodeScalars {
            if CharacterSet.alphanumerics.contains(scalar) || (scalar.value >= 0x4E00 && scalar.value <= 0x9FFF) {
                current.append(String(scalar))
            } else if !current.isEmpty {
                tokens.append(current)
                current = ""
            }
        }
        if !current.isEmpty {
            tokens.append(current)
        }
        return tokens
    }

    private static func stableHash(_ text: String) -> Int {
        var hash = 5381
        for scalar in text.unicodeScalars {
            hash = ((hash << 5) &+ hash) &+ Int(scalar.value)
        }
        return abs(hash)
    }

    private static func iso8601String(from millis: Int64) -> String? {
        guard millis > 0 else { return nil }
        return ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }
}

private extension String {
    var removingLastExtension: String {
        let value = NSString(string: self)
        let name = value.deletingPathExtension
        return name.isEmpty ? self : name
    }

    var trimmedNilIfEmpty: String? {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty || clean.lowercased() == "null" ? nil : clean
    }
}

private extension Int64 {
    var meetingDateLabel: String {
        guard self > 0 else { return "本机" }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(self) / 1000))
    }
}
