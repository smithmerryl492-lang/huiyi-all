import SwiftUI

struct ProcessingView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = ProcessingViewModel()
    @State private var showingDeleteFailedConfirmation = false
    @State private var showingCancelConfirmation = false

    var body: some View {
        NavigationStack {
            SmartScreenScaffold(title: "生成会议纪要", onBack: { closeProcessingPage() }) {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }
                if let statusMessage = viewModel.statusMessage {
                    HStack(spacing: 9) {
                        Image(systemName: "checkmark.circle.fill")
                        Text(statusMessage)
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(HuiyiTheme.success)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(HuiyiTheme.success.opacity(0.10), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }

                if let task = viewModel.selectedTask {
                    currentTaskCard(task)
                    actionCard(task)
                } else {
                    SmartCard(radius: 16, padding: 16) {
                        EmptyStateView(title: "暂无处理任务", message: "待处理、可继续或失败的任务会保留在这里。", systemImage: "checklist")
                    }
                }

                let queued = session.taskQueue.visibleQueuedTasks(excluding: viewModel.selectedTask?.id)
                if !queued.isEmpty {
                    SmartInfoBlock(title: "后续队列") {
                        VStack(spacing: 12) {
                            ForEach(queued) { task in
                                QueueTaskRow(task: task, hint: queueHint(for: task)) {
                                    viewModel.open(taskId: task.id, session: session)
                                }
                            }
                        }
                    }
                }
            }
            .confirmationDialog("删除任务？", isPresented: $showingDeleteFailedConfirmation, titleVisibility: .visible) {
                Button("删除", role: .destructive) {
                    viewModel.removeFailedOrCanceled(session: session)
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("删除后该任务的文件、转写结果和知识库索引将不可查看。")
            }
            .confirmationDialog("终止处理？", isPresented: $showingCancelConfirmation, titleVisibility: .visible) {
                Button("终止任务", role: .destructive) {
                    Task { await viewModel.cancelProcessing(session: session) }
                }
                Button("继续处理", role: .cancel) {}
            } message: {
                Text("当前文件正在生成会议纪要，终止后需要重新开始处理。")
            }
            .task {
                viewModel.open(taskId: router.selectedTaskId, session: session)
                if router.autoStartProcessing {
                    router.autoStartProcessing = false
                    await viewModel.startOrContinue(session: session, router: router)
                }
            }
            .onChange(of: viewModel.completedRemoteTaskId) { completed in
                if let completed {
                    router.openMeeting(completed)
                }
            }
        }
    }

    private func currentTaskCard(_ task: MeetingTask) -> some View {
        SmartGradientPanel(radius: 28, gradient: HuiyiTheme.primaryGradient) {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(taskStatusTitle(task.status))
                            .font(.system(size: 22, weight: .bold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        Text(task.title)
                            .font(.system(size: 14))
                            .foregroundStyle(Color.white.opacity(0.84))
                            .lineLimit(3)
                    }
                    Spacer()
                    Text("\(Int(task.progressPercent.rounded()))%")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Color.white.opacity(0.16), in: Capsule())
                }

                ProgressView(value: min(100.0, max(0.0, task.progressPercent)) / 100.0)
                    .tint(.white)

                Text(task.progressLabel ?? statusText(task.status))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color.white.opacity(0.88))
                    .fixedSize(horizontal: false, vertical: true)

                if viewModel.isPolling {
                    Text("正在同步处理进度")
                        .font(.system(size: 12))
                        .foregroundStyle(Color.white.opacity(0.76))
                }

                if task.status == .finished {
                    HStack(spacing: 8) {
                        SmartStatusBadge(text: "摘要", color: HuiyiTheme.brandDark, background: Color.white.opacity(0.72))
                        SmartStatusBadge(text: "来源", color: HuiyiTheme.brandDark, background: Color.white.opacity(0.72))
                        SmartStatusBadge(text: "待办", color: HuiyiTheme.brandDark, background: Color.white.opacity(0.72))
                    }
                }
            }
            .padding(20)
        }
    }

    @ViewBuilder
    private func actionCard(_ task: MeetingTask) -> some View {
        SmartCard(radius: 18, padding: 16) {
            if task.status == .failed || task.status == .waitingRetry || task.status == .waitingProcess || task.status == .canceled {
                PrimaryActionButton(title: primaryActionTitle(for: task), systemImage: "play.circle", isLoading: viewModel.isRunning) {
                    Task {
                        await viewModel.startOrContinue(session: session, router: router)
                    }
                }
            }
            if task.status == .processing {
                SecondaryActionButton(title: "后台处理", systemImage: "arrow.down.right.and.arrow.up.left") {
                    closeProcessingPage()
                }
                Button {
                    showingCancelConfirmation = true
                } label: {
                    Label(viewModel.isCanceling ? "正在终止" : "终止处理", systemImage: "stop.circle")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(HuiyiTheme.danger, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(viewModel.isCanceling)
            }
            if task.status == .failed || task.status == .canceled {
                Button {
                    showingDeleteFailedConfirmation = true
                } label: {
                    Label(task.status == .canceled ? "删除已终止任务" : "删除失败任务", systemImage: "trash")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(HuiyiTheme.danger)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(Color(red: 1.000, green: 0.941, blue: 0.929), in: Capsule())
                }
                .buttonStyle(.plain)
            }
            if task.status == .finished || viewModel.lastCompletedRemoteTaskId != nil {
                PrimaryActionButton(title: "查看详情", systemImage: "doc.text") {
                    viewModel.viewCompletedDetail()
                }
                .disabled(viewModel.lastCompletedRemoteTaskId == nil)
                .opacity(viewModel.lastCompletedRemoteTaskId == nil ? 0.55 : 1)
            }
        }
    }

    private func taskStatusTitle(_ status: ClientMeetingTaskStatus) -> String {
        switch status {
        case .localSaved: return "已保存本地"
        case .waitingProcess: return "待处理"
        case .processing: return "会议处理中"
        case .waitingRetry: return "可继续处理"
        case .failed: return "处理失败"
        case .canceled: return "已终止"
        case .finished: return "处理完成"
        }
    }

    private func statusText(_ status: ClientMeetingTaskStatus) -> String {
        switch status {
        case .localSaved: return "已保存本地"
        case .waitingProcess: return "待处理"
        case .processing: return "正在处理"
        case .waitingRetry: return "可继续处理"
        case .failed: return "处理失败"
        case .canceled: return "已终止"
        case .finished: return "已完成"
        }
    }

    private func queueHint(for task: MeetingTask) -> String {
        if task.status == .waitingProcess, task.progressStage != "waiting_retry" {
            return "\(statusText(task.status)) · 当前任务完成后自动处理"
        }
        return "\(statusText(task.status)) · 需手动继续"
    }

    private func primaryActionTitle(for task: MeetingTask) -> String {
        switch task.status {
        case .failed, .canceled:
            return "重新尝试"
        case .waitingRetry:
            return "继续处理"
        default:
            return "开始处理"
        }
    }

    private func closeProcessingPage() {
        if let task = viewModel.selectedTask, task.status == .failed || task.status == .canceled {
            viewModel.removeFailedOrCanceled(session: session)
        }
        router.backFromProcessing()
    }
}

private struct QueueTaskRow: View {
    let task: MeetingTask
    let hint: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 12) {
                SmartGradientIcon(systemImage: task.source == .recording ? "mic" : "doc.badge.plus", tint: task.status.badgeColor, size: 42)
                VStack(alignment: .leading, spacing: 5) {
                    Text(task.title)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                        .lineLimit(2)
                    Text(hint)
                        .font(.system(size: 12))
                        .foregroundStyle(HuiyiTheme.muted)
                        .lineLimit(2)
                }
                Spacer()
                SmartStatusBadge(text: task.status.shortLabel, color: task.status.badgeColor)
            }
            .padding(14)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(HuiyiTheme.line, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
