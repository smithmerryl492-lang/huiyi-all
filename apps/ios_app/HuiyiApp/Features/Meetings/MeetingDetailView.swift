import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct MeetingDetailView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @EnvironmentObject private var toastCenter: ToastCenter
    @StateObject private var viewModel = MeetingDetailViewModel()
    @State private var showingDeleteConfirmation = false
    @State private var showingTitleEditor = false
    @State private var showingInfoEditor = false
    @State private var showingSummaryEditor = false
    @State private var showingTodoCreator = false
    @State private var showingSpeakerEditor = false
    @State private var showingExporter = false
    @State private var showingRegenerateConfirmation = false
    @State private var selectedSourceContext: TranscriptSegmentContext?
    @State private var selectedSpeakerContext: TranscriptSegmentContext?
    @State private var selectedTodoContext: TodoContextItem?
    @State private var titleDraft = ""
    @State private var showingFileExporter = false
    @State private var exportDocument = MeetingTextDocument(text: "")

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.detail == nil {
                    ProgressView("正在加载会议")
                } else if let result = viewModel.result {
                    content(result: result)
                } else if let errorMessage = viewModel.errorMessage {
                    VStack(spacing: 16) {
                        ErrorBanner(message: errorMessage)
                        SecondaryActionButton(title: "重新加载", systemImage: "arrow.clockwise") {
                            Task { await viewModel.load(taskId: router.selectedMeetingId, session: session) }
                        }
                    }
                    .padding(16)
                } else {
                    EmptyStateView(title: "暂无会议结果", message: "会议完成处理后，纪要、待办和转写原文会显示在这里。", systemImage: "doc.text")
                }
            }
            .navigationTitle(viewModel.taskTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("返回") { router.backFromDetail() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            Task { await viewModel.load(taskId: router.selectedMeetingId, session: session) }
                        } label: {
                            Label("刷新", systemImage: "arrow.clockwise")
                        }
                        if let result = viewModel.result {
                            ShareLink(item: MeetingMarkdownExporter.markdown(title: viewModel.taskTitle, result: result, includeTranscript: true)) {
                                Label("分享 Markdown", systemImage: "square.and.arrow.up")
                            }
                            Button {
                                showingExporter = true
                            } label: {
                                Label("导出会议纪要", systemImage: "tray.and.arrow.down")
                            }
                            .disabled(viewModel.isExporting)
                            if viewModel.transcriptNeedsRegeneration || viewModel.isRegenerating {
                                Button {
                                    showingRegenerateConfirmation = true
                                } label: {
                                    Label(viewModel.isRegenerating ? "正在重新生成" : "重新生成纪要", systemImage: "arrow.triangle.2.circlepath")
                                }
                                .disabled(viewModel.isRegenerating || result.transcripts.isEmpty)
                            }
                            Button {
                                viewModel.selectedTab = .todos
                                showingTodoCreator = true
                            } label: {
                                Label("补充待办", systemImage: "plus.circle")
                            }
                            Button {
                                viewModel.selectedTab = .transcript
                                showingSpeakerEditor = true
                            } label: {
                                Label("编辑说话人", systemImage: "person.2")
                            }
                            .disabled(viewModel.speakerIdentities.isEmpty)
                        }
                        Button {
                            titleDraft = viewModel.taskTitle
                            showingTitleEditor = true
                        } label: {
                            Label("编辑标题", systemImage: "pencil")
                        }
                        Button {
                            showingInfoEditor = true
                        } label: {
                            Label("编辑会议信息", systemImage: "person.text.rectangle")
                        }
                        .disabled(viewModel.result == nil)
                        Button {
                            showingSummaryEditor = true
                        } label: {
                            Label("编辑纪要", systemImage: "text.alignleft")
                        }
                        .disabled(viewModel.result == nil)
                        Button(role: .destructive) {
                            showingDeleteConfirmation = true
                        } label: {
                            Label("删除会议", systemImage: "trash")
                        }
                        .disabled(viewModel.detail == nil || viewModel.isDeleting)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .confirmationDialog("删除这条会议记录？", isPresented: $showingDeleteConfirmation, titleVisibility: .visible) {
                Button("删除", role: .destructive) {
                    Task {
                        if await viewModel.deleteCurrentTask(session: session) {
                            router.backFromDetail()
                        }
                    }
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("删除会移除会议任务、结果和知识库索引。")
            }
            .confirmationDialog("更新纪要？", isPresented: $showingRegenerateConfirmation, titleVisibility: .visible) {
                Button("开始更新") {
                    Task { await viewModel.regenerateMinutes(session: session) }
                }
                .disabled(viewModel.isRegenerating)
                Button("取消", role: .cancel) {}
            } message: {
                Text("将根据当前会议内容重新生成摘要、议题、决策、风险和待办。已完成的待办会保留，重复的新待办不会新增。处理可能需要一些时间。")
            }
            .alert("编辑会议标题", isPresented: $showingTitleEditor) {
                TextField("会议标题", text: $titleDraft)
                Button("保存") {
                    Task { await viewModel.updateTitle(titleDraft, session: session) }
                }
                Button("取消", role: .cancel) {}
            }
            .sheet(isPresented: $showingInfoEditor) {
                MeetingInfoEditorView(
                    title: viewModel.taskTitle,
                    participants: viewModel.result?.participants ?? "",
                    tagsText: viewModel.result?.tags.joined(separator: "，") ?? "",
                    isPrivate: viewModel.detail?.task.isPrivate ?? false,
                    knowledgeScope: viewModel.detail?.task.knowledgeScope ?? .local
                ) { title, participants, tags, isPrivate, knowledgeScope in
                    Task {
                        await viewModel.updateMeetingInfo(
                            title: title,
                            participants: participants,
                            tags: tags,
                            isPrivate: isPrivate,
                            knowledgeScope: knowledgeScope,
                            session: session
                        )
                    }
                }
            }
            .sheet(isPresented: $showingSummaryEditor) {
                SummaryEditorView(summary: viewModel.result?.summary ?? "") { summary in
                    Task { await viewModel.updateSummary(summary, session: session) }
                }
            }
            .sheet(isPresented: $showingTodoCreator) {
                TodoCreateView { draft in
                    Task {
                        if await viewModel.createManualTodo(draft, session: session) {
                            showingTodoCreator = false
                        }
                    }
                }
            }
            .sheet(item: $selectedTodoContext) { item in
                TodoDetailEditorView(item: item) { draft in
                    Task {
                        if await viewModel.updateTodo(item.todo, draft: draft, session: session) {
                            selectedTodoContext = nil
                        }
                    }
                } onDelete: {
                    Task {
                        if await viewModel.deleteTodo(item.todo, session: session) {
                            selectedTodoContext = nil
                        }
                    }
                } onSource: {
                    if let context = viewModel.transcriptContext(for: item.todo) {
                        selectedSourceContext = context
                    }
                    selectedTodoContext = nil
                }
            }
            .sheet(isPresented: $showingSpeakerEditor) {
                SpeakerManagementView(speakers: viewModel.speakerIdentities) { speaker, target, saveVoiceprint in
                    Task {
                        if await viewModel.renameSpeaker(speaker, target: target, saveVoiceprint: saveVoiceprint, session: session) {
                            showingSpeakerEditor = false
                        }
                    }
                }
            }
            .sheet(isPresented: $showingExporter) {
                MeetingExportView(
                    isExporting: viewModel.isExporting,
                    statusText: viewModel.exportStatusMessage
                ) { includeTranscript in
                    Task {
                        if await viewModel.exportMarkdown(includeTranscript: includeTranscript, session: session) {
                            if let markdown = viewModel.exportedMarkdown {
                                exportDocument = MeetingTextDocument(text: markdown)
                                viewModel.exportedMarkdown = nil
                                showingFileExporter = true
                            }
                            showingExporter = false
                        }
                    }
                }
            }
            .sheet(item: $selectedSourceContext) { context in
                SourceDetailView(
                    context: context,
                    isPlaying: viewModel.activeAudioSegmentId == context.id,
                    playbackMessage: viewModel.audioStatusMessage
                ) {
                    Task { await viewModel.playAudioSegment(context, session: session) }
                } onSpeaker: {
                    selectedSourceContext = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        selectedSpeakerContext = context
                    }
                } onSaveCorrection: { text in
                    Task {
                        if await viewModel.updateTranscriptSegmentText(index: context.index, text: text, session: session) {
                            selectedSourceContext = nil
                        }
                    }
                }
            }
            .sheet(item: $selectedSpeakerContext) { context in
                SegmentSpeakerEditorView(
                    context: context,
                    speakers: viewModel.speakerIdentities
                ) { name, speakerId in
                    Task {
                        if await viewModel.updateSegmentSpeaker(index: context.index, target: name, selectedSpeakerId: speakerId, session: session) {
                            selectedSpeakerContext = nil
                            selectedSourceContext = nil
                        }
                    }
                }
            }
            .fileExporter(
                isPresented: $showingFileExporter,
                document: exportDocument,
                contentType: .plainText,
                defaultFilename: "\(viewModel.taskTitle)-会议纪要.txt"
            ) { result in
                if case let .failure(error) = result {
                    toastCenter.show(error.localizedDescription)
                }
            }
            .task {
                if viewModel.detail == nil {
                    await viewModel.load(taskId: router.selectedMeetingId, session: session)
                }
                openPendingSourceIfNeeded()
            }
        }
    }

    private func openPendingSourceIfNeeded() {
        if let todo = router.pendingSourceTodo {
            guard let context = viewModel.transcriptContext(for: todo) else {
                if viewModel.result != nil {
                    router.pendingSourceTodo = nil
                }
                return
            }
            router.pendingSourceTodo = nil
            viewModel.selectedTab = .transcript
            selectedSourceContext = context
            return
        }
        if let source = router.pendingKnowledgeSource {
            guard let context = viewModel.transcriptContext(for: source) else {
                if viewModel.result != nil {
                    router.pendingKnowledgeSource = nil
                }
                return
            }
            router.pendingKnowledgeSource = nil
            viewModel.selectedTab = .transcript
            selectedSourceContext = context
        }
    }

    private func content(result: MeetingProcessingResult) -> some View {
        VStack(spacing: 0) {
            Picker("内容", selection: $viewModel.selectedTab) {
                ForEach(MeetingDetailTab.allCases) { tab in
                    Text(tab.title).tag(tab)
                }
            }
            .pickerStyle(.segmented)
            .padding(16)

            ScrollView {
                switch viewModel.selectedTab {
                case .info:
                    MeetingInfoContent(detail: viewModel.detail) {
                        Task { await viewModel.confirmMeeting(session: session) }
                    }
                case .summary:
                    MeetingSummaryContent(result: result)
                case .topics:
                    TopicContent(
                        result: result,
                        sourceContext: { topic in
                            viewModel.transcriptContext(
                                timestamp: topic.sourceTimestamp,
                                text: [topic.title, topic.summary, topic.source].joined(separator: " ")
                            )
                        },
                        onSource: { selectedSourceContext = $0 }
                    )
                case .decisions:
                    DecisionContent(
                        result: result,
                        sourceContext: { decision in
                            viewModel.transcriptContext(text: decision)
                        },
                        onSource: { selectedSourceContext = $0 }
                    )
                case .todos:
                    MeetingTodosContent(result: result, savingTodoId: viewModel.savingTodoId, onAdd: {
                        showingTodoCreator = true
                    }, onStart: { todo in
                        Task { await viewModel.startTodo(todo, session: session) }
                    }, onDetail: { todo in
                        selectedTodoContext = viewModel.todoContext(for: todo)
                    }, onSource: { todo in
                        if let context = viewModel.transcriptContext(for: todo) {
                            selectedSourceContext = context
                        }
                    }) { todo in
                        Task { await viewModel.toggleTodo(todo, session: session) }
                    }
                case .risks:
                    RiskContent(
                        result: result,
                        sourceContext: { risk in
                            viewModel.transcriptContext(
                                timestamp: risk.sourceTimestamp,
                                text: [risk.title, risk.description, risk.recommendation, risk.source].joined(separator: " ")
                            )
                        },
                        onSource: { selectedSourceContext = $0 }
                    )
                case .transcript:
                    TranscriptContent(
                        contexts: viewModel.filteredTranscriptContexts,
                        isFiltering: !viewModel.transcriptQuery.isEmpty,
                        onSource: { selectedSourceContext = $0 },
                        onSpeaker: { selectedSpeakerContext = $0 },
                        onCopy: copyTranscriptSegment
                    )
                }
            }
        }
        .huiyiScreenBackground()
        .searchable(text: $viewModel.transcriptQuery, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "搜索原文")
    }

    private func copyTranscriptSegment(_ context: TranscriptSegmentContext) {
        UIPasteboard.general.string = context.segment.text
        toastCenter.show("已复制片段")
    }
}

