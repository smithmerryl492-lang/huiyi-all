import SwiftUI

struct RecordingView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = RecordingViewModel()
    @State private var showingRecordConsent = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    SmartRoundIconButton(
                        systemImage: "chevron.left",
                        accessibilityLabel: "返回",
                        tint: HuiyiTheme.ink
                    ) {
                        router.go(.home)
                    }
                    .disabled(viewModel.state == .recording || viewModel.state == .paused || viewModel.state == .stopping)

                    VStack(spacing: 3) {
                        Text(recordTitle)
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(HuiyiTheme.ink)
                            .lineLimit(1)
                        Text(viewModel.state.recordSubtitle)
                            .font(.system(size: 13))
                            .foregroundStyle(HuiyiTheme.muted)
                    }
                    .frame(maxWidth: .infinity)
                    Spacer().frame(width: 40)
                }
                .padding(.horizontal, 12)
                .padding(.top, 12)
                .padding(.bottom, 8)

                SmartGradientPanel(radius: 28, gradient: LinearGradient(colors: [HuiyiTheme.brand, Color(red: 0.294, green: 0.482, blue: 1.000), HuiyiTheme.brandCyan], startPoint: .leading, endPoint: .trailing)) {
                    VStack(spacing: 16) {
                        HStack {
                            RecordingLiveDot(text: viewModel.state.liveLabel, active: viewModel.state == .recording)
                            Spacer()
                            Text("输入 \(viewModel.audioLevel.levelPercent)%")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(viewModel.audioLevel.levelPercent > 0 ? HuiyiTheme.brandDark : .white)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(viewModel.audioLevel.levelPercent > 0 ? Color(red: 0.831, green: 1.000, blue: 0.973) : Color.white.opacity(0.12), in: Capsule())
                        }
                        Text(timeText)
                            .font(.system(size: 44, weight: .bold, design: .monospaced))
                            .foregroundStyle(.white)
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.78)
                        WaveBars(active: viewModel.state == .recording, levelPercent: viewModel.audioLevel.levelPercent, barCount: 24, maxHeight: 44)
                            .frame(maxWidth: .infinity)
                        if let status = viewModel.statusMessage {
                            Text(status)
                                .font(.system(size: 13))
                                .foregroundStyle(Color(red: 0.749, green: 0.969, blue: 0.933))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if viewModel.state == .paused {
                            HStack(alignment: .top, spacing: 10) {
                                Image(systemName: "pause.fill")
                                    .foregroundStyle(Color(red: 0.518, green: 0.894, blue: 0.847))
                                Text("点击继续后恢复录音与实时转写，当前已保留暂停前片段。")
                                    .font(.system(size: 13))
                                    .foregroundStyle(Color.white.opacity(0.82))
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.white.opacity(0.14), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                        }
                    }
                    .padding(18)
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 18)

                if showsPreStartOptions {
                    languagePickerCard
                        .padding(.horizontal, 20)
                        .padding(.bottom, 14)
                }

                HStack {
                    Text("实时转写")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                    Spacer()
                    RecordingLiveDot(text: transcriptStatusLabel, active: viewModel.state == .recording && viewModel.statusMessage == nil, contentColor: HuiyiTheme.brand)
                }
                .padding(.horizontal, 20)

                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 14) {
                            if viewModel.transcriptBuffer.displaySegments.isEmpty {
                                SmartCard(radius: 22, padding: 18, borderColor: HuiyiTheme.line) {
                                    Text("开始说话后，转写内容会显示在这里。")
                                        .font(.system(size: 14))
                                        .foregroundStyle(HuiyiTheme.muted)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            } else {
                                ForEach(Array(viewModel.transcriptBuffer.displaySegments.enumerated()), id: \.offset) { index, segment in
                                    RecordingTranscriptLine(segment: segment)
                                        .id(index)
                                }
                            }
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 14)
                        .padding(.bottom, 16)
                    }
                    .scrollIndicators(.hidden)
                    .onChange(of: viewModel.transcriptBuffer.displaySegments.count) { count in
                        guard count > 0 else { return }
                        withAnimation {
                            proxy.scrollTo(count - 1, anchor: .bottom)
                        }
                    }
                }

                HStack(spacing: 10) {
                    if viewModel.state == .recording || viewModel.state == .paused || viewModel.state == .preparing {
                        SecondaryActionButton(
                            title: viewModel.state == .paused ? "继续" : (viewModel.state == .preparing ? "准备中" : "暂停"),
                            systemImage: viewModel.state == .paused ? "mic.fill" : "pause.fill"
                        ) {
                            if viewModel.state != .preparing {
                                viewModel.pauseOrResume()
                            }
                        }
                        .disabled(viewModel.state == .preparing)
                    }
                    Button {
                        if requiresRecordConsent {
                            showingRecordConsent = true
                        } else {
                            viewModel.primaryAction(session: session, router: router)
                        }
                    } label: {
                        HStack(spacing: 7) {
                            if viewModel.state == .stopping {
                                ProgressView().tint(.white)
                            } else {
                                Image(systemName: viewModel.state == .recording || viewModel.state == .paused ? "stop.fill" : "mic.fill")
                            }
                            Text(viewModel.state == .recording || viewModel.state == .paused ? "结束" : viewModel.primaryActionTitle)
                                .font(.system(size: 16, weight: .bold))
                        }
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(viewModel.state == .recording || viewModel.state == .paused ? HuiyiTheme.danger : HuiyiTheme.brand, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(viewModel.state == .stopping)
                }
                .padding(.horizontal, 20)
                .padding(.top, 10)
                .padding(.bottom, 18)
            }
            .huiyiScreenBackground()
            .navigationBarHidden(true)
            .onAppear {
                viewModel.configure(schedule: router.selectedRecordingSchedule)
            }
            .sheet(isPresented: $showingRecordConsent) {
                RecordConsentView(selectedLanguage: $viewModel.selectedLanguage) {
                    showingRecordConsent = false
                    viewModel.primaryAction(session: session, router: router)
                }
            }
        }
    }

    private var recordTitle: String {
        if let schedule = viewModel.scheduleContext, !schedule.title.isEmpty {
            return schedule.title
        }
        return "实时录音"
    }

    private var languagePickerCard: some View {
        SmartCard(radius: 18, padding: 14) {
            Text("识别语言")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(HuiyiTheme.ink)
            Picker("语言", selection: $viewModel.selectedLanguage) {
                ForEach(RecognitionLanguage.allCases, id: \.rawValue) { language in
                    Text(language.displayName).tag(language)
                }
            }
            .pickerStyle(.segmented)
            .disabled(viewModel.isBusy)
            if let schedule = viewModel.scheduleContext {
                VStack(alignment: .leading, spacing: 5) {
                    Text(schedule.time)
                    if !schedule.participants.isEmpty {
                        Text(schedule.participants)
                    }
                }
                .font(.system(size: 13))
                .foregroundStyle(HuiyiTheme.muted)
            }
            if let errorMessage = viewModel.errorMessage {
                ErrorBanner(message: errorMessage)
            }
        }
    }

    private var transcriptStatusLabel: String {
        switch viewModel.state {
        case .preparing: return "准备中"
        case .paused: return "已暂停"
        case .recording where viewModel.statusMessage != nil: return "本地保存中"
        default: return "转写中"
        }
    }

    private var showsPreStartOptions: Bool {
        switch viewModel.state {
        case .idle, .failedBeforeStart, .finished:
            return true
        case .preparing, .recording, .paused, .stopping:
            return false
        }
    }

    private var requiresRecordConsent: Bool {
        switch viewModel.state {
        case .idle, .failedBeforeStart, .finished:
            return true
        case .preparing, .recording, .paused, .stopping:
            return false
        }
    }

    private var timeText: String {
        let minutes = viewModel.elapsedSeconds / 60
        let seconds = viewModel.elapsedSeconds % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }
}

