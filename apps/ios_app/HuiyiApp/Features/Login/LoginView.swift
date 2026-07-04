import SwiftUI

struct LoginView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = LoginViewModel()

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 0) {
                    loginHero(height: heroHeight(for: geometry.size))
                    loginCard
                        .padding(.horizontal, geometry.size.width < 360 ? 18 : 22)
                        .offset(y: -10)
                    Spacer(minLength: 24)
                }
                .frame(minHeight: geometry.size.height)
            }
            .scrollIndicators(.hidden)
            .background(
                ZStack(alignment: .top) {
                    HuiyiTheme.appBg
                    VStack(spacing: 0) {
                        RoundedRectangle(cornerRadius: 36, style: .continuous)
                            .fill(
                                LinearGradient(
                                    colors: [HuiyiTheme.brandCyan, Color(red: 0.294, green: 0.482, blue: 1.000), HuiyiTheme.brand],
                                    startPoint: .top,
                                    endPoint: .bottom
                                )
                            )
                            .frame(height: heroHeight(for: geometry.size) + 58)
                        Spacer()
                    }
                    VStack {
                        Spacer()
                        LinearGradient(colors: [.clear, HuiyiTheme.brandSoftCyan.opacity(0.72)], startPoint: .top, endPoint: .bottom)
                            .frame(height: geometry.size.height < 720 ? 130 : 190)
                    }
                }
                .ignoresSafeArea()
            )
        }
        .navigationBarHidden(true)
    }

    private func heroHeight(for size: CGSize) -> CGFloat {
        if size.width < 360 || size.height < 720 { return 210 }
        if size.width < 400 { return 236 }
        return 252
    }

    private func loginHero(height: CGFloat) -> some View {
        VStack(spacing: 10) {
            Spacer(minLength: 20)
            ZStack {
                RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .fill(Color.white.opacity(0.18))
                    .overlay(
                        RoundedRectangle(cornerRadius: 26, style: .continuous)
                            .stroke(Color.white.opacity(0.28), lineWidth: 1)
                    )
                Image("KunqiongLogo")
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .padding(9)
            }
            .frame(width: 90, height: 90)

            Text("鲲穹会纪")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.8)

            Text("鲲穹AI旗下产品")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(Color.white.opacity(0.92))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Color.white.opacity(0.16), in: Capsule())
                .overlay(Capsule().stroke(Color.white.opacity(0.24), lineWidth: 1))

            Spacer(minLength: 18)
        }
        .frame(maxWidth: .infinity)
        .frame(height: height)
        .padding(.horizontal, 24)
        .padding(.top, 24)
    }

    private var loginCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            if viewModel.requiresNewPassword {
                flowHeader
            } else {
                SmartSegmentedPicker(
                    items: LoginViewModel.Mode.loginModes,
                    selection: Binding(get: { viewModel.mode }, set: { viewModel.changeMode($0) }),
                    title: \.title,
                    enabled: !viewModel.isLoading
                )
            }

            SmartLoginInputField(
                label: "手机号",
                placeholder: "请输入手机号",
                text: Binding(
                    get: { viewModel.phone },
                    set: {
                        viewModel.phone = $0
                        viewModel.sanitizePhoneInput()
                    }
                ),
                keyboardType: .phonePad,
                textContentType: .telephoneNumber,
                enabled: !viewModel.isLoading
            )

            if viewModel.requiresCode {
                SmartLoginCodeField(
                    value: Binding(
                        get: { viewModel.code },
                        set: {
                            viewModel.code = $0
                            viewModel.sanitizeCodeInput()
                        }
                    ),
                    sendTitle: viewModel.sendCodeTitle,
                    canSend: viewModel.canSendCode,
                    enabled: !viewModel.isLoading,
                    onSend: {
                        Task { await viewModel.sendCode(session: session) }
                    }
                )
            }

            if viewModel.mode == .password || viewModel.requiresNewPassword {
                SmartLoginInputField(
                    label: viewModel.mode == .reset ? "新密码" : "密码",
                    placeholder: viewModel.requiresNewPassword ? "8-32 位字母和数字" : "请输入密码",
                    text: Binding(
                        get: { viewModel.password },
                        set: {
                            viewModel.password = $0
                            viewModel.sanitizePasswordInputs()
                        }
                    ),
                    keyboardType: .default,
                    textContentType: viewModel.requiresNewPassword ? .newPassword : .password,
                    isSecure: true,
                    enabled: !viewModel.isLoading,
                    supportingText: viewModel.passwordHint
                )
            }

            if viewModel.requiresNewPassword {
                SmartLoginInputField(
                    label: "确认密码",
                    placeholder: "再次输入密码",
                    text: Binding(
                        get: { viewModel.confirmPassword },
                        set: {
                            viewModel.confirmPassword = $0
                            viewModel.sanitizePasswordInputs()
                        }
                    ),
                    keyboardType: .default,
                    textContentType: .newPassword,
                    isSecure: true,
                    enabled: !viewModel.isLoading
                )
            }

            if !viewModel.requiresNewPassword {
                loginLinks
            }

            if let smsHint = viewModel.smsHint, !smsHint.isEmpty {
                LoginStatusStrip(text: smsHint, tone: .success)
            }
            if let errorMessage = viewModel.errorMessage, !errorMessage.isEmpty {
                LoginStatusStrip(text: errorMessage, tone: .danger)
            }

            agreementRow

            Button {
                Task { await viewModel.submit(session: session) }
            } label: {
                HStack(spacing: 8) {
                    if viewModel.isLoading {
                        ProgressView()
                            .tint(submitLooksEnabled ? .white : HuiyiTheme.muted)
                    }
                    Text(viewModel.mode.submitTitle)
                        .font(.system(size: 17, weight: .bold))
                }
                .foregroundStyle(submitLooksEnabled ? .white : Color(red: 0.541, green: 0.588, blue: 0.667))
                .frame(maxWidth: .infinity, minHeight: 54)
                .background(
                    submitLooksEnabled ? AnyShapeStyle(HuiyiTheme.primaryGradient) : AnyShapeStyle(Color(red: 0.910, green: 0.933, blue: 0.973)),
                    in: Capsule()
                )
            }
            .buttonStyle(.plain)
            .disabled(submitDisabled)
        }
        .padding(.horizontal, 24)
        .padding(.top, 22)
        .padding(.bottom, 24)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 32, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 32, style: .continuous)
                .stroke(Color.white.opacity(0.82), lineWidth: 1)
        )
        .shadow(color: HuiyiTheme.brandDark.opacity(0.16), radius: 18, x: 0, y: 10)
    }

    private var flowHeader: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 4) {
                Text(viewModel.mode.flowTitle)
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(HuiyiTheme.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
                Text(viewModel.mode.flowSubtitle)
                    .font(.system(size: 13))
                    .foregroundStyle(HuiyiTheme.muted)
                    .lineLimit(2)
            }
            Spacer()
            Button("返回登录") {
                viewModel.changeMode(.sms)
            }
            .font(.system(size: 14, weight: .bold))
            .foregroundStyle(HuiyiTheme.brand)
            .buttonStyle(.plain)
        }
    }

    private var submitLooksEnabled: Bool {
        viewModel.canSubmit || !viewModel.agreementAccepted
    }

    private var submitDisabled: Bool {
        viewModel.isLoading || (viewModel.agreementAccepted && !viewModel.canSubmit)
    }

    private var loginLinks: some View {
        HStack {
            Button("忘记密码") {
                viewModel.changeMode(.reset)
            }
            .foregroundStyle(HuiyiTheme.muted)
            Spacer()
            Button("免费注册账号") {
                viewModel.changeMode(.register)
            }
            .foregroundStyle(HuiyiTheme.brand)
        }
        .font(.system(size: 14, weight: .bold))
        .buttonStyle(.plain)
    }

    private var agreementRow: some View {
        HStack(alignment: .center, spacing: 8) {
            Button {
                viewModel.agreementAccepted.toggle()
            } label: {
                Image(systemName: viewModel.agreementAccepted ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(viewModel.agreementAccepted ? HuiyiTheme.brand : HuiyiTheme.muted.opacity(0.76))
                    .frame(width: 34, height: 34)
            }
            .buttonStyle(.plain)

            Text("我已阅读并同意")
                .foregroundStyle(HuiyiTheme.muted)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
            Button("《用户协议》") { router.go(.userAgreement) }
                .foregroundStyle(HuiyiTheme.brand)
            Text("和")
                .foregroundStyle(HuiyiTheme.muted)
            Button("《隐私政策》") { router.go(.privacyPolicy) }
                .foregroundStyle(HuiyiTheme.brand)
        }
        .font(.system(size: 12, weight: .bold))
        .buttonStyle(.plain)
        .lineLimit(1)
        .minimumScaleFactor(0.68)
    }
}

