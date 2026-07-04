import SwiftUI

struct MeetingHomeView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = MeetingsViewModel()
    @State private var showingEditor = false
    @State private var editingSchedule: ScheduledMeeting?
    @State private var pendingDeleteSchedule: ScheduledMeeting?
    @State private var pendingDeleteMeeting: MeetingDetail?

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        homeHeader(width: geometry.size.width)
                        if let errorMessage = viewModel.errorMessage {
                            ErrorBanner(message: errorMessage)
                                .padding(.horizontal, horizontalPadding(for: geometry.size.width))
                        }
                        recordHero(width: geometry.size.width)
                            .padding(.horizontal, horizontalPadding(for: geometry.size.width))
                        secondaryActions(width: geometry.size.width)
                            .padding(.horizontal, horizontalPadding(for: geometry.size.width))
                        SmartSectionHeader(
                            title: "今日会议",
                            primaryAction: "全部",
                            onPrimaryAction: { router.go(.schedules) },
                            secondaryAction: "新建",
                            onSecondaryAction: { beginCreatingSchedule() }
                        )
                        .padding(.horizontal, horizontalPadding(for: geometry.size.width))
                        todayMeetingList(width: geometry.size.width)
                            .padding(.horizontal, horizontalPadding(for: geometry.size.width))

                        SmartSectionHeader(
                            title: "最近会议",
                            primaryAction: "全部",
                            onPrimaryAction: { router.go(.meetings) }
                        )
                        .padding(.horizontal, horizontalPadding(for: geometry.size.width))

                        if let processing = session.taskQueue.tasks.first(where: { $0.status == .processing }) {
                            ProcessingPreviewCard(task: processing) {
                                router.openProcessing(taskId: processing.id)
                            }
                            .padding(.horizontal, horizontalPadding(for: geometry.size.width))
                        }

                        recentMeetingList
                            .padding(.horizontal, horizontalPadding(for: geometry.size.width))
                    }
                    .padding(.bottom, 18)
                }
                .refreshable { await viewModel.load(session: session) }
                .scrollIndicators(.hidden)
                .huiyiScreenBackground()
            }
            .navigationBarHidden(true)
            .task {
                if viewModel.meetings.isEmpty {
                    await viewModel.load(session: session)
                }
                while !Task.isCancelled {
                    session.checkScheduleReminders(schedules: viewModel.schedules)
                    try? await Task.sleep(nanoseconds: 30_000_000_000)
                }
            }
            .sheet(item: $editingSchedule) { schedule in
                ScheduleEditorView(schedule: schedule) { draft in
                    do {
                        _ = try await session.saveSchedule(draft)
                        await viewModel.load(session: session)
                        return nil
                    } catch {
                        return userMessage(error)
                    }
                }
            }
            .sheet(isPresented: $showingEditor) {
                ScheduleEditorView(schedule: nil) { draft in
                    do {
                        _ = try await session.saveSchedule(draft)
                        await viewModel.load(session: session)
                        return nil
                    } catch {
                        return userMessage(error)
                    }
                }
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
            .confirmationDialog(
                "删除预约会议？",
                isPresented: Binding(get: { pendingDeleteSchedule != nil }, set: { if !$0 { pendingDeleteSchedule = nil } }),
                titleVisibility: .visible
            ) {
                Button("删除", role: .destructive) {
                    if let schedule = pendingDeleteSchedule {
                        Task { await deleteSchedule(schedule) }
                    }
                    pendingDeleteSchedule = nil
                }
                Button("取消", role: .cancel) { pendingDeleteSchedule = nil }
            } message: {
                Text("删除后不会再提醒这场预约会议。")
            }
            .confirmationDialog(
                "删除会议？",
                isPresented: Binding(get: { pendingDeleteMeeting != nil }, set: { if !$0 { pendingDeleteMeeting = nil } }),
                titleVisibility: .visible
            ) {
                Button("删除", role: .destructive) {
                    if let detail = pendingDeleteMeeting {
                        Task { await deleteMeeting(detail) }
                    }
                    pendingDeleteMeeting = nil
                }
                Button("取消", role: .cancel) { pendingDeleteMeeting = nil }
            } message: {
                Text("删除后该会议的录音、转写结果和知识库索引将不可查看。")
            }
        }
    }

    private func horizontalPadding(for width: CGFloat) -> CGFloat {
        width < 360 ? 14 : (width < 400 ? 16 : 20)
    }

    private func homeHeader(width: CGFloat) -> some View {
        let compact = width < 400
        return ZStack(alignment: .top) {
            HuiyiTheme.headerGradient
            Image("SmartMeetingHeaderBg")
                .resizable()
                .scaledToFill()
                .frame(height: compact ? 198 : 216)
                .frame(maxWidth: .infinity)
                .clipped()
                .opacity(1)
                .frame(maxHeight: .infinity, alignment: .bottom)
            VStack(alignment: .leading, spacing: compact ? 16 : 18) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("鲲穹会纪")
                            .font(.system(size: compact ? 24 : 25, weight: .bold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        Text(todaySchedules.isEmpty ? "今天没有预约会议" : "今天 \(todaySchedules.count) 场会议待处理")
                            .font(.system(size: 13))
                            .foregroundStyle(Color.white.opacity(0.88))
                            .lineLimit(1)
                    }
                    Spacer()
                    Button {
                        router.go(.profile)
                    } label: {
                        Image(systemName: "person.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 40, height: 40)
                            .background(Color.white.opacity(0.18), in: Circle())
                            .overlay(Circle().stroke(Color.white.opacity(0.22), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }

                SmartSearchPill(text: "查询会议记录") {
                    router.go(.search)
                }

                HStack(alignment: .top, spacing: 0) {
                    HomeQuickAction(title: "会议记录", systemImage: "clock", tint: HuiyiTheme.brand) {
                        router.go(.meetings)
                    }
                    HomeQuickAction(title: "会议纪要", systemImage: "doc.text", tint: HuiyiTheme.brandCyan) {
                        router.go(.importFile)
                    }
                    HomeQuickAction(title: "预约会议", systemImage: "calendar", tint: HuiyiTheme.brandPurple) {
                        beginCreatingSchedule()
                    }
                    HomeQuickAction(title: "云端同步", systemImage: "icloud", tint: Color(red: 1.000, green: 0.624, blue: 0.263)) {
                        session.openProfileCloudSync()
                        router.go(.profile)
                    }
                }
            }
            .padding(.horizontal, horizontalPadding(for: width))
            .padding(.top, 24)
        }
        .frame(maxWidth: .infinity)
        .frame(height: compact ? 312 : 334)
    }

    private func recordHero(width: CGFloat) -> some View {
        let compact = width < 400
        return Button {
            router.startRecording()
        } label: {
            SmartGradientPanel(radius: 18, gradient: HuiyiTheme.recordGradient) {
                HStack(spacing: 14) {
                    Image(systemName: "mic.fill")
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 52, height: 52)
                        .background(Color.white.opacity(0.16), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    VStack(alignment: .leading, spacing: 4) {
                        Text("开始记录")
                            .font(.system(size: 23, weight: .bold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        Text("实时转写，结束后生成纪要")
                            .font(.system(size: 13))
                            .foregroundStyle(Color.white.opacity(0.78))
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                    }
                    Spacer()
                    WaveBars(active: true, barCount: 5, maxHeight: 30)
                }
                .padding(.horizontal, compact ? 18 : 20)
            }
            .frame(height: compact ? 96 : 104)
        }
        .buttonStyle(.plain)
    }

    private func secondaryActions(width: CGFloat) -> some View {
        let compact = width < 400
        return HStack(spacing: 14) {
            HomeActionTile(title: "导入文件", subtitle: "音频/视频转写", systemImage: "doc.badge.plus", importStyle: true, compact: compact) {
                router.go(.importFile)
            }
            HomeActionTile(title: "预约会议", subtitle: "设置会议时间", systemImage: "calendar.badge.plus", importStyle: false, compact: compact) {
                beginCreatingSchedule()
            }
        }
    }

    private func todayMeetingList(width: CGFloat) -> some View {
        let meetings = Array(todaySchedules.prefix(2))
        return VStack(spacing: 12) {
            if meetings.isEmpty {
                SmartCard(radius: 16, padding: 16) {
                    VStack(spacing: 10) {
                        Image(systemName: "calendar")
                            .font(.system(size: 38, weight: .semibold))
                            .foregroundStyle(Color(red: 0.718, green: 0.784, blue: 0.937))
                        Text("今天还没有预约会议")
                            .font(.system(size: 13))
                            .foregroundStyle(HuiyiTheme.muted)
                    }
                    .frame(maxWidth: .infinity, minHeight: width < 400 ? 78 : 86)
                }
            } else {
                ForEach(meetings) { schedule in
                    TodayScheduleCard(schedule: schedule) {
                        editingSchedule = schedule
                    } onStart: {
                        router.startRecording(schedule: schedule)
                    } onDelete: {
                        pendingDeleteSchedule = schedule
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var recentMeetingList: some View {
        if viewModel.isLoading {
            SmartCard(radius: 16, padding: 16) {
                HStack(spacing: 12) {
                    ProgressView()
                    Text("正在同步")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(HuiyiTheme.muted)
                }
            }
        } else if viewModel.visibleMeetings.isEmpty && session.taskQueue.tasks.first(where: { $0.status == .processing }) == nil {
            SmartCard(radius: 16, padding: 16) {
                HStack(spacing: 12) {
                    SmartGradientIcon(systemImage: "doc.text", tint: Color(red: 0.718, green: 0.784, blue: 0.937))
                    Text("暂无会议")
                        .font(.system(size: 14))
                        .foregroundStyle(HuiyiTheme.muted)
                }
            }
        } else {
            VStack(spacing: 12) {
                ForEach(viewModel.visibleMeetings.prefix(3), id: \.task.id) { detail in
                    MeetingCard(detail: detail, selectable: false, selected: false) {
                        if detail.task.canOpenProcessingPage {
                            session.enqueueTask(detail.task)
                            router.openProcessing(taskId: detail.task.id)
                        } else {
                            router.openMeeting(detail.task.remoteTaskId ?? detail.task.id)
                        }
                    } onDelete: {
                        pendingDeleteMeeting = detail
                    }
                }
            }
        }
    }

    private var todaySchedules: [ScheduledMeeting] {
        viewModel.schedules
            .filter { $0.isToday() && !$0.isFinished() }
            .sorted { ($0.startAtMillis ?? Int64.max) < ($1.startAtMillis ?? Int64.max) }
    }

    private func deleteSchedule(_ schedule: ScheduledMeeting) async {
        do {
            try await session.deleteSchedule(schedule.id)
            await viewModel.load(session: session)
        } catch {
            viewModel.errorMessage = userMessage(error)
        }
    }

    private func deleteMeeting(_ detail: MeetingDetail) async {
        do {
            try await session.deleteMeetingTask(detail.task)
        } catch {
            viewModel.errorMessage = userMessage(error)
        }
        await viewModel.load(session: session)
    }

    private func beginCreatingSchedule() {
        editingSchedule = nil
        showingEditor = true
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }
}

private struct HomeQuickAction: View {
    let title: String
    let systemImage: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: systemImage)
                    .font(.system(size: 23, weight: .semibold))
                    .foregroundStyle(tint)
                    .frame(width: 50, height: 50)
                    .background(Color.white.opacity(0.78), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(Color.white.opacity(0.68), lineWidth: 1)
                    )
                    .shadow(color: HuiyiTheme.brandDark.opacity(0.08), radius: 6, x: 0, y: 2)
                Text(title)
                    .font(.system(size: 13))
                    .foregroundStyle(HuiyiTheme.brandDark)
                    .lineLimit(1)
                    .minimumScaleFactor(0.76)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
    }
}

private struct HomeActionTile: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let importStyle: Bool
    let compact: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 14) {
                Image(systemName: systemImage)
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(importStyle ? HuiyiTheme.brand : HuiyiTheme.brandPurple)
                    .frame(width: compact ? 52 : 56, height: compact ? 52 : 56)
                    .background(importStyle ? Color(red: 0.918, green: 0.961, blue: 1.000) : Color(red: 0.941, green: 0.914, blue: 1.000), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(spacing: 8) {
                    Text(title)
                        .font(.system(size: compact ? 18 : 19, weight: .bold))
                        .foregroundStyle(HuiyiTheme.brand)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Text(subtitle)
                        .font(.system(size: 13))
                        .foregroundStyle(HuiyiTheme.muted)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: compact ? 150 : 156)
            .background(
                LinearGradient(
                    colors: importStyle
                        ? [Color(red: 0.957, green: 0.984, blue: 1.000).opacity(0.94), Color(red: 0.941, green: 0.973, blue: 1.000).opacity(0.92)]
                        : [Color(red: 0.969, green: 0.957, blue: 1.000).opacity(0.97), Color(red: 0.945, green: 0.914, blue: 1.000).opacity(0.94)],
                    startPoint: .leading,
                    endPoint: .trailing
                ),
                in: RoundedRectangle(cornerRadius: 16, style: .continuous)
            )
        }
        .buttonStyle(.plain)
    }
}

private struct TodayScheduleCard: View {
    let schedule: ScheduledMeeting
    let onEdit: () -> Void
    let onStart: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Button(action: onEdit) {
            HStack(spacing: 10) {
                Text(schedule.compactStartTimeLabel)
                    .font(.system(size: schedule.compactStartTimeLabel.count > 4 ? 13 : 14, weight: .bold))
                    .foregroundStyle(schedule.isOverdue() ? Color(red: 0.702, green: 0.239, blue: 0.129) : HuiyiTheme.brand)
                    .lineLimit(1)
                    .minimumScaleFactor(0.74)
                    .frame(width: 54, height: 36)
                    .background(schedule.isOverdue() ? Color(red: 1.000, green: 0.941, blue: 0.918) : HuiyiTheme.brandSoft, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(schedule.title)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                        .lineLimit(1)
                    Text(schedule.participantLine)
                        .font(.system(size: 13))
                        .foregroundStyle(HuiyiTheme.muted)
                        .lineLimit(1)
                }
                Spacer()
                Button(action: onDelete) {
                    Image(systemName: "trash")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(HuiyiTheme.muted.opacity(0.62))
                        .frame(width: 30, height: 30)
                }
                .buttonStyle(.plain)
                Button(action: onStart) {
                    Text(schedule.isOverdue() ? "补会" : "记录")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 54, height: 32)
                        .background(HuiyiTheme.primaryGradient, in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(Color.white.opacity(0.96), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(Color.white.opacity(0.78), lineWidth: 1)
            )
            .shadow(color: HuiyiTheme.brandDark.opacity(0.08), radius: 8, x: 0, y: 3)
        }
        .buttonStyle(.plain)
    }
}

struct MeetingCard: View {
    let detail: MeetingDetail
    var selectable = false
    var selected = false
    var onSelectionChange: (() -> Void)?
    let action: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Button {
            selectable ? (onSelectionChange?()) : action()
        } label: {
            SmartCard(radius: 16, padding: 16, color: selected ? HuiyiTheme.brandSoft.opacity(0.70) : Color.white.opacity(0.96), borderColor: selected ? HuiyiTheme.brand : Color.white.opacity(0.72)) {
                HStack(alignment: .top, spacing: 10) {
                    if selectable {
                        Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundStyle(selected ? HuiyiTheme.brand : HuiyiTheme.muted)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Text(detail.task.title)
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(HuiyiTheme.ink)
                            .lineLimit(1)
                        Text(detail.displaySubtitle)
                            .font(.system(size: 14))
                            .foregroundStyle(HuiyiTheme.muted)
                            .lineLimit(2)
                    }
                    Spacer(minLength: 8)
                    SmartStatusBadge(text: detail.task.status.shortLabel, color: detail.task.status.badgeColor)
                    if !selectable {
                        Button(action: onDelete) {
                            Image(systemName: "trash")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundStyle(HuiyiTheme.danger)
                                .frame(width: 34, height: 34)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .buttonStyle(.plain)
    }
}

private struct ProcessingPreviewCard: View {
    let task: MeetingTask
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            SmartCard(radius: 16, padding: 18, color: Color.white.opacity(0.94), borderColor: Color(red: 0.847, green: 0.871, blue: 1.000)) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("会议处理中")
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(HuiyiTheme.ink)
                        Text("\(task.title) · \(task.progressLabel ?? task.status.displayName)")
                            .font(.system(size: 13))
                            .foregroundStyle(HuiyiTheme.muted)
                            .lineLimit(2)
                        Text("点击查看进度或终止任务")
                            .font(.system(size: 12))
                            .foregroundStyle(HuiyiTheme.brand)
                    }
                    Spacer()
                    SmartStatusBadge(text: "\(Int(task.progressPercent.rounded()))%", color: HuiyiTheme.brand)
                }
                ProgressView(value: min(100.0, max(0.0, task.progressPercent)) / 100.0)
                    .tint(HuiyiTheme.brand)
            }
        }
        .buttonStyle(.plain)
    }
}

private extension ScheduledMeeting {
    var compactStartTimeLabel: String {
        if isOverdue() { return "逾期" }
        if let startAtMillis {
            return Self.timeFormatter.string(from: Date(timeIntervalSince1970: TimeInterval(startAtMillis) / 1000))
        }
        if let range = time.range(of: #"\d{1,2}:\d{2}"#, options: .regularExpression) {
            return String(time[range])
        }
        return String(time.prefix(5))
    }

    var participantLine: String {
        let clean = participants.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty || clean == "待补充参会人" || clean == "未填写参会人" {
            return "未填写参会人"
        }
        return clean
    }

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "HH:mm"
        return formatter
    }()
}

extension MeetingDetail {
    var displaySubtitle: String {
        let dateLabel = task.createdAtMillis.meetingDateLabel
        let suffix = durationLabel
        return suffix.isEmpty ? dateLabel : "\(dateLabel)，\(suffix)"
    }

    private var durationLabel: String {
        if let sizeLabel = task.sizeLabel?.trimmingCharacters(in: .whitespacesAndNewlines), !sizeLabel.isEmpty {
            return sizeLabel
        }
        if let file {
            return ByteCountFormatter.string(fromByteCount: file.sizeBytes, countStyle: .file)
        }
        return task.status == .finished ? "会议纪要" : task.status.displayName
    }
}

extension Int64 {
    var meetingDateLabel: String {
        guard self > 0 else { return "" }
        let date = Date(timeIntervalSince1970: TimeInterval(self) / 1000)
        let calendar = Calendar.current
        let timeFormatter = DateFormatter()
        timeFormatter.locale = Locale(identifier: "zh_CN")
        timeFormatter.dateFormat = "HH:mm"
        let time = timeFormatter.string(from: date)
        if calendar.isDateInToday(date) {
            return "今天 \(time)"
        }
        if calendar.isDateInYesterday(date) {
            return "昨天 \(time)"
        }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = calendar.component(.year, from: date) == calendar.component(.year, from: Date()) ? "MM-dd HH:mm" : "yyyy-MM-dd HH:mm"
        return formatter.string(from: date)
    }
}

extension ClientMeetingTaskStatus {
    var shortLabel: String {
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

    var badgeColor: Color {
        switch self {
        case .finished: return HuiyiTheme.success
        case .failed, .canceled: return HuiyiTheme.danger
        case .processing: return HuiyiTheme.brand
        case .waitingRetry: return HuiyiTheme.warning
        default: return HuiyiTheme.muted
        }
    }
}