private struct RecordingLiveDot: View {
    let text: String
    let active: Bool
    var contentColor: Color = .white

    var body: some View {
        HStack(spacing: 7) {
            Circle()
                .fill(active ? HuiyiTheme.success : contentColor.opacity(0.5))
                .frame(width: 8, height: 8)
            Text(text)
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(contentColor)
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(contentColor.opacity(0.12), in: Capsule())
    }
}

private struct RecordingTranscriptLine: View {
    let segment: TranscriptSegment

    var body: some View {
        SmartCard(radius: 18, padding: 14, borderColor: HuiyiTheme.line) {
            HStack(spacing: 8) {
                Text(segment.timestamp.isEmpty ? "实时" : segment.timestamp)
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundStyle(HuiyiTheme.brand)
                if !segment.timeRangeLabel.isEmpty {
                    Text(segment.timeRangeLabel)
                        .font(.system(size: 12))
                        .foregroundStyle(HuiyiTheme.muted)
                }
                Spacer()
            }
            Text(segment.text)
                .font(.system(size: 15))
                .foregroundStyle(HuiyiTheme.ink)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private extension RecordingViewModel.State {
    var recordSubtitle: String {
        switch self {
        case .preparing: return "正在连接实时转写"
        case .paused: return "录音已暂停"
        case .recording: return "录音进行中"
        case .stopping: return "正在保存录音"
        default: return "录音准备中"
        }
    }

    var liveLabel: String {
        switch self {
        case .preparing: return "连接中"
        case .paused: return "录音已暂停"
        case .recording: return "实时记录中"
        default: return "待开始"
        }
    }
}

private struct LegacyRecordingView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = RecordingViewModel()
    @State private var showingRecordConsent = false

    var body: some View {
        NavigationStack {
            List {
                Section("识别语言") {
                    Picker("语言", selection: $viewModel.selectedLanguage) {
                        ForEach(RecognitionLanguage.allCases, id: \.self) { language in
                            Text(language.displayName).tag(language)
                        }
                    }
                    .pickerStyle(.segmented)
                    .disabled(viewModel.isBusy)
                }

                Section("实时记录") {
                    VStack(spacing: 18) {
                        Text(timeText)
                            .font(.system(size: 44, weight: .semibold, design: .monospaced))
                            .monospacedDigit()
                            .frame(maxWidth: .infinity)

                        AudioLevelBar(level: viewModel.audioLevel.levelPercent)

                        if let status = viewModel.statusMessage {
                            Text(status)
                                .font(.subheadline)
                                .foregroundStyle(HuiyiTheme.textSecondary)
                                .multilineTextAlignment(.center)
                                .fixedSize(horizontal: false, vertical: true)
                        }

                        PrimaryActionButton(
                            title: viewModel.primaryActionTitle,
                            systemImage: actionIcon,
                            isLoading: viewModel.state == .stopping
                        ) {
                            if requiresRecordConsent {
                                showingRecordConsent = true
                            } else {
                                viewModel.primaryAction(session: session, router: router)
                            }
                        }

                        if viewModel.state == .recording || viewModel.state == .paused {
                            SecondaryActionButton(title: viewModel.state == .paused ? "继续录音" : "暂停录音", systemImage: viewModel.state == .paused ? "play.circle" : "pause.circle") {
                                viewModel.pauseOrResume()
                            }
                        }
                    }
                    .padding(.vertical, 12)
                }

                if let schedule = viewModel.scheduleContext {
                    Section("预约会议") {
                        LabeledContent("主题", value: schedule.title)
                        LabeledContent("时间", value: schedule.time)
                        if !schedule.participants.isEmpty {
                            LabeledContent("参会人", value: schedule.participants)
                        }
                    }
                }

                if let errorMessage = viewModel.errorMessage {
                    Section {
                        ErrorBanner(message: errorMessage)
                    }
                }

                Section("实时字幕") {
                    if viewModel.transcriptBuffer.displaySegments.isEmpty {
                        EmptyStateView(
                            title: "等待发言",
                            message: "实时字幕用于记录过程展示，最终纪要仍会进入服务端说话人分离和声纹处理。",
                            systemImage: "text.bubble"
                        )
                        .listRowInsets(EdgeInsets())
                    } else {
                        ForEach(Array(viewModel.transcriptBuffer.displaySegments.enumerated()), id: \.offset) { _, segment in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(segment.timestamp)
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(HuiyiTheme.textSecondary)
                                Text(segment.text)
                                    .font(.subheadline)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
            }
            .navigationTitle("录音")
            .toolbar {
                Button("返回") { router.go(.home) }
                    .disabled(viewModel.state == .recording || viewModel.state == .paused || viewModel.state == .stopping)
            }
            .onAppear {
                viewModel.configure(schedule: router.selectedRecordingSchedule)
            }
            .sheet(isPresented: $showingRecordConsent) {
                RecordConsentView(selectedLanguage: $viewModel.selectedLanguage) {
                    showingRecordConsent = false
                    viewModel.primaryAction(session: session, router: router)
                }
            }
        }
    }

    private var requiresRecordConsent: Bool {
        switch viewModel.state {
        case .idle, .failedBeforeStart, .finished:
            return true
        case .preparing, .recording, .paused, .stopping:
            return false
        }
    }

    private var actionIcon: String {
        switch viewModel.state {
        case .recording, .paused:
            return "stop.circle"
        case .preparing:
            return "xmark.circle"
        default:
            return "mic.circle"
        }
    }

    private var timeText: String {
        let minutes = viewModel.elapsedSeconds / 60
        let seconds = viewModel.elapsedSeconds % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }
}

private struct RecordConsentView: View {
    @Binding var selectedLanguage: RecognitionLanguage
    let onStart: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("开始录音前确认") {
                    Text("请确认本次录音已获得会议参与方许可，并遵守当地法律法规。录音中会持续展示状态提醒。")
                        .font(.subheadline)
                        .foregroundStyle(HuiyiTheme.textSecondary)
                    Label("麦克风权限", systemImage: "mic")
                    Label("本地录音文件", systemImage: "doc")
                }

                Section("识别语言") {
                    Picker("语言", selection: $selectedLanguage) {
                        ForEach(RecognitionLanguage.allCases, id: \.self) { language in
                            Text(language.displayName).tag(language)
                        }
                    }
                    .pickerStyle(.segmented)
                }
            }
            .navigationTitle("开始录音前确认")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("开始录音") {
                        dismiss()
                        onStart()
                    }
                }
            }
        }
    }
}

private struct AudioLevelBar: View {
    let level: Int

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(HuiyiTheme.textSecondary.opacity(0.15))
                Capsule()
                    .fill(HuiyiTheme.accent)
                    .frame(width: geometry.size.width * CGFloat(min(100, max(0, level))) / 100)
            }
        }
        .frame(height: 10)
    }
}