private struct MeetingInfoEditorView: View {
    let initialTitle: String
    let initialParticipants: String
    let initialTagsText: String
    let onSave: (String, String, [String], Bool, KnowledgeIndexScope) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var participants: String
    @State private var tagsText: String
    @State private var isPrivate: Bool
    @State private var knowledgeScope: KnowledgeIndexScope

    init(
        title: String,
        participants: String,
        tagsText: String,
        isPrivate: Bool,
        knowledgeScope: KnowledgeIndexScope,
        onSave: @escaping (String, String, [String], Bool, KnowledgeIndexScope) -> Void
    ) {
        initialTitle = title
        initialParticipants = participants
        initialTagsText = tagsText
        self.onSave = onSave
        _title = State(initialValue: title)
        _participants = State(initialValue: participants)
        _tagsText = State(initialValue: tagsText)
        _isPrivate = State(initialValue: isPrivate)
        _knowledgeScope = State(initialValue: knowledgeScope)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("会议信息") {
                    TextField("标题", text: $title)
                    TextField("参会人", text: $participants)
                    TextField("标签，用逗号分隔", text: $tagsText)
                }

                Section("知识库与隐私") {
                    Toggle("私密会议", isOn: $isPrivate)
                    Picker("知识库范围", selection: $knowledgeScope) {
                        Text(KnowledgeIndexScope.local.displayName).tag(KnowledgeIndexScope.local)
                        Text(KnowledgeIndexScope.cloud.displayName).tag(KnowledgeIndexScope.cloud)
                        Text(KnowledgeIndexScope.excluded.displayName).tag(KnowledgeIndexScope.excluded)
                    }
                    if isPrivate {
                        Text("私密会议不会进入本机知识库索引；云端同步时也会保留私密标记。")
                            .font(.footnote)
                            .foregroundStyle(HuiyiTheme.textSecondary)
                    } else {
                        Text(knowledgeScope.helpText)
                            .font(.footnote)
                            .foregroundStyle(HuiyiTheme.textSecondary)
                    }
                }
            }
            .navigationTitle("编辑会议信息")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") {
                        onSave(title, participants, parsedTags, isPrivate, knowledgeScope)
                        dismiss()
                    }
                    .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private var parsedTags: [String] {
        tagsText
            .split { character in
                character == "," || character == "，" || character == "、"
            }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }
}

