import Foundation

@MainActor
final class LoginViewModel: ObservableObject {
    static let minPasswordLength = 8
    static let codeLength = 6

    enum Mode: String, CaseIterable, Identifiable {
        case password
        case sms
        case register
        case reset

        var id: String { rawValue }
        var title: String {
            switch self {
            case .password: return "密码登录"
            case .sms: return "验证码登录"
            case .register: return "注册"
            case .reset: return "重置密码"
            }
        }

        var submitTitle: String {
            switch self {
            case .password, .sms: return "登录"
            case .register: return "注册并登录"
            case .reset: return "重置并登录"
            }
        }

        var smsScene: SmsCodeScene {
            switch self {
            case .password, .sms: return .login
            case .register: return .register
            case .reset: return .changePassword
            }
        }
    }

    @Published var mode: Mode = .password
    @Published var phone = ""
    @Published var password = ""
    @Published var confirmPassword = ""
    @Published var code = ""
    @Published var agreementAccepted: Bool {
        didSet {
            settingsStore.loginAgreementAccepted = agreementAccepted
        }
    }
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var smsHint: String?
    @Published var resendSeconds = 0

    private let settingsStore: SettingsStore
    private var resendTask: Task<Void, Never>?

    init(settingsStore: SettingsStore = SettingsStore()) {
        self.settingsStore = settingsStore
        _agreementAccepted = Published(initialValue: settingsStore.loginAgreementAccepted)
    }

    var canSubmit: Bool {
        guard agreementAccepted else { return false }
        let phoneReady = sanitizedPhone.count >= 11
        switch mode {
        case .password:
            return phoneReady && sanitizedPassword.count >= Self.minPasswordLength
        case .sms:
            return phoneReady && sanitizedCode.count == Self.codeLength
        case .register, .reset:
            return phoneReady &&
                sanitizedCode.count == Self.codeLength &&
                sanitizedPassword.count >= Self.minPasswordLength &&
                !sanitizedConfirmPassword.isEmpty
        }
    }

    var canSendCode: Bool {
        !isLoading && resendSeconds == 0 && sanitizedPhone.count >= 11
    }

    var sendCodeTitle: String {
        resendSeconds > 0 ? "\(resendSeconds)s" : "获取验证码"
    }

    var passwordHint: String? {
        guard mode == .register || mode == .reset else { return nil }
        guard !sanitizedPassword.isEmpty && sanitizedPassword.count < Self.minPasswordLength else { return nil }
        return "密码至少 \(Self.minPasswordLength) 位"
    }

    var requiresCode: Bool {
        mode != .password
    }

    var requiresNewPassword: Bool {
        mode == .register || mode == .reset
    }

    func sanitizePhoneInput() {
        let clean = phone.filter { $0.isNumber || $0 == "+" }
        if clean != phone {
            phone = String(clean.prefix(14))
        } else if phone.count > 14 {
            phone = String(phone.prefix(14))
        }
    }

    func sanitizeCodeInput() {
        let clean = code.filter(\.isNumber)
        if clean != code {
            code = String(clean.prefix(Self.codeLength))
        } else if code.count > Self.codeLength {
            code = String(code.prefix(Self.codeLength))
        }
    }

    func sanitizePasswordInputs() {
        if password.count > 32 {
            password = String(password.prefix(32))
        }
        if confirmPassword.count > 32 {
            confirmPassword = String(confirmPassword.prefix(32))
        }
    }

    func changeMode(_ nextMode: Mode) {
        guard mode != nextMode else { return }
        mode = nextMode
        code = ""
        password = ""
        confirmPassword = ""
        errorMessage = nil
        smsHint = nil
        resendSeconds = 0
        resendTask?.cancel()
    }

    func submit(session: AppSession) async {
        guard agreementAccepted else {
            errorMessage = "请先阅读并同意用户协议和隐私政策"
            return
        }
        guard canSubmit else { return }
        if requiresNewPassword && sanitizedPassword != sanitizedConfirmPassword {
            errorMessage = "两次输入的密码不一致"
            return
        }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            switch mode {
            case .password:
                try await session.loginByPassword(phone: sanitizedPhone, password: sanitizedPassword)
            case .sms:
                try await session.loginBySms(phone: sanitizedPhone, code: sanitizedCode)
            case .register:
                try await session.registerByPassword(phone: sanitizedPhone, code: sanitizedCode, password: sanitizedPassword)
            case .reset:
                try await session.resetPassword(phone: sanitizedPhone, code: sanitizedCode, password: sanitizedPassword)
            }
        } catch {
            handleSubmitError(error, phone: sanitizedPhone, code: sanitizedCode)
        }
    }

    func sendCode(session: AppSession) async {
        guard agreementAccepted else {
            errorMessage = "请先阅读并同意用户协议和隐私政策"
            return
        }
        guard canSendCode else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let response = try await session.sendSmsCode(phone: sanitizedPhone, scene: mode.smsScene)
            smsHint = response.message
            startResendCountdown()
        } catch {
            handleSmsError(error, phone: sanitizedPhone)
        }
    }

    deinit {
        resendTask?.cancel()
    }

    private var sanitizedPhone: String {
        phone.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var sanitizedPassword: String {
        password.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var sanitizedConfirmPassword: String {
        confirmPassword.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var sanitizedCode: String {
        code.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func startResendCountdown() {
        resendTask?.cancel()
        resendSeconds = 60
        resendTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self, !Task.isCancelled else { return }
                if self.resendSeconds <= 1 {
                    self.resendSeconds = 0
                    return
                }
                self.resendSeconds -= 1
            }
        }
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }

    private func handleSmsError(_ error: Error, phone: String) {
        if case let APIError.httpStatus(status, message) = error {
            if status == 404 && message == "ACCOUNT_NOT_REGISTERED" {
                phoneSwitch(to: .register, phone: phone, message: "该手机号未注册，请先注册")
                return
            }
            if status == 409 && message == "ACCOUNT_ALREADY_REGISTERED" {
                phoneSwitch(to: .sms, phone: phone, message: "该手机号已注册，请直接登录")
                return
            }
        }
        errorMessage = userMessage(error)
    }

    private func handleSubmitError(_ error: Error, phone: String, code: String) {
        if case let APIError.httpStatus(status, message) = error {
            if mode == .sms && status == 404 && message == "ACCOUNT_NOT_REGISTERED" {
                phoneSwitch(to: .register, phone: phone, code: code, message: "该手机号未注册，请设置密码完成注册")
                return
            }
            if mode == .register && status == 409 && message == "ACCOUNT_ALREADY_REGISTERED" {
                phoneSwitch(to: .sms, phone: phone, code: code, message: "该手机号已注册，请直接登录")
                return
            }
        }
        errorMessage = userMessage(error)
    }

    private func phoneSwitch(to nextMode: Mode, phone: String, code: String = "", message: String) {
        mode = nextMode
        self.phone = phone
        self.code = code
        password = ""
        confirmPassword = ""
        resendSeconds = 0
        resendTask?.cancel()
        smsHint = nil
        errorMessage = message
    }
}
