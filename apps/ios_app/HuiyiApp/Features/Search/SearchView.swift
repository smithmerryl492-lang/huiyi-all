import SwiftUI

struct SearchView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = SearchViewModel()
    @State private var query = ""

    var body: some View {
        NavigationStack {
            SmartScreenScaffold(title: "搜索", onBack: { router.go(.home) }) {
                searchBox

                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }

                SmartInfoBlock(title: "搜索结果") {
                    if cleanQuery.isEmpty {
                        Text("输入关键词搜索本机会议内容")
                            .font(.system(size: 14))
                            .foregroundStyle(HuiyiTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    } else if results.isEmpty {
                        Text("没有找到匹配内容，换个关键词试试")
                            .font(.system(size: 14))
                            .foregroundStyle(HuiyiTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(results.prefix(20).enumerated()), id: \.element.id) { index, result in
                                SearchResultRow(result: result) {
                                    commitSearch()
                                    if let source = result.source {
                                        router.openMeeting(result.remoteTaskId ?? result.taskId, knowledgeSource: source)
                                    } else if let sourceTodo = result.sourceTodo {
                                        router.openMeeting(result.remoteTaskId ?? result.taskId, sourceTodo: sourceTodo)
                                    } else {
                                        router.openMeeting(result.remoteTaskId ?? result.taskId)
                                    }
                                }
                                if index < min(results.count, 20) - 1 {
                                    Divider().padding(.leading, 52)
                                }
                            }
                        }
                    }
                }

                if !viewModel.recentSearches.isEmpty {
                    SmartInfoBlock(title: "最近搜索") {
                        VStack(spacing: 0) {
                            ForEach(Array(viewModel.recentSearches.enumerated()), id: \.element) { index, keyword in
                                SmartRowButton(systemImage: "magnifyingglass", title: keyword, subtitle: "再次搜索") {
                                    query = keyword
                                    commitSearch(keyword)
                                }
                                if index < viewModel.recentSearches.count - 1 {
                                    Divider().padding(.leading, 52)
                                }
                            }
                        }
                    }
                }
            }
            .task {
                if viewModel.items.isEmpty {
                    await viewModel.load(session: session)
                }
                viewModel.loadRecentSearches(session: session)
            }
            .refreshable { await viewModel.load(session: session) }
        }
    }

    private var cleanQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var searchBox: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(HuiyiTheme.muted)
            TextField("搜索会议、纪要、待办、转写原文", text: $query)
                .font(.system(size: 15))
                .foregroundStyle(HuiyiTheme.ink)
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
                .onSubmit { commitSearch() }
            Image(systemName: "doc.text.fill")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(HuiyiTheme.brand)
        }
        .padding(.horizontal, 14)
        .frame(height: 56)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(HuiyiTheme.line, lineWidth: 1))
        .shadow(color: HuiyiTheme.brandDark.opacity(0.06), radius: 8, x: 0, y: 2)
    }

    private var results: [MeetingSearchResult] {
        viewModel.search(query)
    }

    private func commitSearch(_ keyword: String? = nil) {
        viewModel.recordSearch(keyword ?? query, session: session)
    }
}

private struct SearchResultRow: View {
    let result: MeetingSearchResult
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(HuiyiTheme.brand)
                    .frame(width: 40, height: 40)
                    .background(HuiyiTheme.brandSoft, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 5) {
                    Text(result.title)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                        .lineLimit(2)
                    Text(result.snippet)
                        .font(.system(size: 13))
                        .foregroundStyle(HuiyiTheme.muted)
                        .lineLimit(3)
                    Text(result.reason)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(HuiyiTheme.brand)
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 10)
        }
        .buttonStyle(.plain)
    }
}