private extension KnowledgeIndexScope {
    var displayName: String {
        switch self {
        case .local: return "本机知识库"
        case .cloud: return "云端知识库"
        case .all: return "全部知识库"
        case .excluded: return "不纳入知识库"
        }
    }

    var helpText: String {
        switch self {
        case .local:
            return "仅参与本机知识库检索，适合暂未上传或只在本设备使用的会议。"
        case .cloud:
            return "参与云端知识库检索，适合多端同步后的会议。"
        case .all:
            return "同时按本机和云端范围参与检索。"
        case .excluded:
            return "该会议不会进入知识库问答检索。"
        }
    }
}

private struct SummaryEditorView: View {
    let initialSummary: String
    let onSave: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var summary: String

    init(summary: String, onSave: @escaping (String) -> Void) {
        initialSummary = summary
        self.onSave = onSave
        _summary = State(initialValue: summary)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("纪要") {
                    TextField("纪要内容", text: $summary, axis: .vertical)
                        .lineLimit(8...20)
                }
            }
            .navigationTitle("编辑纪要")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") {
                        onSave(summary)
                        dismiss()
                    }
                    .disabled(summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

private struct MeetingExportView: View {
    let isExporting: Bool
    let statusText: String?
    let onExport: (Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var includeTranscript = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("保存为 TXT 文件，可选择分享或存储位置。")
                        .font(.footnote)
                        .foregroundStyle(HuiyiTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Section("导出内容") {
                    ExportScopeRow(
                        title: "仅会议纪要",
                        subtitle: "摘要、议题、决策、待办和风险",
                        selected: !includeTranscript
                    ) {
                        includeTranscript = false
                    }
                    .disabled(isExporting)

                    ExportScopeRow(
                        title: "纪要和原文",
                        subtitle: "在纪要后附上完整转写文本",
                        selected: includeTranscript
                    ) {
                        includeTranscript = true
                    }
                    .disabled(isExporting)
                }

                if let statusText, !statusText.isEmpty {
                    Section {
                        Label(statusText, systemImage: "hourglass")
                            .font(.footnote)
                            .foregroundStyle(HuiyiTheme.warning)
                    }
                }

                Section {
                    PrimaryActionButton(title: isExporting ? "准备中" : "选择位置并导出", systemImage: "tray.and.arrow.down", isLoading: isExporting) {
                        onExport(includeTranscript)
                    }
                }
            }
            .navigationTitle("导出会议纪要")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                        .disabled(isExporting)
                }
            }
        }
    }
}