private struct SmartLoginInputField: View {
    let label: String
    let placeholder: String
    @Binding var text: String
    var keyboardType: UIKeyboardType
    var textContentType: UITextContentType?
    var isSecure = false
    var enabled = true
    var supportingText: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(HuiyiTheme.ink)
            Group {
                if isSecure {
                    SecureField(placeholder, text: $text)
                } else {
                    TextField(placeholder, text: $text)
                }
            }
            .keyboardType(keyboardType)
            .textContentType(textContentType)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .font(.system(size: 16, weight: .medium))
            .foregroundStyle(HuiyiTheme.ink)
            .disabled(!enabled)
            .padding(.horizontal, 16)
            .frame(height: 58)
            .background(Color(red: 0.973, green: 0.984, blue: 1.000), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(Color(red: 0.863, green: 0.906, blue: 0.961), lineWidth: 1)
            )

            if let supportingText, !supportingText.isEmpty {
                Text(supportingText)
                    .font(.system(size: 12))
                    .foregroundStyle(HuiyiTheme.danger)
            }
        }
    }
}

private struct SmartLoginCodeField: View {
    @Binding var value: String
    let sendTitle: String
    let canSend: Bool
    let enabled: Bool
    let onSend: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("验证码")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(HuiyiTheme.ink)
            HStack(spacing: 8) {
                TextField("请输入验证码", text: $value)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    .font(.system(size: 16, weight: .medium))
                    .disabled(!enabled)
                Rectangle()
                    .fill(HuiyiTheme.line)
                    .frame(width: 1, height: 22)
                Button(sendTitle, action: onSend)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(canSend ? HuiyiTheme.brand : HuiyiTheme.muted.opacity(0.64))
                    .frame(width: 96, height: 42)
                    .disabled(!canSend)
            }
            .padding(.leading, 16)
            .padding(.trailing, 8)
            .frame(height: 58)
            .background(Color(red: 0.973, green: 0.984, blue: 1.000), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(Color(red: 0.863, green: 0.906, blue: 0.961), lineWidth: 1)
            )
        }
    }
}

