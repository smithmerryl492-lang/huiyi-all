import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct VoiceprintsView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = VoiceprintsViewModel()
    @State private var showingImporter = false
    @State private var showingRecorder = false

    var body: some View {
        NavigationStack {
            SmartScreenScaffold(
                title: "声纹库",
                onBack: { router.go(.profile) },
                trailing: {
                    SmartRoundIconButton(
                        systemImage: viewModel.isBusy ? "hourglass" : "arrow.clockwise",
                        accessibilityLabel: "刷新",
                        tint: HuiyiTheme.brand
                    ) {
                        Task { await viewModel.load(session: session) }
                    }
                    .disabled(session.currentUser == nil || viewModel.isBusy)
                }
            ) {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }
                if let statusMessage = viewModel.statusMessage {
                    HStack(spacing: 9) {
                        Image(systemName: viewModel.isBusy ? "hourglass" : "checkmark.circle.fill")
                        Text(statusMessage)
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(viewModel.isBusy ? HuiyiTheme.warning : HuiyiTheme.success)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }

                if session.currentUser == nil {
                    SmartInfoBlock(title: "登录后使用") {
                        Text("声纹档案按账号保存。登录后可以提前录入、删除、停用，并在新会议中自动识别说话人。")
                            .font(.system(size: 14))
                            .foregroundStyle(HuiyiTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                } else {
                    enrollmentPanel
                    profilesPanel
                }
            }
            .sheet(isPresented: $showingImporter) {
                VoiceprintDocumentPicker(contentTypes: DocumentImportService().supportedContentTypes) { url in
                    Task { await viewModel.importSample(url, session: session) }
                }
            }
            .sheet(isPresented: $showingRecorder) {
                VoiceprintSampleRecorderView(name: viewModel.enrollmentName) { url in
                    Task {
                        await viewModel.enrollRecordedSample(url, session: session)
                        showingRecorder = false
                    }
                }
            }
            .refreshable {
                if session.currentUser != nil {
                    await viewModel.load(session: session)
                }
            }
            .task {
                if session.currentUser != nil && session.speakerProfiles.isEmpty {
                    await viewModel.load(session: session)
                }
            }
        }
    }

    private var enrollmentPanel: some View {
        SmartInfoBlock(title: "提前录入") {
            Toggle(isOn: $viewModel.consentAccepted) {
                Text("我同意采集并保存本人或已获授权的声纹样本，用于说话人识别；后续可在声纹库停用或删除。")
                    .font(.system(size: 13))
                    .foregroundStyle(HuiyiTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .tint(HuiyiTheme.brand)

            TextField("声纹姓名", text: $viewModel.enrollmentName)
                .font(.system(size: 15))
                .padding(.horizontal, 14)
                .frame(height: 48)
                .textInputAutocapitalization(.never)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(HuiyiTheme.line, lineWidth: 1))

            HStack(spacing: 12) {
                SecondaryActionButton(title: "录音录入", systemImage: "mic.fill") {
                    showingRecorder = true
                }
                .disabled(!viewModel.canEnroll || viewModel.isBusy)
                PrimaryActionButton(title: "导入音频", systemImage: "doc.badge.plus") {
                    showingImporter = true
                }
                .disabled(!viewModel.canEnroll || viewModel.isBusy)
            }
        }
    }

    private var profilesPanel: some View {
        SmartInfoBlock(title: "声纹档案") {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(session.speakerProfiles.count) 个档案")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                    Text("停用后不会参与自动匹配。")
                        .font(.system(size: 13))
                        .foregroundStyle(HuiyiTheme.muted)
                }
                Spacer()
                SmartStatusBadge(text: viewModel.isBusy ? "处理中" : "已同步", color: viewModel.isBusy ? HuiyiTheme.warning : HuiyiTheme.brand)
            }

            if viewModel.isLoading {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("正在加载")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(HuiyiTheme.muted)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 18)
            } else if session.speakerProfiles.isEmpty {
                EmptyStateView(title: "暂无声纹档案", message: "可以先在这里录制或导入一段清晰人声采样。", systemImage: "person.2")
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(session.speakerProfiles.enumerated()), id: \.element.id) { index, profile in
                        VoiceprintProfileRow(
                            profile: profile,
                            isBusy: viewModel.isBusy,
                            onRename: { name in
                                Task { await viewModel.rename(profile, name: name, session: session) }
                            },
                            onToggle: { active in
                                Task { await viewModel.toggle(profile, active: active, session: session) }
                            },
                            onDelete: {
                                Task { await viewModel.delete(profile, session: session) }
                            }
                        )
                        if index < session.speakerProfiles.count - 1 {
                            Divider().padding(.leading, 52)
                        }
                    }
                }
            }
        }
    }
}