private struct MeetingTextDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.plainText] }

    var text: String

    init(text: String) {
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            text = ""
            return
        }
        text = String(decoding: data, as: UTF8.self)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

private struct ExportScopeRow: View {
    let title: String
    let subtitle: String
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected ? HuiyiTheme.accent : HuiyiTheme.textSecondary)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(HuiyiTheme.textPrimary)
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(HuiyiTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }
}

private struct MeetingInfoContent: View {
    let detail: RemoteTaskDetail?
    let onConfirm: () -> Void

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 16) {
            if let detail {
                ConfirmationStatusView(confirmed: detail.task.confirmed, onConfirm: onConfirm)
                DetailSection(title: "会议", systemImage: "doc.text") {
                    LabeledContent("标题", value: detail.task.title)
                    LabeledContent("来源", value: detail.task.source.displayName)
                    LabeledContent("状态", value: detail.task.status.displayName)
                    LabeledContent("创建时间", value: detail.task.createdAt)
                    if let file = detail.file {
                        LabeledContent("文件", value: file.originalName)
                        LabeledContent("大小", value: ByteCountFormatter.string(fromByteCount: file.sizeBytes, countStyle: .file))
                    }
                }
            } else {
                EmptyStateView(title: "暂无详情", message: "会议详情加载后会显示基本信息。", systemImage: "doc.text")
            }
        }
        .padding(16)
    }
}

private struct ConfirmationStatusView: View {
    let confirmed: Bool
    let onConfirm: () -> Void