private struct LoginStatusStrip: View {
    enum Tone {
        case success
        case danger

        var container: Color {
            switch self {
            case .success: return Color(red: 0.918, green: 0.973, blue: 0.961)
            case .danger: return Color(red: 1.000, green: 0.941, blue: 0.929)
            }
        }

        var border: Color {
            switch self {
            case .success: return Color(red: 0.796, green: 0.925, blue: 0.902)
            case .danger: return Color(red: 1.000, green: 0.788, blue: 0.745)
            }
        }

        var color: Color {
            switch self {
            case .success: return HuiyiTheme.brandDark
            case .danger: return Color(red: 0.761, green: 0.239, blue: 0.165)
            }
        }
    }

    let text: String
    let tone: Tone

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: tone == .success ? "checkmark.shield.fill" : "exclamationmark.triangle.fill")
                .foregroundStyle(tone.color)
            Text(text)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(tone.color)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tone.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(tone.border, lineWidth: 1))
    }
}

private extension LoginViewModel.Mode {
    static var loginModes: [LoginViewModel.Mode] {
        [.password, .sms]
    }

    var flowTitle: String {
        switch self {
        case .register: return "免费注册账号"
        case .reset: return "忘记密码"
        case .password, .sms: return ""
        }
    }

    var flowSubtitle: String {
        switch self {
        case .register: return "验证手机号后设置登录密码"
        case .reset: return "通过手机号验证码重置密码"
        case .password, .sms: return ""
        }
    }
}
