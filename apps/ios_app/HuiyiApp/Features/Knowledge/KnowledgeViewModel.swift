import Foundation

struct KnowledgeChatMessage: Identifiable, Equatable {
    enum Role: Equatable {
        case user
        case assistant
    }

    let id: String
    var role: Role
    var text: String
    var sources: [KnowledgeSource] = []
    var failed = false
    var retryQuestion: String?

    init(id: String = UUID().uuidString, role: Role, text: String, sources: [KnowledgeSource] = [], failed: Bool = false, retryQuestion: String? = nil) {
        self.id = id
        self.role = role
        self.text = text
        self.sources = sources
        self.failed = failed
        self.retryQuestion = retryQuestion
    }
}

@MainActor
final class KnowledgeViewModel: ObservableObject {
    @Published var question = ""
    @Published private(set) var messages: [KnowledgeChatMessage] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var queryScope: KnowledgeQueryScope = .local
    @Published private(set) var activeQuestion: String?
    @Published private(set) var topics: [KnowledgeTopic] = []

    private var requestId: Int64 = 0

    var canSend: Bool {
        !question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isLoading
    }

    func sendCurrentQuestion(session: AppSession) async -> Bool {
        await send(question, session: session)
    }

    func loadTopics(session: AppSession) {
        topics = session.knowledgeTopics()
    }

    func send(_ text: String, session: AppSession) async -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        guard !isLoading else {
            errorMessage = "知识库正在回答"
            return false
        }
        question = ""
        errorMessage = nil
        messages.append(KnowledgeChatMessage(id: "user-\(Date().timeIntervalSince1970)-\(messages.count)", role: .user, text: trimmed))
        isLoading = true
        activeQuestion = trimmed
        requestId += 1
        let currentRequestId = requestId
        do {
            let response = try await session.askKnowledge(
                question: trimmed,
                scope: queryScope,
                contextTaskIds: lastContextTaskIds(),
                contextMessages: contextMessages()
            )
            guard requestId == currentRequestId else { return false }
            messages.append(
                KnowledgeChatMessage(
                    id: "assistant-\(Date().timeIntervalSince1970)-\(messages.count)",
                    role: .assistant,
                    text: response.answer,
                    sources: sanitizedSources(response.sources)
                )
            )
            try? await session.refreshMembership()
        } catch {
            guard requestId == currentRequestId else { return false }
            let message = userMessage(error, fallback: "知识库问答失败")
            messages.append(
                KnowledgeChatMessage(
                    id: "assistant-error-\(Date().timeIntervalSince1970)-\(messages.count)",
                    role: .assistant,
                    text: message,
                    failed: true
                )
            )
            errorMessage = message
            if isQuotaExhausted(error, message: message) {
                if requestId == currentRequestId {
                    isLoading = false
                    activeQuestion = nil
                }
                return true
            }
        }
        if requestId == currentRequestId {
            isLoading = false
            activeQuestion = nil
            loadTopics(session: session)
        }
        return false
    }

    func cancelAnswer() {
        guard isLoading else { return }
        let retry = activeQuestion?.trimmingCharacters(in: .whitespacesAndNewlines)
        requestId += 1
        isLoading = false
        activeQuestion = nil
        messages.append(
            KnowledgeChatMessage(
                id: "assistant-stopped-\(Date().timeIntervalSince1970)-\(messages.count)",
                role: .assistant,
                text: "已停止回答",
                retryQuestion: retry?.isEmpty == false ? retry : nil
            )
        )
        errorMessage = nil
    }

    func retry(_ question: String) {
        self.question = question
    }

    private func lastContextTaskIds() -> [String] {
        messages
            .reversed()
            .first { $0.role == .assistant && !$0.sources.isEmpty }?
            .sources
            .compactMap { $0.taskId.trimmedNilIfEmpty }
            .reduce(into: [String]()) { output, id in
                if !output.contains(id) {
                    output.append(id)
                }
            } ?? []
    }

    private func contextMessages() -> [KnowledgeContextItem] {
        messages.suffix(6).map { message in
            KnowledgeContextItem(
                role: message.role == .user ? "user" : "assistant",
                text: message.text,
                sources: message.sources
            )
        }
    }

    private func sanitizedSources(_ sources: [KnowledgeSource]) -> [KnowledgeSource] {
        Array(sources.compactMap { source in
            let title = source.title.trimmedNilIfEmpty
            let text = source.text.trimmedNilIfEmpty
            let speaker = source.speaker?.trimmedNilIfEmpty
            let timestamp = source.timestamp?.trimmedNilIfEmpty
            guard title != nil || text != nil || speaker != nil || timestamp != nil else { return nil }
            var next = source
            next.title = title ?? "会议记录"
            next.text = text ?? ""
            next.speaker = speaker
            next.timestamp = timestamp
            return next
        }.prefix(8))
    }

    private func userMessage(_ error: Error, fallback: String) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            if localized.contains("402") {
                return "额度已耗尽，请充值后继续享受权益"
            }
            return localized
        }
        let raw = error.localizedDescription
        return raw.isEmpty ? fallback : raw
    }

    private func isQuotaExhausted(_ error: Error, message: String) -> Bool {
        if case let APIError.httpStatus(status, _) = error, status == 402 {
            return true
        }
        return message.contains("额度") || message.contains("知识库问答次数不足")
    }
}

private extension String {
    var trimmedNilIfEmpty: String? {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty || clean.lowercased() == "null" ? nil : clean
    }
}