    var body: some View {
        DetailSection(title: confirmed ? "确认状态：已确认" : "确认状态：待确认", systemImage: confirmed ? "checkmark.seal" : "exclamationmark.circle") {
            VStack(alignment: .leading, spacing: 10) {
                Text(confirmed ? "会议纪要已确认，可作为正式记录使用。" : "请核对摘要、待办和来源片段，确认后会同步标记这份会议纪要。")
                    .font(.subheadline)
                    .foregroundStyle(HuiyiTheme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if !confirmed {
                    Button {
                        onConfirm()
                    } label: {
                        Label("确认会议纪要", systemImage: "checkmark.circle")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(HuiyiTheme.accent)
                }
            }
        }
    }
}

private struct MeetingSummaryContent: View {
    let result: MeetingProcessingResult

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 16) {
            DetailSection(title: "摘要", systemImage: "text.alignleft") {
                Text(result.summary)
                    .font(.body)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if !result.tags.isEmpty {
                DetailSection(title: "标签", systemImage: "tag") {
                    FlowTags(tags: result.tags)
                }
            }

            if !result.topics.isEmpty {
                DetailSection(title: "议题", systemImage: "list.bullet.rectangle") {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(result.topics) { topic in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(topic.title)
                                    .font(.subheadline.weight(.semibold))
                                if !topic.summary.isEmpty {
                                    Text(topic.summary)
                                        .font(.subheadline)
                                        .foregroundStyle(HuiyiTheme.textSecondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                    }
                }
            }

            if !result.decisions.isEmpty {
                DetailSection(title: "决策", systemImage: "checkmark.seal") {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(Array(result.decisions.enumerated()), id: \.offset) { _, decision in
                            BulletText(text: decision, color: HuiyiTheme.success)
                        }
                    }
                }
            }

            if !result.risks.isEmpty {
                DetailSection(title: "风险", systemImage: "exclamationmark.triangle") {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(result.risks) { risk in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(risk.title)
                                    .font(.subheadline.weight(.semibold))
                                Text(risk.description)
                                    .font(.subheadline)
                                    .foregroundStyle(HuiyiTheme.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                if !risk.recommendation.isEmpty {
                                    Text(risk.recommendation)
                                        .font(.footnote)
                                        .foregroundStyle(HuiyiTheme.warning)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                    }
                }
            }
        }
        .padding(16)
    }
}

private struct TopicContent: View {
    let result: MeetingProcessingResult
    let sourceContext: (TopicItem) -> TranscriptSegmentContext?
    let onSource: (TranscriptSegmentContext) -> Void

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 12) {
            if result.topics.isEmpty {
                EmptyStateView(title: "暂无议题", message: "纪要生成后会显示会议议题。", systemImage: "list.bullet.rectangle")
            } else {
                ForEach(result.topics) { topic in
                    DetailSection(title: topic.title, systemImage: "text.bubble") {
                        if !topic.summary.isEmpty {
                            Text(topic.summary)
                                .font(.subheadline)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if !topic.source.isEmpty {
                            Text(topic.source)
                                .font(.caption)
                                .foregroundStyle(HuiyiTheme.textSecondary)
                        }
                        if let context = sourceContext(topic) {
                            SourceJumpButton(context: context, onSource: onSource)
                        } else {
                            MissingSourceLabel()
                        }
                    }
                }
            }
        }
        .padding(16)
    }
}

private struct DecisionContent: View {
    let result: MeetingProcessingResult
    let sourceContext: (String) -> TranscriptSegmentContext?
    let onSource: (TranscriptSegmentContext) -> Void

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 12) {
            if result.decisions.isEmpty {
                EmptyStateView(title: "暂无决策", message: "会议中的明确结论会显示在这里。", systemImage: "checkmark.seal")
            } else {
                DetailSection(title: "决策", systemImage: "checkmark.seal") {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(Array(result.decisions.enumerated()), id: \.offset) { _, decision in
                            VStack(alignment: .leading, spacing: 4) {
                                BulletText(text: decision, color: HuiyiTheme.success)
                                if let context = sourceContext(decision) {
                                    SourceJumpButton(context: context, onSource: onSource)
                                        .padding(.leading, 18)
                                } else {
                                    MissingSourceLabel()
                                        .padding(.leading, 18)
                                }
                            }
                        }
                    }
                }
            }
        }
        .padding(16)
    }
}

