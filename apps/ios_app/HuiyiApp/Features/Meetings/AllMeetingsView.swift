import SwiftUI

struct AllMeetingsView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = MeetingsViewModel()
    @State private var query = ""
    @State private var selectedIds: Set<String> = []
    @State private var selectionMode = false
    @State private var dateFilter: MeetingDateFilter = .all
    @State private var customDateRange = MeetingDateRange()
    @State private var showingCustomDateRange = false
    @State private var pendingSingleDelete: MeetingDetail?
    @State private var showingBulkDeleteConfirmation = false
    @State private var isDeleting = false

    var body: some View {
        NavigationStack {
            SmartScreenScaffold(title: "全部会议", onBack: backAction, trailing: {
                HStack(spacing: 10) {
                    if selectionMode {
                        Button(selectedIds.count == filteredMeetings.count ? "清空" : "全选") {
                            toggleSelectAllVisible()
                        }
                        .disabled(filteredMeetings.isEmpty || isDeleting)
                    }
                    Button(selectionMode ? "完成" : "多选") {
                        selectionMode.toggle()
                        if !selectionMode { selectedIds.removeAll() }
                    }
                    .disabled(isDeleting)
                }
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(HuiyiTheme.brand)
            }) {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }

                SmartCard(radius: 16, padding: 12) {
                    HStack(spacing: 8) {
                        Image(systemName: "magnifyingglass")
                            .foregroundStyle(HuiyiTheme.muted)
                        TextField("搜索标题、摘要、原文", text: $query)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .font(.system(size: 15))
                    }
                    dateFilterBar
                }

                if selectionMode {
                    selectionPanel
                }

                if !visibleProcessingTasks.isEmpty {
                    SmartInfoBlock(title: "正在处理") {
                        VStack(spacing: 12) {
                            ForEach(visibleProcessingTasks) { task in
                                ProcessingTaskRow(task: task) {
                                    router.openProcessing(taskId: task.id)
                                }
                            }
                        }
                    }
                }

                SmartInfoBlock(title: "会议记录") {
                    if viewModel.isLoading {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("正在同步")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(HuiyiTheme.muted)
                        }
                    } else if filteredMeetings.isEmpty {
                        EmptyStateView(
                            title: query.isEmpty ? "暂无会议" : "未找到会议",
                            message: query.isEmpty ? "录音或导入文件后会出现在这里。" : "换个关键词试试。",
                            systemImage: "doc.text"
                        )
                    } else {
                        VStack(spacing: 12) {
                            ForEach(filteredMeetings, id: \.task.id) { detail in
                                MeetingCard(
                                    detail: detail,
                                    selectable: selectionMode,
                                    selected: selectedIds.contains(detail.task.id),
                                    onSelectionChange: { toggleSelection(detail.task.id) }
                                ) {
                                    if detail.task.canOpenProcessingPage {
                                        session.enqueueTask(detail.task)
                                        router.openProcessing(taskId: detail.task.id)
                                    } else {
                                        router.openMeeting(detail.task.remoteTaskId ?? detail.task.id)
                                    }
                                } onDelete: {
                                    pendingSingleDelete = detail
                                }
                            }
                        }
                    }
                }
            }
            .refreshable { await viewModel.load(session: session) }
            .confirmationDialog(
                "删除会议？",
                isPresented: Binding(get: { pendingSingleDelete != nil }, set: { if !$0 { pendingSingleDelete = nil } }),
                titleVisibility: .visible
            ) {
                Button("删除", role: .destructive) {
                    if let pendingSingleDelete {
                        Task { await deleteSingle(pendingSingleDelete) }
                    }
                }
                Button("取消", role: .cancel) { pendingSingleDelete = nil }
            } message: {
                Text("删除后该会议的录音、转写、纪要、待办和知识库索引将不可查看；若已同步，将一并删除云端记录。")
            }
            .confirmationDialog("删除选中会议？", isPresented: $showingBulkDeleteConfirmation, titleVisibility: .visible) {
                Button(isDeleting ? "删除中" : "删除", role: .destructive) {
                    Task { await deleteSelected() }
                }
                .disabled(isDeleting || selectedIds.isEmpty)
                Button("取消", role: .cancel) {}
            } message: {
                Text("将删除选中的 \(selectedIds.count) 场会议，本机录音、转写、纪要、待办和知识库索引也会同步清理；若已同步，将一并删除云端记录。")
            }
            .sheet(isPresented: $showingCustomDateRange) {
                MeetingDateRangePickerView(range: customDateRange) { range in
                    customDateRange = range
                    dateFilter = range.isActive ? .custom : .all
                }
            }
            .task {
                if viewModel.meetings.isEmpty {
                    await viewModel.load(session: session)
                }
            }
        }
    }

    private var dateFilterBar: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(MeetingDateFilter.allCases) { filter in
                    Button {
                        dateFilter = filter
                        if filter == .all {
                            customDateRange = MeetingDateRange()
                        }
                        if filter == .custom {
                            showingCustomDateRange = true
                        }
                    } label: {
                        Text(filter == .custom && customDateRange.isActive ? customDateRange.shortLabel : filter.title)
                            .font(.system(size: 13, weight: dateFilter == filter ? .bold : .regular))
                            .foregroundStyle(dateFilter == filter ? HuiyiTheme.brand : HuiyiTheme.muted)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(dateFilter == filter ? HuiyiTheme.brandSoft : Color.white, in: Capsule())
                            .overlay(Capsule().stroke(dateFilter == filter ? HuiyiTheme.brandSoft : HuiyiTheme.line, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .scrollIndicators(.hidden)
    }

    private var selectionPanel: some View {
        SmartCard(radius: 16, padding: 14) {
            HStack {
                Text("已选 \(selectedVisibleCount) 项")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(HuiyiTheme.ink)
                Spacer()
                Button("取消") {
                    selectionMode = false
                    selectedIds.removeAll()
                }
                .foregroundStyle(HuiyiTheme.muted)
            }
            HStack(spacing: 10) {
                SecondaryActionButton(title: selectedVisibleCount == filteredMeetings.count ? "清空" : "全选", systemImage: "checkmark.circle") {
                    toggleSelectAllVisible()
                }
                Button {
                    showingBulkDeleteConfirmation = true
                } label: {
                    Label(isDeleting ? "删除中" : "删除所选", systemImage: "trash")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(HuiyiTheme.danger, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(selectedVisibleCount == 0 || isDeleting)
            }
        }
    }

    private var filteredMeetings: [MeetingDetail] {
        let meetings = viewModel.visibleMeetings.filter { dateFilter.matches($0.task.createdAtMillis, customRange: customDateRange) }
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return meetings }
        return meetings.filter { detail in
            detail.task.title.localizedCaseInsensitiveContains(clean) ||
                (detail.result?.summary.localizedCaseInsensitiveContains(clean) ?? false) ||
                (detail.result?.participants?.localizedCaseInsensitiveContains(clean) ?? false) ||
                (detail.result?.tags.contains { $0.localizedCaseInsensitiveContains(clean) } ?? false) ||
                (detail.result?.transcripts.contains { $0.text.localizedCaseInsensitiveContains(clean) || $0.speaker.localizedCaseInsensitiveContains(clean) } ?? false)
        }
    }

    private var visibleProcessingTasks: [MeetingTask] {
        session.taskQueue.tasks
            .filter { $0.status == .processing }
            .filter { dateFilter.matches($0.createdAtMillis, customRange: customDateRange) }
            .sorted { $0.createdAtMillis > $1.createdAtMillis }
    }

    private var selectedVisibleCount: Int {
        let visibleIds = Set(filteredMeetings.map(\.task.id))
        return selectedIds.intersection(visibleIds).count
    }

    private func backAction() {
        if selectionMode {
            selectionMode = false
            selectedIds.removeAll()
        } else {
            router.go(.home)
        }
    }

    private func toggleSelection(_ id: String) {
        if selectedIds.contains(id) {
            selectedIds.remove(id)
        } else {
            selectedIds.insert(id)
        }
    }

    private func toggleSelectAllVisible() {
        let visibleIds = Set(filteredMeetings.map(\.task.id))
        if selectedIds.intersection(visibleIds).count == visibleIds.count {
            selectedIds.subtract(visibleIds)
        } else {
            selectedIds.formUnion(visibleIds)
        }
    }

    private func deleteSingle(_ detail: MeetingDetail) async {
        pendingSingleDelete = nil
        isDeleting = true
        defer { isDeleting = false }
        do {
            try await session.deleteMeetingTask(detail.task)
        } catch {
            viewModel.errorMessage = userMessage(error)
        }
        await viewModel.load(session: session)
    }

    private func deleteSelected() async {
        let targets = filteredMeetings.filter { selectedIds.contains($0.task.id) }
        isDeleting = true
        defer { isDeleting = false }
        for detail in targets {
            do {
                try await session.deleteMeetingTask(detail.task)
            } catch {
                viewModel.errorMessage = userMessage(error)
            }
        }
        selectedIds.removeAll()
        selectionMode = false
        showingBulkDeleteConfirmation = false
        await viewModel.load(session: session)
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }
}

private struct ProcessingTaskRow: View {
    let task: MeetingTask
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(task.title)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                        .lineLimit(2)
                    Spacer()
                    SmartStatusBadge(text: task.status.shortLabel, color: task.status.badgeColor)
                }
                ProgressView(value: min(100.0, max(0.0, task.progressPercent)) / 100.0)
                    .tint(HuiyiTheme.brand)
                Text(task.progressLabel ?? task.status.displayName)
                    .font(.system(size: 12))
                    .foregroundStyle(HuiyiTheme.muted)
            }
        }
        .buttonStyle(.plain)
    }
}

