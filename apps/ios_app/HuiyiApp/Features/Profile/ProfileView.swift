import SwiftUI

struct ProfileView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @State private var showingLogoutConfirm = false
    @State private var showingDeleteDataConfirm = false
    @State private var isClearingData = false
    @State private var showingNameEditor = false
    @State private var nameDraft = ""
    @State private var permissionStatus: PermissionStatus = .unknown
    @State private var statusMessage: String?
    @State private var errorMessage: String?
    @State private var isRefreshingPermissions = false

    var body: some View {
        NavigationStack {
            SmartScreenScaffold(
                title: "个人中心",
                onBack: { router.go(.home) }
            ) {
                if let errorMessage {
                    ErrorBanner(message: errorMessage)
                }
                if let statusMessage {
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

                profileAccountCard
                profileMembershipCard
                profileSyncCard
                profilePanel(title: "订单与支付") {
                    SmartRowButton(systemImage: "receipt", title: "订单记录", subtitle: "查看会员套餐和加量包购买记录") {
                        router.go(.paymentOrders)
                    }
                }
                profilePanel(title: "智能声纹") {
                    SmartRowButton(systemImage: "mic.fill", title: "声纹库", subtitle: "管理声纹档案，可提前录入或停用") {
                        router.go(.voiceprints)
                    }
                }
                profilePanel(title: "账号安全") {
                    SmartRowButton(systemImage: "lock.shield.fill", title: "手机号与密码", subtitle: "修改手机号、密码和登录安全信息") {
                        router.go(.accountSecurity)
                    }
                }
                profilePanel(title: "数据与隐私") {
                    SmartRowButton(systemImage: "doc.text.fill", title: "用户协议") {
                        router.go(.userAgreement)
                    }
                    SmartRowButton(systemImage: "hand.raised.fill", title: "隐私政策") {
                        router.go(.privacyPolicy)
                    }
                    SmartRowButton(systemImage: "trash.fill", title: "删除会议数据", subtitle: "清理本机录音、转写、纪要和索引", tint: HuiyiTheme.danger) {
                        showingDeleteDataConfirm = true
                    }
                    .disabled(isClearingData)
                }
                profilePanel(title: "系统权限") {
                    ProfilePermissionRow(title: "麦克风", value: permissionStatus.microphoneEnabled ? "已开启" : "未开启", enabled: permissionStatus.microphoneEnabled) {
                        Task { await requestMicrophonePermission() }
                    }
                    ProfilePermissionRow(title: "通知", value: permissionStatus.notificationEnabled ? "已开启" : "未开启", enabled: permissionStatus.notificationEnabled) {
                        Task { await requestNotificationPermission() }
                    }
                    ProfilePermissionRow(title: "文件访问", value: permissionStatus.fileAccessLabel, enabled: true) {
                        PermissionStatusReader.openAppSettings()
                    }
                    SecondaryActionButton(title: isRefreshingPermissions ? "刷新中" : "刷新权限状态", systemImage: "arrow.clockwise") {
                        Task { await refreshPermissionStatus() }
                    }
                    .disabled(isRefreshingPermissions)
                }
            }
            .task {
                await refreshPermissionStatus()
            }
            .alert("修改昵称", isPresented: $showingNameEditor) {
                TextField("昵称", text: $nameDraft)
                Button("保存") {
                    session.updateProfileName(nameDraft)
                }
                Button("取消", role: .cancel) {}
            }
            .confirmationDialog("删除会议数据", isPresented: $showingDeleteDataConfirm, titleVisibility: .visible) {
                Button("确认删除", role: .destructive) {
                    Task { await clearMeetingData() }
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("将删除本地录音、转写、纪要、待办和知识库索引；已同步的数据会同时清理云端记录。")
            }
            .confirmationDialog("退出账号", isPresented: $showingLogoutConfirm, titleVisibility: .visible) {
                Button("退出并删除本机数据", role: .destructive) {
                    session.logoutAndClearLocalData()
                }
                Button("先不退出", role: .cancel) {}
            } message: {
                Text(logoutWarningText)
            }
        }
    }

    private var profileAccountCard: some View {
        ZStack {
            LinearGradient(
                colors: [HuiyiTheme.brandCyan, Color(red: 0.431, green: 0.631, blue: 1.000), HuiyiTheme.brandPurple],
                startPoint: .leading,
                endPoint: .trailing
            )
            VStack(alignment: .leading, spacing: 15) {
                HStack(alignment: .center, spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(Color.white.opacity(0.22))
                        Image(systemName: "person.fill")
                            .font(.system(size: 24, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                    .frame(width: 48, height: 48)

                    VStack(alignment: .leading, spacing: 5) {
                        Text(displayName)
                            .font(.system(size: displayName.count > 8 ? 18 : 21, weight: .bold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .minimumScaleFactor(0.76)
                        HStack(spacing: 6) {
                            Text(profilePhoneLabel)
                                .font(.system(size: 12.5, weight: .medium))
                                .foregroundStyle(Color.white.opacity(0.84))
                                .lineLimit(1)
                                .minimumScaleFactor(0.76)
                            Button {
                                router.go(.accountSecurity)
                            } label: {
                                Text("修改")
                                    .font(.system(size: 11.5, weight: .bold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 6)
                                    .frame(height: 24)
                            }
                            .buttonStyle(.plain)
                            .disabled(session.currentUser?.phone.isEmpty ?? true)
                        }
                    }
                    Spacer(minLength: 8)
                    Button {
                        nameDraft = editableProfileName
                        showingNameEditor = true
                    } label: {
                        Image(systemName: "pencil")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(Color.white.opacity(0.18), in: Circle())
                    }
                    .buttonStyle(.plain)
                }

                Divider()
                    .overlay(Color.white.opacity(0.18))

                HStack(spacing: 0) {
                    ProfileGradientMetricTile(value: "\(meetingCount)", label: "会议", systemImage: "doc.text.fill")
                    Rectangle()
                        .fill(Color.white.opacity(0.22))
                        .frame(width: 1, height: 32)
                    ProfileGradientMetricTile(value: "\(todoCount)", label: "待办", systemImage: "checkmark.shield.fill")
                }

                Divider()
                    .overlay(Color.white.opacity(0.18))

                Button(role: .destructive) {
                    showingLogoutConfirm = true
                } label: {
                    HStack {
                        Image(systemName: "rectangle.portrait.and.arrow.right")
                        Text("退出登录")
                            .font(.system(size: 16, weight: .bold))
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(Color.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Color.white.opacity(0.42), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .disabled(isClearingData)
            }
            .padding(18)
        }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .shadow(color: HuiyiTheme.brandDark.opacity(0.12), radius: 14, x: 0, y: 6)
    }

    private var profileMembershipCard: some View {
        Button {
            router.go(.membership)
        } label: {
            SmartCard(radius: 24, padding: 18, borderColor: session.membershipProfile.active ? HuiyiTheme.brand.opacity(0.18) : HuiyiTheme.line) {
                HStack(spacing: 12) {
                    Image(systemName: "checkmark.shield.fill")
                        .font(.system(size: 23, weight: .semibold))
                        .foregroundStyle(HuiyiTheme.brand)
                        .frame(width: 44, height: 44)
                        .background(session.membershipProfile.active ? HuiyiTheme.brandSoft : HuiyiTheme.brandSoftCyan, in: Circle())
                    VStack(alignment: .leading, spacing: 4) {
                        Text(session.membershipProfile.active ? session.membershipProfile.planName : "未开通会员")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(HuiyiTheme.ink)
                            .lineLimit(1)
                        Text(memberSubtitle)
                            .font(.system(size: 13))
                            .foregroundStyle(HuiyiTheme.muted)
                            .lineLimit(1)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(HuiyiTheme.brand)
                }
                HStack(spacing: 12) {
                    ProfileQuotaMini(title: "转写时长", used: session.membershipProfile.transcriptionMinutesUsed, total: session.membershipProfile.transcriptionMinutesTotal, suffix: "分钟")
                    ProfileQuotaMini(title: "知识问答", used: session.membershipProfile.knowledgeQaUsed, total: session.membershipProfile.knowledgeQaTotal, suffix: "次")
                }
            }
        }
        .buttonStyle(.plain)
    }

    private var profileSyncCard: some View {
        SmartCard(radius: 24, padding: 18) {
            HStack(spacing: 12) {
                Image(systemName: "icloud.fill")
                    .font(.system(size: 23, weight: .semibold))
                    .foregroundStyle(HuiyiTheme.brand)
                    .frame(width: 44, height: 44)
                    .background(HuiyiTheme.brandSoft, in: Circle())
                VStack(alignment: .leading, spacing: 4) {
                    Text("云端同步")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                    Text(session.cloudSyncStatusText)
                        .font(.system(size: 13))
                        .foregroundStyle(HuiyiTheme.muted)
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { session.cloudSyncEnabled },
                    set: { session.setCloudSyncEnabled($0) }
                ))
                .labelsHidden()
                .tint(HuiyiTheme.brand)
            }
            HStack(spacing: 10) {
                ProfileMetricTile(value: "\(session.unsyncedFinishedMeetingCount)", label: "未上传")
                ProfileMetricTile(value: "\(session.pendingDeleteCount)", label: "待删除")
            }
            HStack(spacing: 10) {
                SecondaryActionButton(title: session.cloudSyncInProgress ? "同步中" : "上传本机", systemImage: "icloud.and.arrow.up") {
                    Task { await uploadUnsyncedMeetings() }
                }
                .disabled(session.cloudSyncInProgress || session.unsyncedFinishedMeetingCount == 0)
                SecondaryActionButton(title: session.cloudSyncInProgress ? "同步中" : "拉取云端", systemImage: "arrow.triangle.2.circlepath") {
                    Task { await refreshCloudData() }
                }
                .disabled(session.cloudSyncInProgress)
            }
        }
    }

    private func profilePanel<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        SmartInfoBlock(title: title) {
            VStack(spacing: 0) {
                content()
            }
        }
    }

    private var displayName: String {
        let name = editableProfileName
        if !name.isEmpty { return name }
        return "未设置"
    }

    private var editableProfileName: String {
        let profileName = session.profileName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !profileName.isEmpty && !profileName.isGeneratedPhoneDisplayName { return profileName }
        let userName = session.currentUser?.displayName.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !userName.isEmpty && !userName.isGeneratedPhoneDisplayName { return userName }
        return ""
    }

    private var profilePhoneLabel: String {
        let phone = session.currentUser?.phone ?? ""
        let label = phone.profilePhoneLabel
        if !label.isEmpty { return label }
        return session.currentUser?.userId ?? "登录后开启云端同步"
    }

    private var profileInitial: String {
        String(displayName.prefix(1))
    }

    private var memberSubtitle: String {
        let profile = session.membershipProfile
        if profile.active { return "有效期至 \(profile.expiresAt ?? "-")" }
        if profile.transcriptionMinutesTotal > 0 || profile.knowledgeQaTotal > 0 { return "当前剩余额度可直接使用" }
        return "开通后获得转写时长和知识库问答额度"
    }

    private var logoutWarningText: String {
        var lines = ["退出登录会清除本机会议、待办、预约和知识库缓存，只影响当前设备，不会主动删除云端数据。"]
        if session.unsyncedFinishedMeetingCount > 0 {
            lines.append("有 \(session.unsyncedFinishedMeetingCount) 个本机会议或文件任务尚未上传到云端，确认退出后这些本机数据会被删除。")
        }
        if session.pendingDeleteCount > 0 {
            lines.append("还有 \(session.pendingDeleteCount) 条本机删除操作尚未同步，退出后这些同步任务会停止。")
        }
        return lines.joined(separator: "\n")
    }

    private func refreshCloudData() async {
        errorMessage = nil
        statusMessage = nil
        do {
            _ = try await session.loadCloudBootstrap()
            statusMessage = "云端数据已刷新"
        } catch {
            errorMessage = userMessage(error)
        }
    }

    private func uploadUnsyncedMeetings() async {
        errorMessage = nil
        statusMessage = nil
        do {
            try await session.uploadAllUnsyncedMeetings()
            statusMessage = "未上传会议纪要已同步"
        } catch {
            errorMessage = userMessage(error)
        }
    }

    private func refreshPermissionStatus() async {
        isRefreshingPermissions = true
        defer { isRefreshingPermissions = false }
        permissionStatus = await PermissionStatusReader.read()
    }

    private func requestMicrophonePermission() async {
        isRefreshingPermissions = true
        defer { isRefreshingPermissions = false }
        _ = await PermissionStatusReader.requestMicrophonePermission()
        permissionStatus = await PermissionStatusReader.read()
    }

    private func requestNotificationPermission() async {
        isRefreshingPermissions = true
        defer { isRefreshingPermissions = false }
        _ = await PermissionStatusReader.requestNotificationPermission()
        permissionStatus = await PermissionStatusReader.read()
    }

    private func clearMeetingData() async {
        isClearingData = true
        errorMessage = nil
        statusMessage = nil
        defer { isClearingData = false }
        do {
            try await session.clearAllMeetingData()
            statusMessage = "会议数据已删除"
        } catch {
            errorMessage = userMessage(error)
        }
    }

    private var meetingCount: Int {
        session.localMeetingDetails().count
    }

    private var todoCount: Int {
        session.localMeetingDetails().reduce(0) { total, detail in
            total + (detail.result?.todos.filter { !$0.done }.count ?? 0)
        }
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }
}

private struct ProfileMetricTile: View {
    let value: String
    let label: String

    var body: some View {
        VStack(spacing: 5) {
            Text(value)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(HuiyiTheme.brand)
                .monospacedDigit()
            Text(label)
                .font(.system(size: 12))
                .foregroundStyle(HuiyiTheme.muted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(Color(red: 0.965, green: 0.980, blue: 0.992), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct ProfileGradientMetricTile: View {
    let value: String
    let label: String
    let systemImage: String

    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Color.white.opacity(0.90))
            VStack(alignment: .leading, spacing: 3) {
                Text(value)
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(.white)
                    .monospacedDigit()
                Text(label)
                    .font(.system(size: 12))
                    .foregroundStyle(Color.white.opacity(0.76))
            }
        }
        .frame(maxWidth: .infinity)
    }
}

private extension String {
    var profilePhoneLabel: String {
        let digits = filter(\.isNumber)
        let local = digits.hasPrefix("86") && digits.count >= 13 ? String(digits.suffix(11)) : digits
        guard local.count == 11 else { return trimmingCharacters(in: .whitespacesAndNewlines) }
        return "\(local.prefix(3))****\(local.suffix(4))"
    }

    var isGeneratedPhoneDisplayName: Bool {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.hasPrefix("用户 ") && clean.contains("*") && clean.contains(where: \.isNumber)
    }
}

private struct ProfileQuotaMini: View {
    let title: String
    let used: Int
    let total: Int
    let suffix: String

    var body: some View {
        let remaining = max(0, total - used)
        let progress = total > 0 ? min(1, max(0, Double(used) / Double(total))) : 0
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(HuiyiTheme.ink)
                .lineLimit(1)
            ProgressView(value: progress)
                .tint(HuiyiTheme.brand)
            Text("剩余 \(remaining)/\(total)\(suffix)")
                .font(.system(size: 12))
                .foregroundStyle(HuiyiTheme.muted)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(red: 0.965, green: 0.980, blue: 0.992), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

private struct MembershipLoadingMiniStrip: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            RoundedRectangle(cornerRadius: 99)
                .fill(Color(red: 0.918, green: 0.941, blue: 0.965))
                .frame(maxWidth: .infinity)
                .frame(height: 10)
            RoundedRectangle(cornerRadius: 99)
                .fill(Color(red: 0.918, green: 0.941, blue: 0.965))
                .frame(width: 160, height: 10)
        }
    }
}

private struct ProfilePermissionRow: View {
    let title: String
    let value: String
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                    Text(value)
                        .font(.system(size: 13))
                        .foregroundStyle(enabled ? HuiyiTheme.brand : HuiyiTheme.warning)
                }
                Spacer()
                Text(value)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(enabled ? HuiyiTheme.brand : HuiyiTheme.warning)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(enabled ? HuiyiTheme.brandSoft : Color(red: 1.000, green: 0.961, blue: 0.859), in: Capsule())
            }
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }
}

private struct LegacyProfileView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @State private var showingLogoutConfirm = false
    @State private var showingDeleteDataConfirm = false
    @State private var isClearingData = false
    @State private var showingNameEditor = false
    @State private var nameDraft = ""
    @State private var permissionStatus: PermissionStatus = .unknown
    @State private var statusMessage: String?
    @State private var errorMessage: String?
    @State private var isRefreshingPermissions = false

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    if let errorMessage {
                        ErrorBanner(message: errorMessage)
                    }
                    if let statusMessage {
                        Label(statusMessage, systemImage: "checkmark.circle")
                            .foregroundStyle(HuiyiTheme.success)
                    }

                    Section("账号") {
                        LabeledContent("用户名", value: session.profileName.isEmpty ? (session.currentUser?.displayName ?? "-") : session.profileName)
                        LabeledContent("手机号", value: session.currentUser?.phone ?? "-")
                        LabeledContent("会议", value: "\(meetingCount) 场")
                        LabeledContent("待办", value: "\(todoCount) 个")
                        Button {
                            nameDraft = session.profileName
                            showingNameEditor = true
                        } label: {
                            Label("修改昵称", systemImage: "pencil")
                        }
                    }

                    Section("云端同步") {
                        Color.clear.frame(height: 0).id("cloud-sync-section")
                        Toggle(isOn: Binding(
                            get: { session.cloudSyncEnabled },
                            set: { session.setCloudSyncEnabled($0) }
                        )) {
                            Label("云端同步", systemImage: "icloud")
                        }
                        LabeledContent("状态", value: session.cloudSyncStatusText)
                        LabeledContent("未上传会议", value: "\(session.unsyncedFinishedMeetingCount) 场")
                        if session.pendingDeleteCount > 0 {
                            LabeledContent("待同步删除", value: "\(session.pendingDeleteCount) 条")
                        }
                        Button {
                            Task { await uploadUnsyncedMeetings() }
                        } label: {
                            Label(session.cloudSyncInProgress ? "同步中" : "上传本机", systemImage: "icloud.and.arrow.up")
                        }
                        .disabled(session.cloudSyncInProgress || session.unsyncedFinishedMeetingCount == 0)
                        Button {
                            Task { await refreshCloudData() }
                        } label: {
                            Label(session.cloudSyncInProgress ? "同步中" : "拉取云端", systemImage: "arrow.triangle.2.circlepath")
                        }
                        .disabled(session.cloudSyncInProgress)
                    }

                    Section("会员") {
                        Button {
                            router.go(.membership)
                        } label: {
                            Label("会员中心", systemImage: "creditcard")
                        }
                        Button {
                            router.go(.paymentOrders)
                        } label: {
                            Label("订单记录", systemImage: "list.bullet.rectangle")
                        }
                    }

                    Section("账号安全") {
                        Button {
                            router.go(.accountSecurity)
                        } label: {
                            Label("手机号与密码", systemImage: "lock.shield")
                        }
                    }

                    Section("会议能力") {
                        Button {
                            router.go(.voiceprints)
                        } label: {
                            Label("声纹档案", systemImage: "person.2")
                        }
                    }

                    Section("系统权限") {
                        Button {
                            Task { await requestMicrophonePermission() }
                        } label: {
                            PermissionStatusRow(title: "麦克风", enabled: permissionStatus.microphoneEnabled)
                        }
                        Button {
                            Task { await requestNotificationPermission() }
                        } label: {
                            PermissionStatusRow(title: "通知", enabled: permissionStatus.notificationEnabled)
                        }
                        LabeledContent("文件导入", value: permissionStatus.fileAccessLabel)
                        Button {
                            PermissionStatusReader.openAppSettings()
                        } label: {
                            Label("打开系统设置", systemImage: "gearshape")
                        }
                        Button {
                            Task { await refreshPermissionStatus() }
                        } label: {
                            Label(isRefreshingPermissions ? "刷新中" : "刷新权限状态", systemImage: "arrow.clockwise")
                        }
                        .disabled(isRefreshingPermissions)
                    }

                    Section("数据与协议") {
                        Button {
                            router.go(.userAgreement)
                        } label: {
                            Label("用户协议", systemImage: "doc.text")
                        }
                        Button {
                            router.go(.privacyPolicy)
                        } label: {
                            Label("隐私政策", systemImage: "hand.raised")
                        }
                        Button(role: .destructive) {
                            showingDeleteDataConfirm = true
                        } label: {
                            Label("删除会议数据", systemImage: "trash")
                        }
                        .disabled(isClearingData)
                    }

                    Section {
                        Button(role: .destructive) {
                            showingLogoutConfirm = true
                        } label: {
                            Label("退出登录", systemImage: "rectangle.portrait.and.arrow.right")
                        }
                        .disabled(isClearingData)
                    }
                }
                .task(id: session.profileCloudSyncFocusRequest) {
                    guard session.profileCloudSyncFocusRequest > 0 else { return }
                    withAnimation {
                        proxy.scrollTo("cloud-sync-section", anchor: .top)
                    }
                    session.consumeProfileCloudSyncFocusRequest()
                }
            }
            .navigationTitle("我的")
            .alert("修改昵称", isPresented: $showingNameEditor) {
                TextField("昵称", text: $nameDraft)
                Button("保存") {
                    session.updateProfileName(nameDraft)
                }
                Button("取消", role: .cancel) {}
            }
            .confirmationDialog("删除会议数据", isPresented: $showingDeleteDataConfirm, titleVisibility: .visible) {
                Button("确认删除", role: .destructive) {
                    Task { await clearMeetingData() }
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("将删除本地录音、转写、纪要、待办和知识库索引；已同步的数据会同时清理云端记录。")
            }
            .confirmationDialog("退出账号", isPresented: $showingLogoutConfirm, titleVisibility: .visible) {
                Button("退出并删除本机数据", role: .destructive) {
                    session.logoutAndClearLocalData()
                }
                Button("先不退出", role: .cancel) {}
            } message: {
                VStack(alignment: .leading, spacing: 8) {
                    Text("退出登录会清除本机会议、待办、预约和知识库缓存，只影响当前设备，不会主动删除云端数据。")
                    if session.unsyncedFinishedMeetingCount > 0 {
                        Text("有 \(session.unsyncedFinishedMeetingCount) 个本机会议或文件任务尚未上传到云端，确认退出后这些本机数据会被删除。")
                    }
                    if session.pendingDeleteCount > 0 {
                        Text("还有 \(session.pendingDeleteCount) 条本机删除操作尚未同步，退出后这些同步任务会停止。")
                    }
                }
            }
            .task {
                await refreshPermissionStatus()
            }
        }
    }

    private func refreshCloudData() async {
        errorMessage = nil
        statusMessage = nil
        do {
            _ = try await session.loadCloudBootstrap()
            statusMessage = "云端数据已刷新"
        } catch {
            errorMessage = userMessage(error)
        }
    }

    private func uploadUnsyncedMeetings() async {
        errorMessage = nil
        statusMessage = nil
        do {
            try await session.uploadAllUnsyncedMeetings()
            statusMessage = "未上传会议纪要已同步"
        } catch {
            errorMessage = userMessage(error)
        }
    }

    private func refreshPermissionStatus() async {
        isRefreshingPermissions = true
        defer { isRefreshingPermissions = false }
        permissionStatus = await PermissionStatusReader.read()
    }

    private func requestMicrophonePermission() async {
        isRefreshingPermissions = true
        defer { isRefreshingPermissions = false }
        _ = await PermissionStatusReader.requestMicrophonePermission()
        permissionStatus = await PermissionStatusReader.read()
    }

    private func requestNotificationPermission() async {
        isRefreshingPermissions = true
        defer { isRefreshingPermissions = false }
        _ = await PermissionStatusReader.requestNotificationPermission()
        permissionStatus = await PermissionStatusReader.read()
    }

    private func clearMeetingData() async {
        isClearingData = true
        errorMessage = nil
        statusMessage = nil
        defer { isClearingData = false }
        do {
            try await session.clearAllMeetingData()
            statusMessage = "会议数据已删除"
        } catch {
            errorMessage = userMessage(error)
        }
    }

    private var meetingCount: Int {
        session.localMeetingDetails().count
    }

    private var todoCount: Int {
        session.localMeetingDetails().reduce(0) { total, detail in
            total + (detail.result?.todos.filter { !$0.done }.count ?? 0)
        }
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }
}

private struct PermissionStatusRow: View {
    let title: String
    let enabled: Bool

    var body: some View {
        LabeledContent(title) {
            Text(enabled ? "已开启" : "未开启")
                .foregroundStyle(enabled ? HuiyiTheme.success : HuiyiTheme.warning)
        }
    }
}
