import SwiftUI

struct TasksView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = TasksViewModel()
    @State private var query = ""
    @State private var filter: TodoListFilter = .all
    @State private var mineOnly = false
    @State private var selectedItem: TodoContextItem?
    @State private var pendingDeleteTask: MeetingTask?

    var body: some View {
        NavigationStack {
            SmartScreenScaffold(
                title: "待办任务",
                onBack: { router.go(.home) },
                trailing: {
                    SmartRoundIconButton(
                        systemImage: "arrow.clockwise",
                        accessibilityLabel: "刷新",
                        tint: HuiyiTheme.brand
                    ) {
                        Task { await viewModel.load(session: session) }
                    }
                }
            ) {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }

                searchCard
                todoStatsPanel

                if !visibleLocalTasks.isEmpty {
                    localTasksPanel
                }

                SmartCard(radius: 16, padding: 0) {
                    todoFilterTabs
                    Divider().background(HuiyiTheme.line.opacity(0.6))
                    if viewModel.isLoading {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("正在同步")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(HuiyiTheme.muted)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 22)
                    } else if pendingTodos.isEmpty && doneTodos.isEmpty {
                        Text(emptyTodoText)
                            .font(.system(size: 14))
                            .foregroundStyle(HuiyiTheme.muted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 20)
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(pendingTodos.enumerated()), id: \.element.id) { index, item in
                                SmartTodoActionRow(
                                    item: item,
                                    isSaving: viewModel.savingTodoId == item.todo.id,
                                    onStart: { Task { await viewModel.startTodo(item, session: session) } },
                                    onSource: { router.openMeeting(item.remoteTaskId ?? item.taskId, sourceTodo: item.todo) },
                                    onDetail: { selectedItem = item }
                                )
                                if index < pendingTodos.count - 1 {
                                    Divider().padding(.leading, 16)
                                }
                            }
                            if !doneTodos.isEmpty {
                                Divider()
                                Text("已完成")
                                    .font(.system(size: 16, weight: .bold))
                                    .foregroundStyle(HuiyiTheme.ink)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 16)
                                    .padding(.top, pendingTodos.isEmpty ? 0 : 14)
                                    .padding(.bottom, 4)
                                ForEach(Array(doneTodos.enumerated()), id: \.element.id) { index, item in
                                    SmartTodoActionRow(
                                        item: item,
                                        isSaving: viewModel.savingTodoId == item.todo.id,
                                        onStart: { Task { await viewModel.startTodo(item, session: session) } },
                                        onSource: { router.openMeeting(item.remoteTaskId ?? item.taskId, sourceTodo: item.todo) },
                                        onDetail: { selectedItem = item }
                                    )
                                    if index < doneTodos.count - 1 {
                                        Divider().padding(.leading, 16)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .refreshable { await viewModel.load(session: session) }
            .sheet(item: $selectedItem) { item in
                TodoDetailEditorView(item: item) { draft in
                    Task {
                        await viewModel.updateTodo(item, draft: draft, session: session)
                        selectedItem = nil
                    }
                } onDelete: {
                    Task {
                        await viewModel.deleteTodo(item, session: session)
                        selectedItem = nil
                    }
                } onSource: {
                    router.openMeeting(item.remoteTaskId ?? item.taskId, sourceTodo: item.todo)
                    selectedItem = nil
                }
            }
            .confirmationDialog(
                "删除本地处理任务？",
                isPresented: Binding(
                    get: { pendingDeleteTask != nil },
                    set: { if !$0 { pendingDeleteTask = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("删除", role: .destructive) {
                    if let pendingDeleteTask {
                        session.removeTask(pendingDeleteTask.id)
                    }
                    pendingDeleteTask = nil
                }
                Button("取消", role: .cancel) { pendingDeleteTask = nil }
            } message: {
                Text("正在处理的任务需要先终止；删除后该文件、转写结果和知识库索引将不可查看。")
            }
            .task {
                if viewModel.items.isEmpty {
                    await viewModel.load(session: session)
                }
            }
        }
    }

    private var cleanQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var visibleLocalTasks: [MeetingTask] {
        session.taskQueue.tasks
            .filter(\.canOpenProcessingPage)
            .filter { task in
                cleanQuery.isEmpty || task.title.localizedCaseInsensitiveContains(cleanQuery)
            }
    }

    private var filteredTodos: [TodoContextItem] {
        viewModel.items
            .filter { item in
                switch filter {
                case .all: return item.todo.status != "canceled"
                case .today: return item.isDueToday
                case .overdue: return item.isOverdue
                case .pending: return item.todo.effectiveStatus == "pending_confirm"
                }
            }
            .filter { item in
                !mineOnly || item.todo.assignee?.localizedCaseInsensitiveContains(session.currentUser?.displayName ?? "") == true
            }
            .filter { item in
                cleanQuery.isEmpty ||
                    item.todo.title.localizedCaseInsensitiveContains(cleanQuery) ||
                    item.todo.description.localizedCaseInsensitiveContains(cleanQuery) ||
                    item.todo.assignee?.localizedCaseInsensitiveContains(cleanQuery) == true ||
                    item.meetingTitle.localizedCaseInsensitiveContains(cleanQuery)
            }
    }

    private var activeItems: [TodoContextItem] {
        viewModel.items.filter { $0.todo.status != "canceled" }
    }

    private var pendingTodos: [TodoContextItem] {
        filteredTodos
            .filter { !$0.todo.done && $0.todo.effectiveStatus != "done" }
            .sorted { left, right in
                if left.todo.priority.priorityWeight != right.todo.priority.priorityWeight {
                    return left.todo.priority.priorityWeight > right.todo.priority.priorityWeight
                }
                if left.isOverdue != right.isOverdue {
                    return left.isOverdue && !right.isOverdue
                }
                if left.sortKey != right.sortKey {
                    return left.sortKey < right.sortKey
                }
                return left.todo.title < right.todo.title
            }
    }

    private var doneTodos: [TodoContextItem] {
        filteredTodos
            .filter { $0.todo.effectiveStatus == "done" }
            .sorted { ($0.todo.completedAtMillis ?? 0) > ($1.todo.completedAtMillis ?? 0) }
    }

    private var myTodoCount: Int {
        let name = session.currentUser?.displayName ?? ""
        return viewModel.items.filter { item in
            item.todo.status != "canceled" &&
                !item.todo.done &&
                item.todo.assignee?.localizedCaseInsensitiveContains(name) == true
        }.count
    }

    private var emptyTodoText: String {
        if !cleanQuery.isEmpty { return "没有匹配的待办" }
        if activeItems.isEmpty { return "还没有待办事项" }
        return "当前筛选下没有待办"
    }

    private var searchCard: some View {
        HStack(spacing: 9) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(HuiyiTheme.muted)
            TextField("搜索标题、截止时间、负责人", text: $query)
                .font(.system(size: 14))
                .foregroundStyle(HuiyiTheme.ink)
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(HuiyiTheme.muted.opacity(0.7))
                }
                .buttonStyle(.plain)
            }
        }
        .frame(height: 48)
        .padding(.horizontal, 15)
        .background(Color.white.opacity(0.88), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.white.opacity(0.74), lineWidth: 1)
        )
        .shadow(color: HuiyiTheme.brandDark.opacity(0.08), radius: 8, x: 0, y: 2)
    }

    private var todoStatsPanel: some View {
        let urgent = activeItems
            .filter { !$0.todo.done }
            .sorted {
                if $0.isOverdue != $1.isOverdue { return $0.isOverdue && !$1.isOverdue }
                return ($0.todo.dueAtMillis ?? Int64.max) < ($1.todo.dueAtMillis ?? Int64.max)
            }
            .first
        return SmartGradientPanel(radius: 16, gradient: LinearGradient(colors: [HuiyiTheme.brandCyan, Color(red: 0.435, green: 0.608, blue: 1.000), Color(red: 0.553, green: 0.490, blue: 1.000)], startPoint: .leading, endPoint: .trailing)) {
            VStack(spacing: 16) {
                HStack(spacing: 0) {
                    SmartTodoStatNumber(value: "\(activeItems.filter(\.isOverdue).count)", label: "已逾期")
                    SmartTodoStatNumber(value: "\(activeItems.filter(\.isDueToday).count)", label: "今日到期")
                    SmartTodoStatNumber(value: "\(myTodoCount)", label: "我负责")
                    SmartTodoStatNumber(value: "\(activeItems.filter { $0.todo.status == "pending_confirm" }.count)", label: "待确认")
                }
                Rectangle()
                    .fill(Color.white.opacity(0.24))
                    .frame(height: 1)
                if let urgent {
                    Button {
                        selectedItem = urgent
                    } label: {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack(spacing: 10) {
                                SmartTodoTag(
                                    text: urgent.isOverdue ? "超时" : "待办",
                                    background: Color(red: 1.000, green: 0.925, blue: 0.910),
                                    foreground: HuiyiTheme.danger
                                )
                                Text(urgent.todo.dueAt ?? "截止待补充")
                                    .font(.system(size: 13))
                                    .foregroundStyle(HuiyiTheme.muted)
                                    .lineLimit(1)
                                Spacer()
                                Text("去处理")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 7)
                                    .background(HuiyiTheme.brand, in: Capsule())
                            }
                            Text(urgent.todo.title)
                                .font(.system(size: 17, weight: .bold))
                                .foregroundStyle(HuiyiTheme.ink)
                                .lineLimit(1)
                            Text(urgent.todo.description.isEmpty ? urgent.meetingTitle : urgent.todo.description)
                                .font(.system(size: 14))
                                .foregroundStyle(HuiyiTheme.muted)
                                .lineLimit(2)
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                } else {
                    Text("暂无紧急待办")
                        .font(.system(size: 14))
                        .foregroundStyle(Color.white.opacity(0.86))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(20)
        }
    }

    private var localTasksPanel: some View {
        SmartInfoBlock(title: "本地处理任务") {
            VStack(spacing: 0) {
                ForEach(Array(visibleLocalTasks.enumerated()), id: \.element.id) { index, task in
                    SmartLocalProcessingTaskRow(task: task) {
                        router.openProcessing(taskId: task.id)
                    } onDelete: {
                        pendingDeleteTask = task
                    }
                    if index < visibleLocalTasks.count - 1 {
                        Divider().padding(.leading, 52)
                    }
                }
            }
        }
    }

    private var todoFilterTabs: some View {
        HStack(spacing: 0) {
            SmartTodoFilterTab(label: "我负责", systemImage: "person.fill", selected: mineOnly && filter == .all) {
                if mineOnly && filter == .all {
                    mineOnly = false
                } else {
                    mineOnly = true
                    filter = .all
                }
            }
            SmartTodoFilterTab(label: "今日到期", systemImage: "calendar", selected: !mineOnly && filter == .today) {
                mineOnly = false
                filter = .today
            }
            SmartTodoFilterTab(label: "已逾期", systemImage: "arrow.clockwise", selected: !mineOnly && filter == .overdue) {
                mineOnly = false
                filter = .overdue
            }
            SmartTodoFilterTab(label: "待确认", systemImage: "doc.text", selected: !mineOnly && filter == .pending) {
                mineOnly = false
                filter = .pending
            }
        }
        .padding(.horizontal, 8)
        .padding(.top, 8)
    }
}

private struct SmartLocalProcessingTaskRow: View {
    let task: MeetingTask
    let onOpen: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Button(action: onOpen) {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "doc.badge.arrow.up")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(HuiyiTheme.brand)
                        .frame(width: 40, height: 40)
                        .background(HuiyiTheme.brandSoft, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    VStack(alignment: .leading, spacing: 6) {
                        Text(task.title)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(HuiyiTheme.ink)
                            .lineLimit(2)
                        Text([task.status.title, task.sizeLabel].compactMap { $0 }.joined(separator: " · "))
                            .font(.system(size: 13))
                            .foregroundStyle(task.status == .failed ? HuiyiTheme.danger : HuiyiTheme.muted)
                            .lineLimit(2)
                        if task.status == .processing {
                            ProgressView(value: Double(task.progressPercent) / 100.0)
                                .tint(HuiyiTheme.brand)
                            Text(task.progressLabel ?? "\(task.progressPercent)%")
                                .font(.system(size: 12))
                                .foregroundStyle(HuiyiTheme.muted)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 10)
            }
            .buttonStyle(.plain)

            Button(action: onDelete) {
                Image(systemName: "trash")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(task.status == .processing ? HuiyiTheme.muted.opacity(0.45) : HuiyiTheme.danger)
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(.plain)
            .disabled(task.status == .processing)
        }
    }
}

private struct SmartTodoFilterTab: View {
    let label: String
    let systemImage: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: systemImage)
                    .font(.system(size: 20, weight: .semibold))
                Text(label)
                    .font(.system(size: 13, weight: selected ? .bold : .medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
                Capsule()
                    .fill(selected ? HuiyiTheme.brand : Color.clear)
                    .frame(width: 28, height: 3)
            }
            .foregroundStyle(selected ? HuiyiTheme.brand : HuiyiTheme.muted)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }
}

private struct SmartTodoTag: View {
    let text: String
    let background: Color
    let foreground: Color

    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(foreground)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(background, in: Capsule())
    }
}