private enum MeetingDateFilter: String, CaseIterable, Identifiable {
    case all
    case today
    case yesterday
    case last7Days
    case custom

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: return "全部"
        case .today: return "今天"
        case .yesterday: return "昨天"
        case .last7Days: return "近7天"
        case .custom: return "自定义"
        }
    }

    func matches(_ millis: Int64, customRange: MeetingDateRange) -> Bool {
        guard millis > 0 else { return self == .all }
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
        let calendar = Calendar.current
        switch self {
        case .all:
            return true
        case .today:
            return calendar.isDateInToday(date)
        case .yesterday:
            return calendar.isDateInYesterday(date)
        case .last7Days:
            guard let start = calendar.date(byAdding: .day, value: -6, to: calendar.startOfDay(for: Date())) else {
                return true
            }
            return date >= start
        case .custom:
            return customRange.matches(date)
        }
    }
}

private struct MeetingDateRange {
    var start: Date?
    var end: Date?

    var isActive: Bool {
        start != nil || end != nil
    }

    var label: String {
        switch (start, end) {
        case let (start?, end?):
            return "\(Self.shortDate(start)) ~ \(Self.shortDate(end))"
        case let (start?, nil):
            return "\(Self.shortDate(start)) 起"
        case let (nil, end?):
            return "\(Self.shortDate(end)) 前"
        default:
            return "请选择日期范围"
        }
    }

    var shortLabel: String {
        label.replacingOccurrences(of: " ~ ", with: "~")
    }

    func matches(_ date: Date) -> Bool {
        let day = Calendar.current.startOfDay(for: date)
        if let start, day < Calendar.current.startOfDay(for: start) {
            return false
        }
        if let end, day > Calendar.current.startOfDay(for: end) {
            return false
        }
        return true
    }

    private static func shortDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "MM-dd"
        return formatter.string(from: date)
    }
}

private struct MeetingDateRangePickerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var start: Date
    @State private var end: Date
    let onApply: (MeetingDateRange) -> Void

    init(range: MeetingDateRange, onApply: @escaping (MeetingDateRange) -> Void) {
        _start = State(initialValue: range.start ?? Date())
        _end = State(initialValue: range.end ?? Date())
        self.onApply = onApply
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("日期范围") {
                    DatePicker("开始", selection: $start, displayedComponents: .date)
                    DatePicker("结束", selection: $end, displayedComponents: .date)
                }
            }
            .navigationTitle("自定义范围")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("应用") {
                        onApply(MeetingDateRange(start: start, end: end))
                        dismiss()
                    }
                }
            }
        }
    }
}