private struct LegacySearchView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = SearchViewModel()
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }

                if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Section("搜索范围") {
                        Label("会议标题、参会人、标签、摘要、议题、决策、风险、待办、转写原文", systemImage: "magnifyingglass")
                            .font(.subheadline)
                            .foregroundStyle(HuiyiTheme.textSecondary)
                    }
                    if !viewModel.recentSearches.isEmpty {
                        Section("最近搜索") {
                            ForEach(viewModel.recentSearches, id: \.self) { keyword in
                                Button {
                                    query = keyword
                                    viewModel.recordSearch(keyword, session: session)
                                } label: {
                                    HStack {
                                        Label(keyword, systemImage: "clock.arrow.circlepath")
                                        Spacer()
                                        Text("再次搜索")
                                            .font(.caption)
                                            .foregroundStyle(HuiyiTheme.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                } else if results.isEmpty {
                    EmptyStateView(title: "未找到结果", message: "换个关键词试试。", systemImage: "magnifyingglass")
                        .listRowInsets(EdgeInsets())
                } else {
                    Section("结果") {
                        ForEach(results) { result in
                            Button {
                                viewModel.recordSearch(query, session: session)
                                if let source = result.source {
                                    router.openMeeting(result.remoteTaskId ?? result.taskId, knowledgeSource: source)
                                } else if let sourceTodo = result.sourceTodo {
                                    router.openMeeting(result.remoteTaskId ?? result.taskId, sourceTodo: sourceTodo)
                                } else {
                                    router.openMeeting(result.remoteTaskId ?? result.taskId)
                                }
                            } label: {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(result.title)
                                        .font(.headline)
                                        .lineLimit(2)
                                    Text(result.snippet)
                                        .font(.subheadline)
                                        .foregroundStyle(HuiyiTheme.textSecondary)
                                        .lineLimit(3)
                                    Text(result.reason)
                                        .font(.caption)
                                        .foregroundStyle(HuiyiTheme.accent)
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "搜索会议内容")
            .onSubmit(of: .search) {
                viewModel.recordSearch(query, session: session)
            }
            .navigationTitle("搜索")
            .toolbar {
                Button("返回") { router.go(.home) }
            }
            .task {
                if viewModel.items.isEmpty {
                    await viewModel.load(session: session)
                }
                viewModel.loadRecentSearches(session: session)
            }
            .refreshable { await viewModel.load(session: session) }
        }
    }

    private var results: [MeetingSearchResult] {
        viewModel.search(query)
    }
}

@MainActor
final class SearchViewModel: ObservableObject {
    @Published private(set) var items: [CloudTaskItem] = []
    @Published private(set) var localItems: [MeetingDetail] = []
    @Published private(set) var recentSearches: [String] = []
    @Published var errorMessage: String?
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore = SettingsStore()) {
        self.settingsStore = settingsStore
    }

    func load(session: AppSession) async {
        errorMessage = nil
        do {
            items = try await session.loadCloudBootstrap().tasks
            localItems = session.localMeetingDetails()
        } catch {
            localItems = session.localMeetingDetails()
            if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
                errorMessage = localized
            } else {
                errorMessage = error.localizedDescription
            }
        }
    }

    func loadRecentSearches(session: AppSession) {
        guard let user = session.currentUser else { return }
        recentSearches = settingsStore.recentSearchKeywords(userId: user.userId)
    }

    func recordSearch(_ keyword: String, session: AppSession) {
        let clean = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, let user = session.currentUser else { return }
        recentSearches = ([clean] + recentSearches.filter { !$0.localizedCaseInsensitiveContainsExactly(clean) }).prefix(10).map { $0 }
        settingsStore.saveRecentSearchKeywords(recentSearches, userId: user.userId)
    }

    func search(_ query: String) -> [MeetingSearchResult] {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return [] }
        let cloudMatches = items.compactMap { item in
            let result = item.result
            if let transcriptMatch = result?.transcripts.enumerated().first(where: { _, segment in
                segment.text.localizedCaseInsensitiveContains(clean) ||
                    segment.speaker.localizedCaseInsensitiveContains(clean) ||
                    segment.timestamp.localizedCaseInsensitiveContains(clean)
            }) {
                let segment = transcriptMatch.element
                let source = KnowledgeSource(
                    chunkId: "\(item.task.id)-search-transcript-\(transcriptMatch.offset)",
                    taskId: item.task.id,
                    title: item.task.title,
                    text: segment.text,
                    chunkType: "transcript",
                    meetingDate: item.task.createdAt,
                    speaker: segment.speaker,
                    timestamp: segment.timestamp,
                    startMs: segment.startMs,
                    endMs: segment.endMs,
                    score: 1,
                    scope: item.task.knowledgeScope.rawValue
                )
                return MeetingSearchResult(
                    taskId: item.task.id,
                    remoteTaskId: item.task.id,
                    title: item.task.title,
                    snippet: SearchViewModel.snippet(from: "\(segment.speaker) \(segment.text)", query: clean),
                    reason: "命中转写原文",
                    source: source,
                    sourceTodo: nil
                )
            }
            if let todo = result?.todos.first(where: { todo in
                [
                    todo.title,
                    todo.description,
                    todo.assignee ?? "",
                    todo.source,
                    todo.sourceTimestamp ?? ""
                ].joined(separator: " ").localizedCaseInsensitiveContains(clean)
            }) {
                return MeetingSearchResult(
                    taskId: item.task.id,
                    remoteTaskId: item.task.id,
                    title: item.task.title,
                    snippet: SearchViewModel.snippet(from: "\(todo.title) \(todo.description)", query: clean),
                    reason: "命中待办",
                    source: nil,
                    sourceTodo: todo
                )
            }
            let haystacks: [(String, String)] = [
                ("标题", item.task.title),
                ("参会人", result?.participants ?? ""),
                ("摘要", result?.summary ?? ""),
                ("标签", result?.tags.joined(separator: " ") ?? ""),
                ("议题", result?.topics.map { "\($0.title) \($0.summary)" }.joined(separator: " ") ?? ""),
                ("决策", result?.decisions.joined(separator: " ") ?? ""),
                ("风险", result?.risks.map { "\($0.title) \($0.description) \($0.recommendation)" }.joined(separator: " ") ?? ""),
                ("待办", result?.todos.map { "\($0.title) \($0.description) \($0.assignee ?? "")" }.joined(separator: " ") ?? ""),
                ("原文", result?.transcripts.map { "\($0.speaker) \($0.text)" }.joined(separator: " ") ?? "")
            ]
            guard let match = haystacks.first(where: { $0.1.localizedCaseInsensitiveContains(clean) }) else {
                return nil
            }
            return MeetingSearchResult(
                taskId: item.task.id,
                remoteTaskId: item.task.id,
                title: item.task.title,
                snippet: SearchViewModel.snippet(from: match.1, query: clean),
                reason: "命中\(match.0)",
                source: nil,
                sourceTodo: nil
            )
        }
        let cloudIds = Set(items.map(\.task.id))
        let localMatches = localItems
            .filter { !cloudIds.contains($0.task.remoteTaskId ?? $0.task.id) }
            .compactMap { searchLocal($0, query: clean) }
        return cloudMatches + localMatches
    }

    private func searchLocal(_ item: MeetingDetail, query clean: String) -> MeetingSearchResult? {
        guard let result = item.result else { return nil }
        if let transcriptMatch = result.transcripts.enumerated().first(where: { _, segment in
            segment.text.localizedCaseInsensitiveContains(clean) ||
                segment.speaker.localizedCaseInsensitiveContains(clean) ||
                segment.timestamp.localizedCaseInsensitiveContains(clean)
        }) {
            let segment = transcriptMatch.element
            let source = KnowledgeSource(
                chunkId: "\(item.task.id)-search-transcript-\(transcriptMatch.offset)",
                taskId: item.task.remoteTaskId ?? item.task.id,
                title: item.task.title,
                text: segment.text,
                chunkType: "transcript",
                meetingDate: "",
                speaker: segment.speaker,
                timestamp: segment.timestamp,
                startMs: segment.startMs,
                endMs: segment.endMs,
                score: 1,
                scope: item.task.knowledgeScope.rawValue
            )
            return MeetingSearchResult(
                taskId: item.task.id,
                remoteTaskId: item.task.remoteTaskId,
                title: item.task.title,
                snippet: SearchViewModel.snippet(from: "\(segment.speaker) \(segment.text)", query: clean),
                reason: "命中转写原文",
                source: source,
                sourceTodo: nil
            )
        }
        if let todo = result.todos.first(where: { todo in
            [
                todo.title,
                todo.description,
                todo.assignee ?? "",
                todo.source,
                todo.sourceTimestamp ?? ""
            ].joined(separator: " ").localizedCaseInsensitiveContains(clean)
        }) {
            return MeetingSearchResult(
                taskId: item.task.id,
                remoteTaskId: item.task.remoteTaskId,
                title: item.task.title,
                snippet: SearchViewModel.snippet(from: "\(todo.title) \(todo.description)", query: clean),
                reason: "命中待办",
                source: nil,
                sourceTodo: todo
            )
        }
        let haystacks: [(String, String)] = [
            ("标题", item.task.title),
            ("参会人", result.participants ?? ""),
            ("摘要", result.summary),
            ("标签", result.tags.joined(separator: " ")),
            ("议题", result.topics.map { "\($0.title) \($0.summary)" }.joined(separator: " ")),
            ("决策", result.decisions.joined(separator: " ")),
            ("风险", result.risks.map { "\($0.title) \($0.description) \($0.recommendation)" }.joined(separator: " ")),
            ("待办", result.todos.map { "\($0.title) \($0.description) \($0.assignee ?? "")" }.joined(separator: " ")),
            ("原文", result.transcripts.map { "\($0.speaker) \($0.text)" }.joined(separator: " "))
        ]
        guard let match = haystacks.first(where: { $0.1.localizedCaseInsensitiveContains(clean) }) else {
            return nil
        }
        return MeetingSearchResult(
            taskId: item.task.id,
            remoteTaskId: item.task.remoteTaskId,
            title: item.task.title,
            snippet: SearchViewModel.snippet(from: match.1, query: clean),
            reason: "命中\(match.0)",
            source: nil,
            sourceTodo: nil
        )
    }

    private static func snippet(from text: String, query: String) -> String {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count > 90 else { return clean }
        if let range = clean.range(of: query, options: .caseInsensitive) {
            let lower = clean.index(range.lowerBound, offsetBy: -30, limitedBy: clean.startIndex) ?? clean.startIndex
            let upper = clean.index(range.upperBound, offsetBy: 60, limitedBy: clean.endIndex) ?? clean.endIndex
            return String(clean[lower..<upper])
        }
        return String(clean.prefix(90))
    }
}

private extension String {
    func localizedCaseInsensitiveContainsExactly(_ other: String) -> Bool {
        compare(other, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
    }
}

struct MeetingSearchResult: Identifiable, Equatable {
    var id: String { taskId }
    let taskId: String
    let remoteTaskId: String?
    let title: String
    let snippet: String
    let reason: String
    let source: KnowledgeSource?
    let sourceTodo: TodoItem?
}