private struct SmartTodoStatNumber: View {
    let value: String
    let label: String

    var body: some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.system(size: 24, weight: .bold))
                .monospacedDigit()
            Text(label)
                .font(.system(size: 13))
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity)
    }
}

private struct SmartTodoActionRow: View {
    let item: TodoContextItem
    let isSaving: Bool
    let onStart: () -> Void
    let onSource: () -> Void
    let onDetail: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 6) {
                Text(item.todo.title)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(HuiyiTheme.ink)
                    .lineLimit(2)
                HStack(spacing: 10) {
                    Text(item.todo.assignee.flatMap { $0.isEmpty ? nil : "负责人 \($0)" } ?? "负责人待补充")
                    Text(item.todo.dueAt.flatMap { $0.isEmpty ? nil : "截止 \($0)" } ?? "截止待补充")
                }
                .font(.system(size: 12))
                .foregroundStyle(HuiyiTheme.muted)
                .lineLimit(2)
                HStack(spacing: 6) {
                    SmartTodoTag(
                        text: item.isOverdue ? "逾期" : item.todo.effectiveStatus.todoStatusLabel,
                        background: item.isOverdue ? Color(red: 1.000, green: 0.925, blue: 0.910) : HuiyiTheme.brandSoft,
                        foreground: item.isOverdue ? HuiyiTheme.danger : HuiyiTheme.brand
                    )
                    SmartTodoTag(
                        text: item.todo.priority.todoPriorityLabel,
                        background: item.todo.priority.normalizedTodoPriority == "high" ? Color(red: 1.000, green: 0.925, blue: 0.910) : Color(red: 0.941, green: 0.957, blue: 0.961),
                        foreground: item.todo.priority.normalizedTodoPriority == "high" ? HuiyiTheme.danger : HuiyiTheme.muted
                    )
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .trailing, spacing: 8) {
                Button(action: onDetail) {
                    Text("查看")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(HuiyiTheme.brand)
                        .padding(.horizontal, 13)
                        .padding(.vertical, 6)
                        .background(Color.clear, in: Capsule())
                        .overlay(Capsule().stroke(HuiyiTheme.brand.opacity(0.28), lineWidth: 1))
                }
                .buttonStyle(.plain)
                Button(action: onSource) {
                    Text("来源")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(HuiyiTheme.brand)
                }
                .buttonStyle(.plain)
                if item.todo.effectiveStatus == "pending_confirm" || item.todo.effectiveStatus == "todo" {
                    Button(action: onStart) {
                        Text(isSaving ? "保存中" : "开始")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(HuiyiTheme.success)
                    }
                    .buttonStyle(.plain)
                    .disabled(isSaving)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }
}

private struct LegacyTasksView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = TasksViewModel()
    @State private var query = ""
    @State private var filter: TodoListFilter = .all
    @State private var mineOnly = false
    @State private var selectedItem: TodoContextItem?
    @State private var pendingDeleteTask: MeetingTask?

    var body: some View {
        NavigationStack {
            List {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }

                todoStatsSection

                if !session.taskQueue.tasks.filter(\.canOpenProcessingPage).isEmpty {
                    Section("本地处理任务") {
                        ForEach(session.taskQueue.tasks.filter(\.canOpenProcessingPage)) { task in
                            HStack(alignment: .center, spacing: 10) {
                                Button {
                                    router.openProcessing(taskId: task.id)
                                } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(task.title).lineLimit(2)
                                        Text(task.errorMessage ?? task.progressLabel ?? task.status.displayName)
                                            .font(.caption)
                                            .foregroundStyle((task.status == .failed || task.status == .canceled) ? HuiyiTheme.danger : HuiyiTheme.textSecondary)
                                            .lineLimit(2)
                                    }
                                }
                                Spacer()
                                Text(task.status.title)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle((task.status == .failed || task.status == .canceled) ? HuiyiTheme.danger : HuiyiTheme.textSecondary)
                                Button(role: .destructive) {
                                    pendingDeleteTask = task
                                } label: {
                                    Image(systemName: "trash")
                                }
                                .buttonStyle(.borderless)
                                .disabled(task.status == .processing)
                            }
                        }
                    }
                }

                Section {
                    Picker("筛选", selection: $filter) {
                        ForEach(TodoListFilter.allCases) { item in
                            Text(item.title).tag(item)
                        }
                    }
                    .pickerStyle(.segmented)

                    Toggle("只看我的", isOn: $mineOnly)
                }

                Section("待办") {
                    if viewModel.isLoading {
                        ProgressView("正在同步")
                    } else if filteredTodos.isEmpty {
                        EmptyStateView(title: "暂无待办", message: "会议纪要生成后，行动项会汇总在这里。", systemImage: "checklist")
                            .listRowInsets(EdgeInsets())
                    } else {
                        ForEach(filteredTodos) { item in
                            TodoListRow(item: item, isSaving: viewModel.savingTodoId == item.todo.id) {
                                Task { await viewModel.toggle(item, session: session) }
                            } onStart: {
                                Task { await viewModel.startTodo(item, session: session) }
                            } onSource: {
                                router.openMeeting(item.remoteTaskId ?? item.taskId, sourceTodo: item.todo)
                            } onDetail: {
                                selectedItem = item
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "搜索待办、负责人、会议")
            .navigationTitle("待办")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await viewModel.load(session: session) }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .refreshable { await viewModel.load(session: session) }
            .sheet(item: $selectedItem) { item in
                TodoDetailEditorView(item: item) { draft in
                    Task {
                        await viewModel.updateTodo(item, draft: draft, session: session)
                        selectedItem = nil
                    }
                } onDelete: {
                    Task {
                        await viewModel.deleteTodo(item, session: session)
                        selectedItem = nil
                    }
                } onSource: {
                    router.openMeeting(item.remoteTaskId ?? item.taskId, sourceTodo: item.todo)
                    selectedItem = nil
                }
            }
            .confirmationDialog(
                "删除本地处理任务？",
                isPresented: Binding(
                    get: { pendingDeleteTask != nil },
                    set: { if !$0 { pendingDeleteTask = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("删除", role: .destructive) {
                    if let pendingDeleteTask {
                        session.removeTask(pendingDeleteTask.id)
                    }
                    pendingDeleteTask = nil
                }
                Button("取消", role: .cancel) { pendingDeleteTask = nil }
            } message: {
                Text("正在处理的任务需要先终止；删除后该文件、转写结果和知识库索引将不可查看。")
            }
            .task {
                if viewModel.items.isEmpty {
                    await viewModel.load(session: session)
                }
            }
        }
    }

    private var filteredTodos: [TodoContextItem] {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return viewModel.items
            .filter { item in
                switch filter {
                case .all: return item.todo.status != "canceled"
                case .today: return item.isDueToday
                case .overdue: return item.isOverdue
                case .pending: return item.todo.effectiveStatus == "pending_confirm"
                }
            }
            .filter { item in
                !mineOnly || item.todo.assignee?.localizedCaseInsensitiveContains(session.currentUser?.displayName ?? "") == true
            }
            .filter { item in
                clean.isEmpty ||
                    item.todo.title.localizedCaseInsensitiveContains(clean) ||
                    item.todo.description.localizedCaseInsensitiveContains(clean) ||
                    item.todo.assignee?.localizedCaseInsensitiveContains(clean) == true ||
                    item.meetingTitle.localizedCaseInsensitiveContains(clean)
            }
    }

    @ViewBuilder
    private var todoStatsSection: some View {
        let activeItems = viewModel.items.filter { $0.todo.status != "canceled" }
        let urgent = activeItems
            .filter { !$0.todo.done }
            .sorted {
                if $0.isOverdue != $1.isOverdue { return $0.isOverdue && !$1.isOverdue }
                return ($0.todo.dueAtMillis ?? Int64.max) < ($1.todo.dueAtMillis ?? Int64.max)
            }
            .first
        Section("待办概览") {
            HStack(spacing: 12) {
                TodoStatTile(value: "\(activeItems.filter(\.isOverdue).count)", label: "逾期")
                TodoStatTile(value: "\(activeItems.filter(\.isDueToday).count)", label: "今日")
                TodoStatTile(value: "\(myTodoCount)", label: "我的")
                TodoStatTile(value: "\(activeItems.filter { $0.todo.status == "pending_confirm" }.count)", label: "待确认")
            }
            if let urgent {
                Button {
                    selectedItem = urgent
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("优先处理")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(HuiyiTheme.warning)
                        Text(urgent.todo.title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(2)
                        Text(urgent.meetingTitle)
                            .font(.caption)
                            .foregroundStyle(HuiyiTheme.textSecondary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    private var myTodoCount: Int {
        let name = session.currentUser?.displayName ?? ""
        return viewModel.items.filter { item in
            item.todo.status != "canceled" &&
                !item.todo.done &&
                item.todo.assignee?.localizedCaseInsensitiveContains(name) == true
        }.count
    }
}

@MainActor
final class TasksViewModel: ObservableObject {
    @Published private(set) var items: [TodoContextItem] = []
    @Published var isLoading = false
    @Published var savingTodoId: String?
    @Published var errorMessage: String?

    func load(session: AppSession) async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let bootstrap = try await session.loadCloudBootstrap()
            let cloudDetails = bootstrap.tasks.compactMap { item -> MeetingDetail? in
                guard let result = item.result else { return nil }
                return MeetingDetail(task: item.task.toClientTask(), file: item.file, result: result)
            }
            let cloudIds = Set(cloudDetails.flatMap { detail in
                [detail.task.id, detail.task.remoteTaskId ?? ""]
            })
            let localDetails = session.localMeetingDetails().filter { detail in
                !cloudIds.contains(detail.task.id) && !cloudIds.contains(detail.task.remoteTaskId ?? "")
            }
            items = (cloudDetails + localDetails).flatMap { detail in
                (detail.result?.todos ?? []).map { todo in
                    TodoContextItem(
                        todo: todo,
                        taskId: detail.task.id,
                        remoteTaskId: detail.task.remoteTaskId,
                        meetingTitle: detail.task.title,
                        result: detail.result
                    )
                }
            }
            .sorted { $0.sortKey < $1.sortKey }
        } catch {
            items = todoItems(from: session.localMeetingDetails())
            errorMessage = userMessage(error)
        }
    }

    private func todoItems(from details: [MeetingDetail]) -> [TodoContextItem] {
        details.flatMap { detail in
            (detail.result?.todos ?? []).map { todo in
                TodoContextItem(
                    todo: todo,
                    taskId: detail.task.id,
                    remoteTaskId: detail.task.remoteTaskId,
                    meetingTitle: detail.task.title,
                    result: detail.result
                )
            }
        }
        .sorted { $0.sortKey < $1.sortKey }
    }

    func toggle(_ item: TodoContextItem, session: AppSession) async {
        guard var result = item.result else { return }
        savingTodoId = item.todo.id
        errorMessage = nil
        defer { savingTodoId = nil }
        guard let index = result.todos.firstIndex(where: { $0.id == item.todo.id }) else { return }
        result.todos[index].done.toggle()
        if result.todos[index].done {
            result.todos[index].status = "done"
            result.todos[index].completedAtMillis = Int64(Date().timeIntervalSince1970 * 1000)
        } else {
            let hasAssignee = result.todos[index].assignee?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            result.todos[index].status = hasAssignee ? "todo" : "pending_confirm"
            result.todos[index].completedAt = nil
            result.todos[index].completedAtMillis = nil
        }
        result.todos[index].lockField("status")
        do {
            let updated = try await session.updateMeetingResult(taskId: item.remoteTaskId ?? item.taskId, request: ResultUpdateRequest(todos: result.todos))
            items = items.map { current in
                guard current.taskId == item.taskId else { return current }
                var copy = current
                copy.result = updated
                if let nextTodo = updated.todos.first(where: { $0.id == current.todo.id }) {
                    copy.todo = nextTodo
                }
                return copy
            }
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func startTodo(_ item: TodoContextItem, session: AppSession) async {
        guard var result = item.result else { return }
        guard item.todo.assignee?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else {
            errorMessage = "请先补全负责人"
            return
        }
        savingTodoId = item.todo.id
        errorMessage = nil
        defer { savingTodoId = nil }
        guard let index = result.todos.firstIndex(where: { $0.id == item.todo.id }) else { return }
        result.todos[index].status = "in_progress"
        result.todos[index].done = false
        result.todos[index].lockField("status")
        do {
            let updated = try await session.updateMeetingResult(taskId: item.remoteTaskId ?? item.taskId, request: ResultUpdateRequest(todos: result.todos))
            applyUpdatedResult(updated, for: item)
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func updateTodo(_ item: TodoContextItem, draft: TodoEditDraft, session: AppSession) async {
        guard var result = item.result else { return }
        savingTodoId = item.todo.id
        errorMessage = nil
        defer { savingTodoId = nil }
        guard let index = result.todos.firstIndex(where: { $0.id == item.todo.id }) else { return }
        let cleanTitle = draft.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTitle.isEmpty else {
            errorMessage = "任务标题不能为空"
            return
        }
        guard cleanTitle.count <= 100 else {
            errorMessage = "任务标题不能超过 100 个字符"
            return
        }
        let cleanAssignee = draft.assignee.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDueAt = draft.dueAt.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDescription = draft.description.trimmingCharacters(in: .whitespacesAndNewlines)
        let previousTodo = result.todos[index]
        result.todos[index].appendLockedFields(from: previousTodo, title: cleanTitle, assignee: cleanAssignee, dueAt: cleanDueAt, priority: draft.priority, description: cleanDescription, status: draft.status)
        result.todos[index].title = cleanTitle
        result.todos[index].assignee = cleanAssignee.isEmpty ? nil : cleanAssignee
        result.todos[index].dueAt = cleanDueAt.isEmpty ? nil : cleanDueAt
        result.todos[index].dueAtMillis = TodoDueParser.parseMillis(cleanDueAt)
        result.todos[index].priority = draft.priority.normalizedTodoPriority
        result.todos[index].description = cleanDescription
        result.todos[index].status = draft.status
        result.todos[index].done = draft.status == "done"
        if result.todos[index].done && result.todos[index].completedAtMillis == nil {
            result.todos[index].completedAtMillis = Int64(Date().timeIntervalSince1970 * 1000)
        } else if !result.todos[index].done {
            result.todos[index].completedAt = nil
            result.todos[index].completedAtMillis = nil
        }
        do {
            let updated = try await session.updateMeetingResult(taskId: item.remoteTaskId ?? item.taskId, request: ResultUpdateRequest(todos: result.todos))
            applyUpdatedResult(updated, for: item)
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func deleteTodo(_ item: TodoContextItem, session: AppSession) async {
        guard var result = item.result else { return }
        savingTodoId = item.todo.id
        errorMessage = nil
        defer { savingTodoId = nil }
        guard let index = result.todos.firstIndex(where: { $0.id == item.todo.id }) else { return }
        result.todos[index].status = "canceled"
        result.todos[index].done = false
        result.todos[index].lockField("status")
        do {
            let updated = try await session.updateMeetingResult(taskId: item.remoteTaskId ?? item.taskId, request: ResultUpdateRequest(todos: result.todos))
            applyUpdatedResult(updated, for: item)
        } catch {
            errorMessage = userMessage(error)
        }
    }

    private func applyUpdatedResult(_ updated: MeetingProcessingResult, for item: TodoContextItem) {
        items = items.map { current in
            guard current.taskId == item.taskId else { return current }
            var copy = current
            copy.result = updated
            if let nextTodo = updated.todos.first(where: { $0.id == current.todo.id }) {
                copy.todo = nextTodo
            }
            return copy
        }
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }
}

struct TodoContextItem: Identifiable, Equatable {
    var todo: TodoItem
    let taskId: String
    let remoteTaskId: String?
    let meetingTitle: String
    var result: MeetingProcessingResult?

    var id: String { "\(taskId)-\(todo.id)" }

    var sortKey: Int64 {
        todo.dueAtMillis ?? Int64.max
    }

    var isDueToday: Bool {
        guard let dueAtMillis = todo.dueAtMillis else { return false }
        return Calendar.current.isDateInToday(Date(timeIntervalSince1970: TimeInterval(dueAtMillis) / 1000))
    }

    var isOverdue: Bool {
        guard let dueAtMillis = todo.dueAtMillis else { return false }
        return dueAtMillis < Int64(Date().timeIntervalSince1970 * 1000) && !todo.done
    }
}

enum TodoListFilter: String, CaseIterable, Identifiable {
    case all
    case today
    case overdue
    case pending

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: return "全部"
        case .today: return "今日"
        case .overdue: return "逾期"
        case .pending: return "待确认"
        }
    }
}

private struct TodoListRow: View {
    let item: TodoContextItem
    let isSaving: Bool
    let onToggle: () -> Void
    let onStart: () -> Void
    let onSource: () -> Void
    let onDetail: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Button(action: onToggle) {
                    if isSaving {
                        ProgressView().frame(width: 24, height: 24)
                    } else {
                        Image(systemName: item.todo.done ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(item.todo.done ? HuiyiTheme.success : HuiyiTheme.textSecondary)
                    }
                }
                .buttonStyle(.plain)
                .disabled(isSaving)

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.todo.title)
                        .font(.headline)
                        .lineLimit(3)
                    if !item.todo.description.isEmpty {
                        Text(item.todo.description)
                            .font(.subheadline)
                            .foregroundStyle(HuiyiTheme.textSecondary)
                            .lineLimit(3)
                    }
                }
            }

            HStack(spacing: 8) {
                Label(item.meetingTitle, systemImage: "doc.text")
                if let assignee = item.todo.assignee, !assignee.isEmpty {
                    Label(assignee, systemImage: "person")
                }
                if let dueAt = item.todo.dueAt, !dueAt.isEmpty {
                    Label(dueAt, systemImage: "calendar")
                }
            }
            .font(.caption)
            .foregroundStyle(HuiyiTheme.textSecondary)
            .lineLimit(2)

            Button {
                onSource()
            } label: {
                Label("查看来源会议", systemImage: "arrow.turn.down.right")
                    .font(.caption.weight(.semibold))
            }
            HStack(spacing: 12) {
                if item.todo.status != "in_progress" && !item.todo.done {
                    Button {
                        onStart()
                    } label: {
                        Label("开始", systemImage: "play.circle")
                    }
                    .disabled(isSaving)
                }
                Button {
                    onDetail()
                } label: {
                    Label("待办详情", systemImage: "info.circle")
                }
            }
            .font(.caption.weight(.semibold))
        }
        .padding(.vertical, 6)
    }
}

private struct TodoStatTile: View {
    let value: String
    let label: String

    var body: some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.headline)
                .monospacedDigit()
            Text(label)
                .font(.caption2)
                .foregroundStyle(HuiyiTheme.textSecondary)
        }
        .frame(maxWidth: .infinity, minHeight: 52)
        .background(HuiyiTheme.accent.opacity(0.07), in: RoundedRectangle(cornerRadius: 8))
    }
}

struct TodoEditDraft {
    var title: String
    var assignee: String
    var dueAt: String
    var priority: String
    var description: String
    var status: String
}

struct TodoDetailEditorView: View {
    let item: TodoContextItem
    let onSave: (TodoEditDraft) -> Void
    let onDelete: () -> Void
    let onSource: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var assignee: String
    @State private var dueAt: String
    @State private var priority: String
    @State private var description: String
    @State private var status: String
    @State private var confirmingDelete = false

    init(item: TodoContextItem, onSave: @escaping (TodoEditDraft) -> Void, onDelete: @escaping () -> Void, onSource: @escaping () -> Void) {
        self.item = item
        self.onSave = onSave
        self.onDelete = onDelete
        self.onSource = onSource
        _title = State(initialValue: item.todo.title)
        _assignee = State(initialValue: item.todo.assignee ?? "")
        _dueAt = State(initialValue: item.todo.dueAt ?? "")
        _priority = State(initialValue: item.todo.priority)
        _description = State(initialValue: item.todo.description)
        _status = State(initialValue: item.todo.status)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("待办") {
                    TextField("标题", text: $title)
                    TextField("负责人", text: $assignee)
                    TextField("截止时间", text: $dueAt)
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

                Section("来源") {
                    LabeledContent("会议", value: item.meetingTitle)
                    Button {
                        onSource()
                        dismiss()
                    } label: {
                        Label("查看来源会议", systemImage: "arrow.turn.down.right")
                    }
                }

                Section {
                    Button("删除待办", role: .destructive) {
                        confirmingDelete = true
                    }
                }
            }
            .navigationTitle("待办详情")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") {
                        onSave(TodoEditDraft(
                            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                            assignee: assignee.trimmingCharacters(in: .whitespacesAndNewlines),
                            dueAt: dueAt.trimmingCharacters(in: .whitespacesAndNewlines),
                            priority: priority,
                            description: description.trimmingCharacters(in: .whitespacesAndNewlines),
                            status: status
                        ))
                        dismiss()
                    }
                    .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .confirmationDialog("删除这个待办？", isPresented: $confirmingDelete, titleVisibility: .visible) {
                Button("删除", role: .destructive) {
                    onDelete()
                    dismiss()
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("删除后这条待办会从当前会议和待办列表中隐藏，并同步到云端。")
            }
        }
    }
}

private extension ClientMeetingTaskStatus {
    var title: String {
        switch self {
        case .localSaved: return "本地"
        case .waitingProcess: return "待处理"
        case .processing: return "处理中"
        case .waitingRetry: return "可继续"
        case .failed: return "失败"
        case .canceled: return "已终止"
        case .finished: return "完成"
        }
    }
}

private extension String {
    var priorityWeight: Int {
        switch normalizedTodoPriority {
        case "high": return 3
        case "low": return 1
        default: return 2
        }
    }

    var todoPriorityLabel: String {
        switch normalizedTodoPriority {
        case "high": return "高优先级"
        case "low": return "低优先级"
        default: return "中优先级"
        }
    }

    var todoStatusLabel: String {
        switch self {
        case "done": return "已完成"
        case "in_progress": return "进行中"
        case "todo": return "待办"
        case "canceled": return "已取消"
        default: return "待确认"
        }
    }
}
