import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct FileImportView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = FileImportViewModel()
    @State private var showingPicker = false
    @State private var pendingDeleteTask: MeetingTask?

    var body: some View {
        NavigationStack {
            SmartScreenScaffold(title: "导入文件", onBack: { router.go(.home) }) {
                SmartInfoBlock(title: "识别语言") {
                    SmartSegmentedPicker(
                        items: RecognitionLanguage.allCases.map(LanguageOption.init),
                        selection: Binding(
                            get: { LanguageOption(viewModel.selectedLanguage) },
                            set: { viewModel.selectedLanguage = $0.language }
                        ),
                        title: { $0.language.displayName }
                    )
                    Text("语言会在选中文件创建任务时固定，后续切换不会影响已排队任务。")
                        .font(.system(size: 13))
                        .foregroundStyle(HuiyiTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }

                uploadPanel

                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }

                if let task = viewModel.createdTask {
                    SmartInfoBlock(title: "已加入队列") {
                        TaskQueueCard(task: task) {
                            router.openProcessing(taskId: task.id)
                        } onDelete: {
                            pendingDeleteTask = task
                        }
                        SecondaryActionButton(title: "去处理", systemImage: "play.circle") {
                            router.openProcessing(taskId: task.id)
                        }
                    }
                }

                if let processingTask = session.taskQueue.activeTask {
                    SmartInfoBlock(title: "正在处理") {
                        ProcessingTaskCard(task: processingTask) {
                            router.openProcessing(taskId: processingTask.id)
                        }
                    }
                }

                SmartInfoBlock(title: queuedImportTasks.isEmpty ? "待处理文件" : "待处理文件（\(queuedImportTasks.count)）") {
                    if queuedImportTasks.isEmpty {
                        HStack(spacing: 12) {
                            SmartGradientIcon(systemImage: "doc.badge.plus", tint: Color(red: 0.718, green: 0.784, blue: 0.937))
                            Text("还没有待处理文件")
                                .font(.system(size: 14))
                                .foregroundStyle(HuiyiTheme.muted)
                        }
                    } else {
                        VStack(spacing: 12) {
                            ForEach(queuedImportTasks) { task in
                                TaskQueueCard(task: task) {
                                    router.openProcessing(taskId: task.id)
                                } onDelete: {
                                    pendingDeleteTask = task
                                }
                            }
                        }
                    }
                    PrimaryActionButton(title: session.taskQueue.activeTask == nil ? "开始处理" : "加入待处理", systemImage: "play.circle") {
                        if let activeTask = session.taskQueue.activeTask {
                            router.openProcessing(taskId: activeTask.id)
                        } else if let first = queuedImportTasks.first(where: { $0.status == .waitingProcess }) {
                            router.openProcessing(taskId: first.id, autoStart: true)
                        }
                    }
                    .disabled(!queuedImportTasks.contains { $0.status == .waitingProcess })
                    .opacity(queuedImportTasks.contains { $0.status == .waitingProcess } ? 1 : 0.55)
                }
            }
            .sheet(isPresented: $showingPicker) {
                DocumentPickerView(contentTypes: DocumentImportService().supportedContentTypes) { url in
                    viewModel.handlePickedFile(url, session: session)
                }
            }
            .confirmationDialog(
                "删除导入任务？",
                isPresented: Binding(get: { pendingDeleteTask != nil }, set: { if !$0 { pendingDeleteTask = nil } }),
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
                Text("删除后该文件不会继续处理；本机队列中的失败或待处理记录也会移除。")
            }
        }
    }

    private var uploadPanel: some View {
        SmartCard(radius: 16, padding: 18) {
            Text("本地文件")
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(HuiyiTheme.ink)
            if let importedFile = viewModel.importedFile {
                HStack(spacing: 12) {
                    SmartGradientIcon(systemImage: "doc", tint: HuiyiTheme.brand)
                    VStack(alignment: .leading, spacing: 5) {
                        Text(importedFile.displayName)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(HuiyiTheme.ink)
                            .lineLimit(2)
                        Text(importedFile.sizeLabel)
                            .font(.system(size: 13))
                            .foregroundStyle(HuiyiTheme.muted)
                    }
                }
            } else {
                EmptyStateView(
                    title: "选择会议文件",
                    message: "支持 mp3、m4a、wav、aac、mp4、mov，最大 500MB。",
                    systemImage: "doc.badge.plus"
                )
            }
            PrimaryActionButton(title: "选择文件", systemImage: "folder", isLoading: viewModel.isImporting) {
                showingPicker = true
            }
        }
    }

    private var queuedImportTasks: [MeetingTask] {
        session.taskQueue.tasks
            .filter { task in
                task.source == .importFile &&
                    (task.status == .waitingProcess || task.status == .waitingRetry || task.status == .failed)
            }
            .sorted { $0.createdAtMillis < $1.createdAtMillis }
    }
}

private struct TaskQueueCard: View {
    let task: MeetingTask
    let onOpen: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(alignment: .top, spacing: 12) {
                SmartGradientIcon(systemImage: "doc.badge.plus", tint: task.status == .failed ? HuiyiTheme.danger : HuiyiTheme.brand, size: 42)
                VStack(alignment: .leading, spacing: 5) {
                    Text(task.title)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                        .lineLimit(2)
                    Text([task.sizeLabel, task.errorMessage ?? task.progressLabel].compactMap { $0 }.joined(separator: " · "))
                        .font(.system(size: 12))
                        .foregroundStyle(task.status == .failed ? HuiyiTheme.danger : HuiyiTheme.muted)
                        .lineLimit(2)
                    Text(task.requiresExplicitRetry ? "点击查看或继续处理" : "点击查看处理")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(HuiyiTheme.brand)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 8) {
                    SmartStatusBadge(text: task.status.shortLabel, color: task.status.badgeColor)
                    Button(action: onDelete) {
                        Image(systemName: "trash")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(HuiyiTheme.danger)
                            .frame(width: 34, height: 34)
                    }
                    .buttonStyle(.plain)
                    .disabled(task.status == .processing)
                }
            }
            .padding(14)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(HuiyiTheme.line, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

private struct ProcessingTaskCard: View {
    let task: MeetingTask
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(task.title)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(HuiyiTheme.ink)
                            .lineLimit(2)
                        Text(task.progressLabel ?? "处理中")
                            .font(.system(size: 12))
                            .foregroundStyle(HuiyiTheme.muted)
                    }
                    Spacer()
                    SmartStatusBadge(text: "\(Int(task.progressPercent.rounded()))%", color: HuiyiTheme.brand)
                }
                ProgressView(value: min(100.0, max(0.0, task.progressPercent)) / 100.0)
                    .tint(HuiyiTheme.brand)
                Text("点击查看进度或终止任务")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(HuiyiTheme.brand)
            }
            .padding(14)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Color(red: 0.847, green: 0.871, blue: 1.000), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

private struct LanguageOption: Identifiable, Equatable {
    let language: RecognitionLanguage
    var id: String { language.rawValue }

    init(_ language: RecognitionLanguage) {
        self.language = language
    }
}

private struct DocumentPickerView: UIViewControllerRepresentable {
    let contentTypes: [UTType]
    let onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: contentTypes, asCopy: true)
        controller.delegate = context.coordinator
        controller.allowsMultipleSelection = false
        return controller
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick)
    }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void

        init(onPick: @escaping (URL) -> Void) {
            self.onPick = onPick
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            onPick(url)
        }
    }
}