private struct LegacyVoiceprintsView: View {
    @EnvironmentObject private var session: AppSession
    @StateObject private var viewModel = VoiceprintsViewModel()
    @State private var showingImporter = false
    @State private var showingRecorder = false

    var body: some View {
        NavigationStack {
            List {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }
                if let statusMessage = viewModel.statusMessage {
                    Label(statusMessage, systemImage: viewModel.isBusy ? "hourglass" : "checkmark.circle")
                        .foregroundStyle(viewModel.isBusy ? HuiyiTheme.warning : HuiyiTheme.success)
                }

                if session.currentUser == nil {
                    Section("登录后使用") {
                        Text("声纹档案按账号保存。登录后可以提前录入、删除、停用，并在新会议中自动识别说话人。")
                            .font(.footnote)
                            .foregroundStyle(HuiyiTheme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                } else {
                    Section("提前录入") {
                        Toggle(isOn: $viewModel.consentAccepted) {
                            Text("我同意采集并保存本人或已获授权的声纹样本，用于说话人识别；后续可在声纹库停用或删除。")
                                .font(.footnote)
                                .foregroundStyle(HuiyiTheme.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        TextField("声纹姓名", text: $viewModel.enrollmentName)
                            .textInputAutocapitalization(.never)
                        HStack {
                            Button {
                                showingRecorder = true
                            } label: {
                                Label("录音录入", systemImage: "mic")
                            }
                            .disabled(!viewModel.canEnroll || viewModel.isBusy)

                            Spacer()

                            Button {
                                showingImporter = true
                            } label: {
                                Label("导入音频", systemImage: "doc.badge.plus")
                            }
                            .disabled(!viewModel.canEnroll || viewModel.isBusy)
                        }
                    }

                    Section("声纹档案") {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("\(session.speakerProfiles.count) 个档案")
                                    .font(.headline)
                                Text("停用后不会参与自动匹配。")
                                    .font(.caption)
                                    .foregroundStyle(HuiyiTheme.textSecondary)
                            }
                            Spacer()
                            Text(viewModel.isBusy ? "处理中" : "已同步")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(viewModel.isBusy ? HuiyiTheme.warning : HuiyiTheme.accent)
                        }

                        if viewModel.isLoading {
                            ProgressView("正在加载")
                        } else if session.speakerProfiles.isEmpty {
                            EmptyStateView(
                                title: "暂无声纹档案",
                                message: "可以先在这里录制或导入一段清晰人声采样。",
                                systemImage: "person.2"
                            )
                            .listRowInsets(EdgeInsets())
                        } else {
                            ForEach(session.speakerProfiles) { profile in
                                VoiceprintProfileRow(
                                    profile: profile,
                                    isBusy: viewModel.isBusy,
                                    onRename: { name in
                                        Task { await viewModel.rename(profile, name: name, session: session) }
                                    },
                                    onToggle: { active in
                                        Task { await viewModel.toggle(profile, active: active, session: session) }
                                    },
                                    onDelete: {
                                        Task { await viewModel.delete(profile, session: session) }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            .navigationTitle("声纹库")
            .toolbar {
                Button {
                    Task { await viewModel.load(session: session) }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(session.currentUser == nil || viewModel.isBusy)
            }
            .sheet(isPresented: $showingImporter) {
                VoiceprintDocumentPicker(contentTypes: DocumentImportService().supportedContentTypes) { url in
                    Task { await viewModel.importSample(url, session: session) }
                }
            }
            .sheet(isPresented: $showingRecorder) {
                VoiceprintSampleRecorderView(name: viewModel.enrollmentName) { url in
                    Task {
                        await viewModel.enrollRecordedSample(url, session: session)
                        showingRecorder = false
                    }
                }
            }
            .refreshable {
                if session.currentUser != nil {
                    await viewModel.load(session: session)
                }
            }
            .task {
                if session.currentUser != nil && session.speakerProfiles.isEmpty {
                    await viewModel.load(session: session)
                }
            }
        }
    }
}

@MainActor
final class VoiceprintsViewModel: ObservableObject {
    @Published var enrollmentName = ""
    @Published var consentAccepted = false
    @Published var isLoading = false
    @Published var isBusy = false
    @Published var statusMessage: String?
    @Published var errorMessage: String?

    private let importService = DocumentImportService()
    var canEnroll: Bool {
        consentAccepted && !enrollmentName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func load(session: AppSession) async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            try await session.refreshSpeakerProfiles()
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func importSample(_ url: URL, session: AppSession) async {
        let name = enrollmentName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        isBusy = true
        errorMessage = nil
        statusMessage = "正在录入声纹"
        defer { isBusy = false }
        do {
            let imported = try importService.copyIntoSandbox(sourceURL: url)
            try await session.enrollSpeakerProfileFromAudio(displayName: name, localFileURL: imported.localFileURL)
            try? FileManager.default.removeItem(at: imported.localFileURL)
            statusMessage = "声纹档案已保存"
            enrollmentName = ""
            consentAccepted = false
        } catch {
            errorMessage = userMessage(error)
            statusMessage = nil
        }
    }

    func enrollRecordedSample(_ url: URL, session: AppSession) async {
        let name = enrollmentName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        isBusy = true
        errorMessage = nil
        statusMessage = "正在保存录音声纹"
        defer { isBusy = false }
        do {
            try await session.enrollSpeakerProfileFromAudio(displayName: name, localFileURL: url)
            try? FileManager.default.removeItem(at: url)
            statusMessage = "声纹档案已保存"
            enrollmentName = ""
            consentAccepted = false
        } catch {
            errorMessage = userMessage(error)
            statusMessage = nil
        }
    }

    func rename(_ profile: SpeakerProfile, name: String, session: AppSession) async {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            try await session.updateSpeakerProfile(profile, displayName: clean)
            statusMessage = "声纹姓名已更新"
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func toggle(_ profile: SpeakerProfile, active: Bool, session: AppSession) async {
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            try await session.updateSpeakerProfile(profile, active: active)
            statusMessage = active ? "声纹档案已启用" : "声纹档案已停用"
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func delete(_ profile: SpeakerProfile, session: AppSession) async {
        isBusy = true
        errorMessage = nil
        defer { isBusy = false }
        do {
            try await session.deleteSpeakerProfile(profile)
            statusMessage = "声纹档案已删除"
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
}

private struct VoiceprintProfileRow: View {
    let profile: SpeakerProfile
    let isBusy: Bool
    let onRename: (String) -> Void
    let onToggle: (Bool) -> Void
    let onDelete: () -> Void
    @State private var editing = false
    @State private var editName = ""
    @State private var confirmingDelete = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "mic.circle.fill")
                    .font(.title2)
                    .foregroundStyle(profile.active ? HuiyiTheme.accent : HuiyiTheme.textSecondary)
                VStack(alignment: .leading, spacing: 4) {
                    Text(profile.displayName)
                        .font(.headline)
                        .lineLimit(1)
                    Text("\(profile.sampleCount) 个样本 · \(profile.updatedAt.voiceprintDateLabel)")
                        .font(.caption)
                        .foregroundStyle(HuiyiTheme.textSecondary)
                }
                Spacer()
                Text(profile.active ? "启用" : "停用")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(profile.active ? HuiyiTheme.success : HuiyiTheme.textSecondary)
            }

            if editing {
                TextField("声纹姓名", text: $editName)
                    .textFieldStyle(.roundedBorder)
                HStack {
                    Button("保存") {
                        onRename(editName)
                        editing = false
                    }
                    .disabled(editName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isBusy)
                    Button("取消") {
                        editName = profile.displayName
                        editing = false
                    }
                    .disabled(isBusy)
                }
            } else {
                HStack {
                    Button("改名") {
                        editName = profile.displayName
                        editing = true
                    }
                    .disabled(isBusy)
                    Button(profile.active ? "停用" : "启用") {
                        onToggle(!profile.active)
                    }
                    .disabled(isBusy)
                    Spacer()
                    Button("删除", role: .destructive) {
                        confirmingDelete = true
                    }
                    .disabled(isBusy)
                }
                .font(.subheadline.weight(.semibold))
            }
        }
        .padding(.vertical, 6)
        .confirmationDialog("删除声纹档案？", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) { onDelete() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("删除后会移除 \(profile.displayName) 的声纹档案和样本，后续会议不会再用它自动识别。")
        }
    }
}

private struct VoiceprintDocumentPicker: UIViewControllerRepresentable {
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

private struct VoiceprintSampleRecorderView: View {
    let name: String
    let onFinished: (URL) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var engine = RecordingEngine()
    @State private var state: RecordingEngineState = .idle
    @State private var elapsedSeconds = 0
    @State private var outputURL: URL?
    @State private var timerTask: Task<Void, Never>?
    private let audioFileStore = AudioFileStore()

    var body: some View {
        NavigationStack {
            List {
                Section("录音样本") {
                    Text(name)
                        .font(.headline)
                    Text(timeText)
                        .font(.system(size: 40, weight: .semibold, design: .monospaced))
                        .monospacedDigit()
                        .frame(maxWidth: .infinity)
                    Text(instructionText)
                        .font(.footnote)
                        .foregroundStyle(HuiyiTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Section {
                    PrimaryActionButton(title: primaryTitle, systemImage: primaryIcon, isLoading: state == .stopping) {
                        Task { await primaryAction() }
                    }
                    if case let .finished(url) = state {
                        SecondaryActionButton(title: "使用此样本", systemImage: "checkmark.circle") {
                            onFinished(url)
                        }
                    }
                }
            }
            .navigationTitle("录音录入")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") {
                        cancelRecording()
                        dismiss()
                    }
                }
            }
            .onDisappear {
                cancelRecording()
            }
        }
    }

    private var primaryTitle: String {
        switch state {
        case .recording:
            return "结束录音"
        case .stopping:
            return "保存中"
        case .finished:
            return "重新录制"
        default:
            return "开始录音"
        }
    }

    private var primaryIcon: String {
        switch state {
        case .recording:
            return "stop.circle"
        default:
            return "mic.circle"
        }
    }

    private var instructionText: String {
        switch state {
        case .finished:
            return "样本已保存，可以使用此样本录入声纹。"
        case .recording:
            return "请朗读 15-30 秒清晰人声，避免背景噪音。"
        default:
            return "录音样本只用于声纹建档，不会创建会议任务。"
        }
    }

    private var timeText: String {
        String(format: "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)
    }

    private func primaryAction() async {
        switch state {
        case .recording:
            if let url = await engine.stop() {
                outputURL = url
            }
            stopTimer()
        case .stopping:
            break
        default:
            await start()
        }
    }

    private func start() async {
        engine.cancel()
        stopTimer()
        elapsedSeconds = 0
        outputURL = nil
        state = .idle
        let taskId = "voiceprint-\(UUID().uuidString)"
        guard let url = try? audioFileStore.recordingURL(taskId: taskId) else { return }
        outputURL = url
        await engine.start(
            outputURL: url,
            onPCM: { _, _ in },
            onLevel: { _ in },
            onState: { recordingState in
                state = recordingState
                if recordingState == .recording {
                    startTimer()
                }
                if case .finished = recordingState {
                    stopTimer()
                }
            }
        )
    }

    private func startTimer() {
        stopTimer()
        let started = Date()
        timerTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 250_000_000)
                await MainActor.run {
                    elapsedSeconds = max(0, Int(Date().timeIntervalSince(started)))
                }
            }
        }
    }

    private func stopTimer() {
        timerTask?.cancel()
        timerTask = nil
    }

    private func cancelRecording() {
        if let outputURL {
            try? FileManager.default.removeItem(at: outputURL)
        }
        engine.cancel()
        stopTimer()
        outputURL = nil
        if case .recording = state {
            state = .idle
        }
    }
}

private extension String {
    var voiceprintDateLabel: String {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.count >= 10 {
            return String(clean.prefix(10))
        }
        return "刚刚更新"
    }
}
