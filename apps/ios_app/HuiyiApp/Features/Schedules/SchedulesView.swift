import SwiftUI

struct SchedulesView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = SchedulesViewModel()
    @State private var showingEditor = false
    @State private var editingSchedule: ScheduledMeeting?
    @State private var selectedFilter: ScheduleDateFilter = .all
    @State private var customDateRange = ScheduleDateRange()
    @State private var showingCustomDateRange = false
    @State private var pendingDeleteSchedule: ScheduledMeeting?

    var body: some View {
        NavigationStack {
            SmartScreenScaffold(
                title: "预约会议",
                onBack: { router.go(.home) },
                trailing: {
                    SmartRoundIconButton(systemImage: "plus", accessibilityLabel: "新建预约", tint: HuiyiTheme.brand) {
                        editingSchedule = nil
                        showingEditor = true
                    }
                }
            ) {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }

                SmartInfoBlock(title: "筛选") {
                    SmartSegmentedPicker(items: ScheduleDateFilter.allCases, selection: $selectedFilter, title: \.title)
                    if selectedFilter == .custom {
                        Button {
                            showingCustomDateRange = true
                        } label: {
                            HStack {
                                Text("自定义范围")
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundStyle(HuiyiTheme.ink)
                                Spacer()
                                Text(customDateRange.label)
                                    .font(.system(size: 13))
                                    .foregroundStyle(HuiyiTheme.muted)
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(HuiyiTheme.muted)
                            }
                            .padding(.top, 4)
                        }
                        .buttonStyle(.plain)
                    }
                }

                SmartInfoBlock(title: "今日会议") {
                    ScheduleSmartRows(
                        schedules: todayFilteredSchedules,
                        emptyText: "暂无预约",
                        onStart: { router.startRecording(schedule: $0) },
                        onEdit: {
                            editingSchedule = $0
                            showingEditor = true
                        },
                        onDelete: { pendingDeleteSchedule = $0 }
                    )
                }

                if !otherFilteredSchedules.isEmpty {
                    SmartInfoBlock(title: "其他预约") {
                        ScheduleSmartRows(
                            schedules: otherFilteredSchedules,
                            emptyText: "暂无预约",
                            onStart: { router.startRecording(schedule: $0) },
                            onEdit: {
                                editingSchedule = $0
                                showingEditor = true
                            },
                            onDelete: { pendingDeleteSchedule = $0 }
                        )
                    }
                } else if todayFilteredSchedules.isEmpty {
                    SmartInfoBlock(title: "筛选结果") {
                        Text("没有符合筛选的预约")
                            .font(.system(size: 14))
                            .foregroundStyle(HuiyiTheme.muted)
                    }
                }
            }
            .sheet(isPresented: $showingEditor) {
                ScheduleEditorView(schedule: editingSchedule) { draft in
                    await viewModel.save(draft, session: session)
                }
            }
            .confirmationDialog(
                "删除预约会议？",
                isPresented: Binding(
                    get: { pendingDeleteSchedule != nil },
                    set: { if !$0 { pendingDeleteSchedule = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("删除", role: .destructive) {
                    if let pendingDeleteSchedule {
                        Task { await viewModel.delete(pendingDeleteSchedule, session: session) }
                    }
                    pendingDeleteSchedule = nil
                }
                Button("取消", role: .cancel) {
                    pendingDeleteSchedule = nil
                }
            } message: {
                Text("删除后该预约会从 App 内列表移除；若已同步云端，也会同步删除云端预约。已结束会议不支持删除。")
            }
            .sheet(item: Binding(
                get: { session.reminderSchedule },
                set: { if $0 == nil { session.dismissScheduleReminder() } }
            )) { schedule in
                ScheduleReminderView(schedule: schedule) {
                    if let meeting = session.consumeScheduleReminderForRecording() {
                        router.startRecording(schedule: meeting)
                    }
                } onLater: {
                    session.snoozeScheduleReminder()
                } onDismiss: {
                    session.dismissScheduleReminder()
                }
            }
            .refreshable { await viewModel.load(session: session) }
            .sheet(isPresented: $showingCustomDateRange) {
                ScheduleDateRangePickerView(range: customDateRange) { range in
                    customDateRange = range
                    selectedFilter = range.isActive ? .custom : .all
                }
            }
            .task {
                if viewModel.schedules.isEmpty {
                    await viewModel.load(session: session)
                }
                session.checkScheduleReminders(schedules: viewModel.schedules)
            }
        }
    }

    private var filteredSchedules: [ScheduledMeeting] {
        viewModel.filteredSchedules(selectedFilter, customRange: customDateRange)
    }

    private var todayFilteredSchedules: [ScheduledMeeting] {
        filteredSchedules.filter { $0.isTodaySchedule }
    }

    private var otherFilteredSchedules: [ScheduledMeeting] {
        filteredSchedules.filter { !$0.isTodaySchedule }
    }
}

private struct ScheduleSmartRows: View {
    let schedules: [ScheduledMeeting]
    let emptyText: String
    let onStart: (ScheduledMeeting) -> Void
    let onEdit: (ScheduledMeeting) -> Void
    let onDelete: (ScheduledMeeting) -> Void

    var body: some View {
        if schedules.isEmpty {
            Text(emptyText)
                .font(.system(size: 14))
                .foregroundStyle(HuiyiTheme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 4)
        } else {
            VStack(spacing: 0) {
                ForEach(Array(schedules.enumerated()), id: \.element.id) { index, schedule in
                    ScheduleSmartRow(schedule: schedule, onStart: onStart, onEdit: onEdit, onDelete: onDelete)
                    if index < schedules.count - 1 {
                        Divider().padding(.leading, 52)
                    }
                }
            }
        }
    }
}

private struct ScheduleSmartRow: View {
    let schedule: ScheduledMeeting
    let onStart: (ScheduledMeeting) -> Void
    let onEdit: (ScheduledMeeting) -> Void
    let onDelete: (ScheduledMeeting) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 3) {
                Image(systemName: schedule.isOverdue() ? "clock.badge.exclamationmark" : "calendar")
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(schedule.isOverdue() ? Color(red: 0.702, green: 0.239, blue: 0.129) : HuiyiTheme.brand)
            }
            .frame(width: 40, height: 40)
            .background(schedule.isOverdue() ? Color(red: 1.000, green: 0.941, blue: 0.918) : HuiyiTheme.brandSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            VStack(alignment: .leading, spacing: 6) {
                Text(schedule.title)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(HuiyiTheme.ink)
                    .lineLimit(2)
                Text(schedule.time)
                    .font(.system(size: 13))
                    .foregroundStyle(HuiyiTheme.muted)
                    .lineLimit(2)
                if !schedule.note.isEmpty {
                    Text(schedule.note)
                        .font(.system(size: 12))
                        .foregroundStyle(HuiyiTheme.muted.opacity(0.78))
                        .lineLimit(2)
                }
                HStack(spacing: 8) {
                    Button {
                        onStart(schedule)
                    } label: {
                        Label(schedule.isOverdue() ? "补会" : "记录", systemImage: "mic.fill")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(schedule.isFinished() ? HuiyiTheme.muted.opacity(0.45) : HuiyiTheme.brand, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(schedule.isFinished())
                    Button("编辑") { onEdit(schedule) }
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(HuiyiTheme.brand)
                        .buttonStyle(.plain)
                    Button("删除") { onDelete(schedule) }
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(schedule.isFinished() ? HuiyiTheme.muted.opacity(0.5) : HuiyiTheme.danger)
                        .buttonStyle(.plain)
                        .disabled(schedule.isFinished())
                }
            }
        }
        .padding(.vertical, 12)
    }
}

private struct LegacySchedulesView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = SchedulesViewModel()
    @State private var showingEditor = false
    @State private var editingSchedule: ScheduledMeeting?
    @State private var selectedFilter: ScheduleDateFilter = .all
    @State private var customDateRange = ScheduleDateRange()
    @State private var showingCustomDateRange = false
    @State private var pendingDeleteSchedule: ScheduledMeeting?

    var body: some View {
        NavigationStack {
            List {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }

                Section("筛选") {
                    Picker("日期", selection: $selectedFilter) {
                        ForEach(ScheduleDateFilter.allCases) { filter in
                            Text(filter.title).tag(filter)
                        }
                    }
                    .pickerStyle(.segmented)
                    if selectedFilter == .custom {
                        Button {
                            showingCustomDateRange = true
                        } label: {
                            HStack {
                                Text("自定义范围")
                                Spacer()
                                Text(customDateRange.label)
                                    .foregroundStyle(HuiyiTheme.textSecondary)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }

                Section("今日会议") {
                    scheduleRows(todayFilteredSchedules)
                }

                if !otherFilteredSchedules.isEmpty {
                    Section("其他预约") {
                        scheduleRows(otherFilteredSchedules)
                    }
                } else if todayFilteredSchedules.isEmpty {
                    Section("筛选结果") {
                        Text("没有符合筛选的预约")
                            .foregroundStyle(HuiyiTheme.textSecondary)
                    }
                }
            }
            .navigationTitle("预约会议")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("返回") { router.go(.home) }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        editingSchedule = nil
                        showingEditor = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showingEditor) {
                ScheduleEditorView(schedule: editingSchedule) { draft in
                    await viewModel.save(draft, session: session)
                }
            }
            .confirmationDialog(
                "删除预约会议？",
                isPresented: Binding(
                    get: { pendingDeleteSchedule != nil },
                    set: { if !$0 { pendingDeleteSchedule = nil } }
                ),
                titleVisibility: .visible
            ) {
                Button("删除", role: .destructive) {
                    if let pendingDeleteSchedule {
                        Task { await viewModel.delete(pendingDeleteSchedule, session: session) }
                    }
                    pendingDeleteSchedule = nil
                }
                Button("取消", role: .cancel) {
                    pendingDeleteSchedule = nil
                }
            } message: {
                Text("删除后该预约会从 App 内列表移除；若已同步云端，也会同步删除云端预约。已结束会议不支持删除。")
            }
            .sheet(item: Binding(
                get: { session.reminderSchedule },
                set: { if $0 == nil { session.dismissScheduleReminder() } }
            )) { schedule in
                ScheduleReminderView(schedule: schedule) {
                    if let meeting = session.consumeScheduleReminderForRecording() {
                        router.startRecording(schedule: meeting)
                    }
                } onLater: {
                    session.snoozeScheduleReminder()
                } onDismiss: {
                    session.dismissScheduleReminder()
                }
            }
            .refreshable { await viewModel.load(session: session) }
            .sheet(isPresented: $showingCustomDateRange) {
                ScheduleDateRangePickerView(range: customDateRange) { range in
                    customDateRange = range
                    selectedFilter = range.isActive ? .custom : .all
                }
            }
            .task {
                if viewModel.schedules.isEmpty {
                    await viewModel.load(session: session)
                }
                session.checkScheduleReminders(schedules: viewModel.schedules)
            }
        }
    }

    @ViewBuilder
    private func scheduleRows(_ schedules: [ScheduledMeeting]) -> some View {
        if schedules.isEmpty {
            Text("暂无预约")
                .foregroundStyle(HuiyiTheme.textSecondary)
        } else {
            ForEach(schedules) { schedule in
                VStack(alignment: .leading, spacing: 8) {
                    Text(schedule.title)
                        .font(.headline)
                        .lineLimit(2)
                    Text(schedule.time)
                        .font(.subheadline)
                        .foregroundStyle(HuiyiTheme.textSecondary)
                    if !schedule.note.isEmpty {
                        Text(schedule.note)
                            .font(.footnote)
                            .foregroundStyle(HuiyiTheme.textSecondary)
                            .lineLimit(2)
                    }
                    HStack {
                        Button {
                            router.startRecording(schedule: schedule)
                        } label: {
                            Label(schedule.isOverdue() ? "补会" : "记录", systemImage: "mic")
                        }
                        .disabled(schedule.isFinished())
                        Spacer()
                        Menu {
                            Button("编辑") {
                                editingSchedule = schedule
                                showingEditor = true
                            }
                            Button("删除", role: .destructive) {
                                pendingDeleteSchedule = schedule
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                    .font(.subheadline)
                }
                .padding(.vertical, 4)
            }
        }
    }

    private var filteredSchedules: [ScheduledMeeting] {
        viewModel.filteredSchedules(selectedFilter, customRange: customDateRange)
    }

    private var todayFilteredSchedules: [ScheduledMeeting] {
        filteredSchedules.filter { $0.isTodaySchedule }
    }

    private var otherFilteredSchedules: [ScheduledMeeting] {
        filteredSchedules.filter { !$0.isTodaySchedule }
    }
}

@MainActor
final class SchedulesViewModel: ObservableObject {
    @Published private(set) var schedules: [ScheduledMeeting] = []
    @Published var errorMessage: String?

    func filteredSchedules(_ filter: ScheduleDateFilter, customRange: ScheduleDateRange) -> [ScheduledMeeting] {
        schedules
            .filter { filter.matches($0.startAtMillis ?? $0.createdAtMillis, customRange: customRange) }
            .sorted { ($0.startAtMillis ?? Int64.max) < ($1.startAtMillis ?? Int64.max) }
    }

    func load(session: AppSession) async {
        errorMessage = nil
        do {
            schedules = try await session.loadCloudBootstrap().schedules.sorted { left, right in
                (left.startAtMillis ?? left.createdAtMillis) < (right.startAtMillis ?? right.createdAtMillis)
            }
            session.checkScheduleReminders(schedules: schedules)
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func save(_ schedule: ScheduledMeeting, session: AppSession) async -> String? {
        errorMessage = nil
        do {
            if hasScheduleConflict(schedule) {
                let message = "该时间已有预约会议，请调整开始时间。"
                errorMessage = message
                return message
            }
            let saved = try await session.saveSchedule(schedule)
            schedules = (schedules.filter { $0.id != saved.id } + [saved]).sorted { left, right in
                (left.startAtMillis ?? left.createdAtMillis) < (right.startAtMillis ?? right.createdAtMillis)
            }
            session.checkScheduleReminders(schedules: schedules)
            return nil
        } catch {
            let message = userMessage(error)
            errorMessage = message
            return message
        }
    }

    func delete(_ schedule: ScheduledMeeting, session: AppSession) async {
        errorMessage = nil
        do {
            try await session.deleteSchedule(schedule.id)
            schedules.removeAll { $0.id == schedule.id }
            session.checkScheduleReminders(schedules: schedules)
        } catch {
            errorMessage = userMessage(error)
        }
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }

    private func hasScheduleConflict(_ schedule: ScheduledMeeting) -> Bool {
        guard let start = schedule.startAtMillis else { return false }
        return schedules.contains { existing in
            existing.id != schedule.id &&
            existing.startAtMillis == start &&
            !existing.isFinished() &&
            !existing.isOverdue()
        }
    }

}

enum ScheduleDateFilter: String, CaseIterable, Identifiable {
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

    static var allCases: [ScheduleDateFilter] {
        [.all, .today, .yesterday, .last7Days, .custom]
    }

    func matches(_ millis: Int64, customRange: ScheduleDateRange) -> Bool {
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

struct ScheduleDateRange {
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

private struct ScheduleDateRangePickerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var start: Date
    @State private var end: Date
    let onApply: (ScheduleDateRange) -> Void

    init(range: ScheduleDateRange, onApply: @escaping (ScheduleDateRange) -> Void) {
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
                        onApply(ScheduleDateRange(start: start, end: end))
                        dismiss()
                    }
                }
            }
        }
    }
}

private extension ScheduledMeeting {
    var isTodaySchedule: Bool {
        guard let millis = startAtMillis else { return false }
        return Calendar.current.isDateInToday(Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }
}

struct ScheduleEditorView: View {
    let schedule: ScheduledMeeting?
    let onSave: (ScheduledMeeting) async -> String?
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var participants: String
    @State private var note: String
    @State private var startAt: Date
    @State private var endAt: Date
    @State private var isSaving = false
    @State private var localError: String?

    init(schedule: ScheduledMeeting?, onSave: @escaping (ScheduledMeeting) async -> String?) {
        self.schedule = schedule
        self.onSave = onSave
        _title = State(initialValue: schedule?.title ?? "")
        _participants = State(initialValue: schedule?.participants ?? "")
        _note = State(initialValue: schedule?.note ?? "")
        let start = schedule?.startAtMillis.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) } ?? Date().addingTimeInterval(3600)
        let end = schedule?.endAtMillis.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) } ?? start.addingTimeInterval(3600)
        _startAt = State(initialValue: start)
        _endAt = State(initialValue: end)
    }

    var body: some View {
        NavigationStack {
            Form {
                if schedule?.isFinished() == true {
                    Section {
                        Text("该会议已结束，仅可查看；修改会被拒绝。")
                            .font(.footnote)
                            .foregroundStyle(HuiyiTheme.danger)
                    }
                } else if schedule?.isOverdue() == true {
                    Section {
                        Text("该会议已逾期，可补会或调整时间。")
                            .font(.footnote)
                            .foregroundStyle(HuiyiTheme.warning)
                    }
                }
                Section("会议信息") {
                    TextField("标题", text: $title)
                    TextField("参会人", text: $participants)
                    TextField("备注", text: $note, axis: .vertical)
                        .lineLimit(2...5)
                }

                Section("时间") {
                    DatePicker("开始", selection: $startAt)
                    DatePicker("结束", selection: $endAt)
                }

                if let localError {
                    Section {
                        ErrorBanner(message: localError)
                    }
                }
            }
            .navigationTitle(schedule == nil ? "新建预约" : "编辑预约")
            .disabled(isSaving || schedule?.isFinished() == true)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                        .disabled(isSaving)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") {
                        Task { await saveAndDismissIfNeeded() }
                    }
                    .disabled(saveDisabled)
                }
            }
        }
    }

    private var saveDisabled: Bool {
        isSaving ||
            schedule?.isFinished() == true ||
            title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func saveAndDismissIfNeeded() async {
        localError = nil
        guard endAt > startAt else {
            localError = "结束时间必须晚于开始时间。"
            return
        }
        isSaving = true
        let error = await onSave(makeSchedule())
        isSaving = false
        if let error {
            localError = error
        } else {
            dismiss()
        }
    }

    private func makeSchedule() -> ScheduledMeeting {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let startMs = Int64(startAt.timeIntervalSince1970 * 1000)
        let endMs = Int64(endAt.timeIntervalSince1970 * 1000)
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return ScheduledMeeting(
            id: schedule?.id ?? "schedule-\(UUID().uuidString)",
            time: "\(formatter.string(from: startAt)) - \(formatter.string(from: endAt))",
            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
            participants: participants.trimmingCharacters(in: .whitespacesAndNewlines),
            note: note.trimmingCharacters(in: .whitespacesAndNewlines),
            durationLabel: durationLabel(startMs: startMs, endMs: endMs),
            reminderLabel: schedule?.reminderLabel ?? "提前 5 分钟提醒",
            startAtMillis: startMs,
            endAtMillis: endMs,
            createdAtMillis: schedule?.createdAtMillis ?? now,
            status: schedule?.status ?? "pending",
            calendarEventId: schedule?.calendarEventId
        )
    }

    private func durationLabel(startMs: Int64, endMs: Int64) -> String {
        let minutes = max(1, (endMs - startMs) / 60_000)
        if minutes >= 60 {
            return "\(minutes / 60) 小时"
        }
        return "\(minutes) 分钟"
    }
}

struct ScheduleReminderView: View {
    let schedule: ScheduledMeeting
    let onStart: () -> Void
    let onLater: () -> Void
    let onDismiss: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("会议即将开始") {
                    Text(schedule.title)
                        .font(.headline)
                    Text(schedule.time)
                        .foregroundStyle(HuiyiTheme.textSecondary)
                    if !schedule.participants.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        LabeledContent("参会人", value: schedule.participants)
                    }
                }
                Section {
                    Button {
                        onStart()
                        dismiss()
                    } label: {
                        Label("开始记录", systemImage: "mic")
                    }
                    Button {
                        onLater()
                        dismiss()
                    } label: {
                        Label("稍后提醒", systemImage: "clock")
                    }
                    Button(role: .cancel) {
                        onDismiss()
                        dismiss()
                    } label: {
                        Label("忽略", systemImage: "xmark")
                    }
                }
            }
            .navigationTitle("会议提醒")
        }
    }
}
