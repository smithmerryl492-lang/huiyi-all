import SwiftUI

struct KnowledgeView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = KnowledgeViewModel()

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                SmartScreenScaffold(
                    title: "知识库",
                    onBack: { router.go(.home) }
                ) {
                    Text("会议、待办、风险一起查")
                        .font(.system(size: 15))
                        .foregroundStyle(HuiyiTheme.brandDark)
                        .frame(maxWidth: .infinity, alignment: .center)

                    RecommendedQuestionsView { question in
                        Task {
                            if await viewModel.send(question, session: session) {
                                router.go(.membership)
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 16) {
                        if viewModel.messages.isEmpty {
                            KnowledgeEmptyContent(
                                topics: viewModel.topics,
                                onTopic: { topic in
                                    router.openMeeting(topic.meetingId)
                                }
                            )
                        } else {
                            ForEach(viewModel.messages) { message in
                                KnowledgeMessageBubble(
                                    message: message,
                                    onSource: { source in
                                        if !source.taskId.isEmpty {
                                            router.openMeeting(source.taskId, knowledgeSource: source)
                                        }
                                    },
                                    onRetry: { question in
                                        viewModel.retry(question)
                                    }
                                )
                            }
                        }

                        if viewModel.isLoading {
                            KnowledgeTypingBubble()
                        }
                    }
                    Color.clear.frame(height: 96)
                }

                VStack(spacing: 10) {
                    if let errorMessage = viewModel.errorMessage {
                        ErrorBanner(message: errorMessage)
                    }
                    HStack(alignment: .center, spacing: 10) {
                        TextField("问会议、待办或风险", text: $viewModel.question, axis: .vertical)
                            .lineLimit(1...4)
                            .font(.system(size: 14))
                            .foregroundStyle(HuiyiTheme.ink)
                            .padding(.horizontal, 16)
                            .frame(minHeight: 52)
                            .background(Color(red: 0.980, green: 0.988, blue: 1.000), in: Capsule())
                            .overlay(Capsule().stroke(HuiyiTheme.line, lineWidth: 1))
                            .disabled(viewModel.isLoading)
                        Button {
                            if viewModel.isLoading {
                                viewModel.cancelAnswer()
                            } else {
                                Task {
                                    if await viewModel.sendCurrentQuestion(session: session) {
                                        router.go(.membership)
                                    }
                                }
                            }
                        } label: {
                            Image(systemName: viewModel.isLoading ? "stop.fill" : "paperplane.fill")
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 52, height: 52)
                                .background(viewModel.isLoading ? HuiyiTheme.danger : HuiyiTheme.brand, in: Circle())
                        }
                        .buttonStyle(.plain)
                        .disabled(!viewModel.isLoading && !viewModel.canSend)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 15)
                .padding(.bottom, 15)
                .background(Color.white.opacity(0.96), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 26, style: .continuous)
                        .stroke(Color.white.opacity(0.82), lineWidth: 1)
                )
                .shadow(color: HuiyiTheme.brandDark.opacity(0.14), radius: 12, x: 0, y: -4)
            }
            .task {
                viewModel.loadTopics(session: session)
            }
        }
    }
}

private struct KnowledgeEmptyContent: View {
    let topics: [KnowledgeTopic]
    let onTopic: (KnowledgeTopic) -> Void

    var body: some View {
        if topics.isEmpty {
            EmptyStateView(
                title: "想查什么会议内容？",
                message: "输入问题后，会从已转写会议和云端知识内容里检索回答。",
                systemImage: "magnifyingglass"
            )
            .padding(.top, 48)
        } else {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .center, spacing: 8) {
                    Text("有什么我能帮你的吗？")
                        .font(.system(size: 21, weight: .bold))
                        .foregroundStyle(HuiyiTheme.brand)
                    Text("—— 不妨先随意看看 ——")
                        .font(.system(size: 13))
                        .foregroundStyle(HuiyiTheme.muted)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 18)
                .padding(.bottom, 8)
                ForEach(topics) { topic in
                    Button {
                        onTopic(topic)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "doc.text.magnifyingglass")
                                .foregroundStyle(HuiyiTheme.accent)
                                .frame(width: 32, height: 32)
                                .background(HuiyiTheme.accent.opacity(0.1), in: Circle())
                            VStack(alignment: .leading, spacing: 4) {
                                Text(topic.title)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(HuiyiTheme.textPrimary)
                                    .lineLimit(1)
                                Text(topic.subtitle)
                                    .font(.caption)
                                    .foregroundStyle(HuiyiTheme.textSecondary)
                                    .lineLimit(2)
                            }
                            Spacer(minLength: 0)
                            Text("查看")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(HuiyiTheme.brand)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(Color.white, in: Capsule())
                                .overlay(Capsule().stroke(HuiyiTheme.line, lineWidth: 1))
                        }
                        .padding(12)
                        .background(Color.white.opacity(0.96), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(HuiyiTheme.line, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 20)
        }
    }
}