private struct RiskContent: View {
    let result: MeetingProcessingResult
    let sourceContext: (RiskItem) -> TranscriptSegmentContext?
    let onSource: (TranscriptSegmentContext) -> Void

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 12) {
            if result.risks.isEmpty {
                EmptyStateView(title: "暂无风险", message: "会议风险和建议会显示在这里。", systemImage: "exclamationmark.triangle")
            } else {
                ForEach(result.risks) { risk in
                    DetailSection(title: risk.title, systemImage: "exclamationmark.triangle") {
                        Text(risk.description)
                            .font(.subheadline)
                            .fixedSize(horizontal: false, vertical: true)
                        if !risk.recommendation.isEmpty {
                            Text(risk.recommendation)
                                .font(.footnote)
                                .foregroundStyle(HuiyiTheme.warning)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if !risk.level.isEmpty {
                            Text(risk.level)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(HuiyiTheme.danger)
                        }
                        if let context = sourceContext(risk) {
                            SourceJumpButton(context: context, onSource: onSource)
                        } else {
                            MissingSourceLabel()
                        }
                    }
                }
            }
        }
        .padding(16)
    }
}

private struct MeetingTodosContent: View {
    let result: MeetingProcessingResult
    let savingTodoId: String?
    let onAdd: () -> Void
    let onStart: (TodoItem) -> Void
    let onDetail: (TodoItem) -> Void
    let onSource: (TodoItem) -> Void
    let onToggle: (TodoItem) -> Void

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 12) {
            Button {
                onAdd()
            } label: {
                Label("补充待办", systemImage: "plus.circle")
                    .font(.subheadline.weight(.semibold))
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if result.todos.isEmpty {
                EmptyStateView(title: "暂无待办", message: "纪要生成后识别出的行动项会出现在这里。", systemImage: "checklist")
            } else {
                ForEach(result.todos) { todo in
                    TodoRow(todo: todo, isSaving: savingTodoId == todo.id, onStart: {
                        onStart(todo)
                    }, onDetail: {
                        onDetail(todo)
                    }, onSource: {
                        onSource(todo)
                    }) {
                        onToggle(todo)
                    }
                }
            }
        }
        .padding(16)
    }
}

private struct TodoCreateView: View {
    let onSave: (TodoEditDraft) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var assignee = ""
    @State private var dueAt = ""
    @State private var priority = "medium"
    @State private var description = ""
    @State private var status = "pending_confirm"

    var body: some View {
        NavigationStack {
            Form {
                Section("待办") {
                    TextField("标题", text: $title)
                    TextField("负责人", text: $assignee)
                    TextField("截止时间，如 2026-07-01 18:00", text: $dueAt)
                    TextField("说明", text: $description, axis: .vertical)
                        .lineLimit(2...5)
                }

                Section("状态") {
                    Picker("优先级", selection: $priority) {
                        Text("高").tag("high")
                        Text("中").tag("medium")
                        Text("低").tag("low")
                    }
                    Picker("状态", selection: $status) {
                        Text("待确认").tag("pending_confirm")
                        Text("进行中").tag("in_progress")
                        Text("已完成").tag("done")
                        Text("已取消").tag("canceled")
                    }
                }
            }
            .navigationTitle("补充待办")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") {
                        onSave(TodoEditDraft(
                            title: title,
                            assignee: assignee,
                            dueAt: dueAt,
                            priority: priority,
                            description: description,
                            status: status
                        ))
                    }
                    .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

private struct TranscriptContent: View {
    let contexts: [TranscriptSegmentContext]
    let isFiltering: Bool
    let onSource: (TranscriptSegmentContext) -> Void
    let onSpeaker: (TranscriptSegmentContext) -> Void
    let onCopy: (TranscriptSegmentContext) -> Void

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 12) {
            if contexts.isEmpty {
                EmptyStateView(
                    title: isFiltering ? "未找到匹配片段" : "暂无转写原文",
                    message: isFiltering ? "换一个关键词试试。" : "处理完成后会显示带时间戳的发言片段。",
                    systemImage: "text.bubble"
                )
            } else {
                ForEach(contexts) { context in
                    TranscriptRow(context: context) {
                        onSource(context)
                    } onSpeaker: {
                        onSpeaker(context)
                    } onCopy: {
                        onCopy(context)
                    }
                }
            }
        }
        .padding(16)
    }
}

private struct SourceDetailView: View {
    let context: TranscriptSegmentContext
    let isPlaying: Bool
    let playbackMessage: String?
    let onPlay: () -> Void
    let onSpeaker: () -> Void
    let onSaveCorrection: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var correctionText: String
    @State private var editingCorrection = false

    init(
        context: TranscriptSegmentContext,
        isPlaying: Bool,
        playbackMessage: String?,
        onPlay: @escaping () -> Void,
        onSpeaker: @escaping () -> Void,
        onSaveCorrection: @escaping (String) -> Void
    ) {
        self.context = context
        self.isPlaying = isPlaying
        self.playbackMessage = playbackMessage
        self.onPlay = onPlay
        self.onSpeaker = onSpeaker
        self.onSaveCorrection = onSaveCorrection
        _correctionText = State(initialValue: context.segment.text)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("来源片段") {
                    LabeledContent("时间", value: context.segment.timeRangeLabel)
                    LabeledContent("说话人", value: context.segment.speaker)
                    Text(context.segment.text)
                        .font(.subheadline)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Section("核验操作") {
                    Button {
                        onPlay()
                    } label: {
                        Label(isPlaying ? "停止播放" : "播放片段", systemImage: isPlaying ? "pause.circle" : "play.circle")
                    }
                    if let playbackMessage {
                        Text(playbackMessage)
                            .font(.footnote)
                            .foregroundStyle(HuiyiTheme.textSecondary)
                    }
                    Button {
                        dismiss()
                        onSpeaker()
                    } label: {
                        Label("修改本段说话人", systemImage: "person.crop.circle.badge")
                    }
                    Button {
                        editingCorrection.toggle()
                    } label: {
                        Label(editingCorrection ? "收起修正" : "修正这段原文", systemImage: "pencil")
                    }
                }

                if editingCorrection {
                    Section("修正转写原文") {
                        TextField("原文内容", text: $correctionText, axis: .vertical)
                            .lineLimit(4...12)
                        Button {
                            onSaveCorrection(correctionText)
                        } label: {
                            Label("保存修正", systemImage: "checkmark.circle")
                        }
                        .disabled(correctionText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .navigationTitle("来源核验")
            .toolbar {
                Button("关闭") { dismiss() }
            }
        }
    }
}

private struct SpeakerManagementView: View {
    let speakers: [SpeakerIdentity]
    let onSave: (SpeakerIdentity, String, Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var selectedSpeakerId: String
    @State private var targetName: String
    @State private var saveVoiceprint = false

    init(speakers: [SpeakerIdentity], onSave: @escaping (SpeakerIdentity, String, Bool) -> Void) {
        self.speakers = speakers
        self.onSave = onSave
        let first = speakers.first
        _selectedSpeakerId = State(initialValue: first?.id ?? "")
        _targetName = State(initialValue: first?.displayName ?? "")
    }

    var selectedSpeaker: SpeakerIdentity? {
        speakers.first { $0.id == selectedSpeakerId } ?? speakers.first
    }

    var body: some View {
        NavigationStack {
            Form {
                if speakers.isEmpty {
                    EmptyStateView(title: "还没有说话人", message: "处理完成并生成原文后可编辑说话人。", systemImage: "person.2")
                        .listRowInsets(EdgeInsets())
                } else {
                    Section("说话人") {
                        Picker("选择", selection: $selectedSpeakerId) {
                            ForEach(speakers) { speaker in
                                Text(speaker.displayName).tag(speaker.id)
                            }
                        }
                        .onChange(of: selectedSpeakerId) { newValue in
                            targetName = speakers.first { $0.id == newValue }?.displayName ?? ""
                        }
                    }

                    Section("名称") {
                        TextField("说话人名称", text: $targetName)
                        Toggle("同时保存为声纹档案", isOn: $saveVoiceprint)
                    }
                }
            }
            .navigationTitle("编辑说话人")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") {
                        if let selectedSpeaker {
                            onSave(selectedSpeaker, targetName, saveVoiceprint)
                        }
                    }
                    .disabled(selectedSpeaker == nil || targetName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

private struct SegmentSpeakerEditorView: View {
    let context: TranscriptSegmentContext
    let speakers: [SpeakerIdentity]
    let onSave: (String, String?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var selectedSpeakerId: String
    @State private var creatingNew = false
    @State private var newSpeakerName = ""

    init(context: TranscriptSegmentContext, speakers: [SpeakerIdentity], onSave: @escaping (String, String?) -> Void) {
        self.context = context
        self.speakers = speakers
        self.onSave = onSave
        let currentId = context.segment.stableSpeakerId
        _selectedSpeakerId = State(initialValue: speakers.contains { $0.id == currentId } ? currentId : speakers.first?.id ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("当前片段") {
                    LabeledContent("时间", value: context.segment.timeRangeLabel)
                    LabeledContent("当前说话人", value: context.segment.speaker)
                    Text(context.segment.text)
                        .font(.subheadline)
                        .foregroundStyle(HuiyiTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Section("选择说话人") {
                    if speakers.isEmpty {
                        Text("暂无可选说话人，可直接新建。")
                            .font(.subheadline)
                            .foregroundStyle(HuiyiTheme.textSecondary)
                    } else {
                        Picker("已有说话人", selection: $selectedSpeakerId) {
                            ForEach(speakers) { speaker in
                                Text(speaker.displayName).tag(speaker.id)
                            }
                        }
                        .disabled(creatingNew)
                    }
                    Toggle("新建说话人", isOn: $creatingNew)
                    if creatingNew {
                        TextField("新说话人名称", text: $newSpeakerName)
                    }
                }
            }
            .navigationTitle("修改本段说话人")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") {
                        if creatingNew {
                            onSave(newSpeakerName, nil)
                        } else if let speaker = speakers.first(where: { $0.id == selectedSpeakerId }) {
                            onSave(speaker.displayName, speaker.id)
                        }
                    }
                    .disabled(saveDisabled)
                }
            }
        }
    }

    private var saveDisabled: Bool {
        if creatingNew {
            return newSpeakerName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        return speakers.first(where: { $0.id == selectedSpeakerId }) == nil
    }
}

private struct DetailSection<Content: View>: View {
    let title: String
    let systemImage: String
    let content: Content

    init(title: String, systemImage: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.systemImage = systemImage
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: systemImage)
                .font(.headline)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(HuiyiTheme.surface, in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct FlowTags: View {
    let tags: [String]

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 8)], alignment: .leading, spacing: 8) {
            ForEach(tags, id: \.self) { tag in
                Text(tag)
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity)
                    .background(HuiyiTheme.accent.opacity(0.10), in: Capsule())
                    .foregroundStyle(HuiyiTheme.accent)
            }
        }
    }
}

private struct BulletText: View {
    let text: String
    let color: Color

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Circle()
                .fill(color)
                .frame(width: 7, height: 7)
                .padding(.top, 6)
            Text(text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct SourceJumpButton: View {
    let context: TranscriptSegmentContext
    let onSource: (TranscriptSegmentContext) -> Void

    var body: some View {
        Button {
            onSource(context)
        } label: {
            Label("查看来源 \(context.segment.timeRangeLabel)", systemImage: "text.magnifyingglass")
        }
        .font(.caption.weight(.semibold))
    }
}

private struct MissingSourceLabel: View {
    var body: some View {
        Text("来源待核验")
            .font(.caption)
            .foregroundStyle(HuiyiTheme.textSecondary)
    }
}

private struct TodoRow: View {
    let todo: TodoItem
    let isSaving: Bool
    let onStart: () -> Void
    let onDetail: () -> Void
    let onSource: () -> Void
    let onToggle: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Button(action: onToggle) {
                    if isSaving {
                        ProgressView()
                            .frame(width: 24, height: 24)
                    } else {
                        Image(systemName: todo.done ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(todo.done ? HuiyiTheme.success : HuiyiTheme.textSecondary)
                            .frame(width: 24, height: 24)
                    }
                }
                .buttonStyle(.plain)
                .disabled(isSaving)
                VStack(alignment: .leading, spacing: 4) {
                    Button(action: onDetail) {
                        Text(todo.title)
                            .font(.subheadline.weight(.semibold))
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                    if !todo.description.isEmpty {
                        Text(todo.description)
                            .font(.footnote)
                            .foregroundStyle(HuiyiTheme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            HStack(spacing: 8) {
                if let assignee = todo.assignee, !assignee.isEmpty {
                    Label(assignee, systemImage: "person")
                }
                if let dueAt = todo.dueAt, !dueAt.isEmpty {
                    Label(dueAt, systemImage: "calendar")
                }
                Text(todo.priority)
            }
            .font(.caption)
            .foregroundStyle(HuiyiTheme.textSecondary)
            .lineLimit(2)
            .minimumScaleFactor(0.85)
            if todo.status != "in_progress" && !todo.done {
                HStack(spacing: 12) {
                    Button {
                        onStart()
                    } label: {
                        Label("开始", systemImage: "play.circle")
                    }
                    .disabled(isSaving)
                    Button {
                        onDetail()
                    } label: {
                        Label("待办详情", systemImage: "info.circle")
                    }
                    Button {
                        onSource()
                    } label: {
                        Label("查看来源", systemImage: "waveform")
                    }
                }
                .font(.caption.weight(.semibold))
            } else {
                HStack(spacing: 12) {
                    Button {
                        onDetail()
                    } label: {
                        Label("待办详情", systemImage: "info.circle")
                    }
                    Button {
                        onSource()
                    } label: {
                        Label("查看来源", systemImage: "waveform")
                    }
                }
                .font(.caption.weight(.semibold))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(HuiyiTheme.surface, in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct TranscriptRow: View {
    let context: TranscriptSegmentContext
    let onSource: () -> Void
    let onSpeaker: () -> Void
    let onCopy: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(context.segment.speaker)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Spacer()
                Text(context.segment.timestamp)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(HuiyiTheme.textSecondary)
            }
            Text(context.segment.text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 12) {
                Button {
                    onSource()
                } label: {
                    Label("来源核验", systemImage: "waveform")
                }
                Button {
                    onSpeaker()
                } label: {
                    Label("说话人", systemImage: "person")
                }
                Button {
                    onCopy()
                } label: {
                    Label("复制片段", systemImage: "doc.on.doc")
                }
            }
            .font(.caption.weight(.semibold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(HuiyiTheme.surface, in: RoundedRectangle(cornerRadius: 8))
    }
}