private struct KnowledgeMessageBubble: View {
    let message: KnowledgeChatMessage
    let onSource: (KnowledgeSource) -> Void
    let onRetry: (String) -> Void
    @State private var sourcesExpanded = false

    var body: some View {
        VStack(alignment: message.role == .user ? .trailing : .leading, spacing: 8) {
            Text(message.text)
                .font(.body)
                .foregroundStyle(textColor)
                .fixedSize(horizontal: false, vertical: true)
                .padding(12)
                .background(backgroundColor, in: RoundedRectangle(cornerRadius: 8))

            if let retryQuestion = message.retryQuestion?.trimmingCharacters(in: .whitespacesAndNewlines), !retryQuestion.isEmpty {
                Button {
                    onRetry(retryQuestion)
                } label: {
                    Label("重新编辑", systemImage: "pencil")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(HuiyiTheme.accent.opacity(0.12), in: Capsule())
                }
                .buttonStyle(.plain)
                .foregroundStyle(HuiyiTheme.accent)
            }

            if showSources {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(visibleSources.enumerated()), id: \.offset) { _, source in
                        Button {
                            onSource(source)
                        } label: {
                            Label(source.sourceLabel, systemImage: "doc.text")
                                .font(.caption)
                                .lineLimit(1)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 7)
                                .background(HuiyiTheme.accent.opacity(0.1), in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(HuiyiTheme.accent)
                    }
                    if message.sources.count > 2 {
                        Button(sourcesExpanded ? "收起来源" : sourceToggleTitle) {
                            sourcesExpanded.toggle()
                        }
                        .font(.caption.weight(.semibold))
                        .buttonStyle(.bordered)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .frame(maxWidth: .infinity, alignment: message.role == .user ? .trailing : .leading)
    }

    private var backgroundColor: Color {
        if message.role == .user {
            return HuiyiTheme.accent
        }
        return message.failed ? Color.red.opacity(0.08) : HuiyiTheme.surface
    }

    private var textColor: Color {
        if message.role == .user {
            return .white
        }
        return message.failed ? .red : HuiyiTheme.textPrimary
    }

    private var showSources: Bool {
        guard !message.failed, !message.sources.isEmpty else { return false }
        return message.text.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "。")) != "未检索到相关内容"
    }

    private var visibleSources: [KnowledgeSource] {
        Array(message.sources.prefix(sourcesExpanded ? 10 : 2))
    }

    private var sourceToggleTitle: String {
        message.sources.count > 10 ? "展开前 10 个来源" : "展开 \(message.sources.count) 个来源"
    }
}

private struct RecommendedQuestionsView: View {
    let onAsk: (String) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                RecommendedQuestionButton(title: "本周待办", question: "本周我有哪些待办？", onAsk: onAsk)
                RecommendedQuestionButton(title: "最近风险", question: "最近有哪些风险？", onAsk: onAsk)
                RecommendedQuestionButton(title: "最近决策", question: "最近会议决定了什么？", onAsk: onAsk)
            }
            .padding(.vertical, 2)
        }
    }
}

private struct RecommendedQuestionButton: View {
    let title: String
    let question: String
    let onAsk: (String) -> Void

    var body: some View {
        Button {
            onAsk(question)
        } label: {
            Text(title)
                .font(.caption)
                .foregroundStyle(HuiyiTheme.textSecondary)
                .lineLimit(1)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(HuiyiTheme.surface, in: Capsule())
                .overlay(Capsule().stroke(Color(.separator).opacity(0.5)))
        }
        .buttonStyle(.plain)
    }
}

private struct KnowledgeTypingBubble: View {
    var body: some View {
        HStack {
            Text("正在回答…")
                .font(.subheadline)
                .foregroundStyle(HuiyiTheme.textSecondary)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(HuiyiTheme.surface, in: RoundedRectangle(cornerRadius: 8))
            Spacer()
        }
    }
}

private extension KnowledgeSource {
    var sourceLabel: String {
        [title, meetingDate, speaker, timestamp]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && $0.lowercased() != "null" }
            .joined(separator: " · ")
    }
}
