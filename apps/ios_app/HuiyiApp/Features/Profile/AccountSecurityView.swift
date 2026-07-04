import SwiftUI

struct AccountSecurityView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = AccountSecurityViewModel()

    var body: some View {
        NavigationStack {
            SmartScreenScaffold(title: "账号安全", onBack: { router.go(.profile) }) {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }
                if let statusMessage = viewModel.statusMessage {
                    Label(statusMessage, systemImage: "checkmark.circle.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(HuiyiTheme.success)
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }

                SmartInfoBlock(title: "设置或修改密码") {
                    SmartSecuritySecureField("当前密码，首次设置可留空", text: $viewModel.oldPassword)
                    SmartSecuritySecureField("新密码", text: Binding(
                        get: { viewModel.newPassword },
                        set: {
                            viewModel.newPassword = String($0.prefix(32))
                        }
                    ))
                    if let passwordHint = viewModel.passwordHint {
                        Text(passwordHint)
                            .font(.footnote)
                            .foregroundStyle(HuiyiTheme.warning)
                    }
                    PrimaryActionButton(title: "保存密码", systemImage: "key.fill") {
                        Task { await viewModel.savePassword(session: session) }
                    }
                    .disabled(!viewModel.canSavePassword || viewModel.isBusy)
                }

                SmartInfoBlock(title: "手机号换绑") {
                    SmartSecurityTextField("当前手机号", text: Binding(
                        get: { viewModel.oldPhone },
                        set: {
                            viewModel.oldPhone = $0
                            viewModel.sanitizePhoneInputs()
                        }
                    ), keyboardType: .phonePad)
                    SmartSecurityTextField("当前手机号验证码", text: Binding(
                        get: { viewModel.oldCode },
                        set: {
                            viewModel.oldCode = $0
                            viewModel.sanitizeCodeInputs()
                        }
                    ), keyboardType: .numberPad)
                    ViewThatFits {
                        HStack(spacing: 10) {
                            oldPhoneActions
                        }
                        VStack(spacing: 10) {
                            oldPhoneActions
                        }
                    }
                    SmartSecurityTextField("新手机号", text: Binding(
                        get: { viewModel.newPhone },
                        set: {
                            viewModel.newPhone = $0
                            viewModel.sanitizePhoneInputs()
                        }
                    ), keyboardType: .phonePad)
                    SmartSecurityTextField("新手机号验证码", text: Binding(
                        get: { viewModel.newCode },
                        set: {
                            viewModel.newCode = $0
                            viewModel.sanitizeCodeInputs()
                        }
                    ), keyboardType: .numberPad)
                    ViewThatFits {
                        HStack(spacing: 10) {
                            newPhoneActions
                        }
                        VStack(spacing: 10) {
                            newPhoneActions
                        }
                    }
                }
            }
            .onAppear {
                viewModel.oldPhone = session.currentUser?.phone ?? ""
            }
        }
    }

    @ViewBuilder
    private var oldPhoneActions: some View {
        SecondaryActionButton(title: viewModel.oldCodeSendTitle, systemImage: "message.fill") {
            Task { await viewModel.sendOldPhoneCode(session: session) }
        }
        .disabled(!viewModel.canSendOldPhoneCode)
        PrimaryActionButton(title: "验证当前手机号", systemImage: "checkmark.shield.fill") {
            Task { await viewModel.verifyOldPhone(session: session) }
        }
        .disabled(!viewModel.canVerifyOldPhone || viewModel.isBusy)
    }

    @ViewBuilder
    private var newPhoneActions: some View {
        SecondaryActionButton(title: viewModel.newCodeSendTitle, systemImage: "message.fill") {
            Task { await viewModel.sendNewPhoneCode(session: session) }
        }
        .disabled(!viewModel.canSendNewPhoneCode)
        PrimaryActionButton(title: "确认换绑", systemImage: "phone.fill") {
            Task { await viewModel.changePhone(session: session) }
        }
        .disabled(!viewModel.canChangePhone || viewModel.isBusy)
    }
}

private struct SmartSecurityTextField: View {
    let placeholder: String
    @Binding var text: String
    var keyboardType: UIKeyboardType = .default

    init(_ placeholder: String, text: Binding<String>, keyboardType: UIKeyboardType = .default) {
        self.placeholder = placeholder
        self._text = text
        self.keyboardType = keyboardType
    }

    var body: some View {
        TextField(placeholder, text: $text)
            .keyboardType(keyboardType)
            .font(.system(size: 15))
            .foregroundStyle(HuiyiTheme.ink)
            .padding(.horizontal, 14)
            .frame(height: 48)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(HuiyiTheme.line, lineWidth: 1))
    }
}

private struct SmartSecuritySecureField: View {
    let placeholder: String
    @Binding var text: String

    init(_ placeholder: String, text: Binding<String>) {
        self.placeholder = placeholder
        self._text = text
    }

    var body: some View {
        SecureField(placeholder, text: $text)
            .font(.system(size: 15))
            .foregroundStyle(HuiyiTheme.ink)
            .padding(.horizontal, 14)
            .frame(height: 48)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(HuiyiTheme.line, lineWidth: 1))
    }
}

@MainActor
final class AccountSecurityViewModel: ObservableObject {
    private static let passwordMinLength = 8
    private static let codeLength = 6

    @Published var oldPassword = ""
    @Published var newPassword = ""
    @Published var oldPhone = ""
    @Published var oldCode = ""
    @Published var newPhone = ""
    @Published var newCode = ""
    @Published var verificationToken = ""
    @Published var isBusy = false
    @Published var errorMessage: String?
    @Published var statusMessage: String?
    @Published var oldPhoneResendSeconds = 0
    @Published var newPhoneResendSeconds = 0

    private var oldPhoneCountdownTask: Task<Void, Never>?
    private var newPhoneCountdownTask: Task<Void, Never>?

    var canSavePassword: Bool {
        newPassword.trimmingCharacters(in: .whitespacesAndNewlines).count >= Self.passwordMinLength
    }

    var passwordHint: String? {
        let clean = newPassword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty, clean.count < Self.passwordMinLength else { return nil }
        return "密码至少 \(Self.passwordMinLength) 位"
    }

    var canChangePhone: Bool {
        !verificationToken.isEmpty &&
            oldPhoneDigits.count == 11 &&
            newPhoneDigits.count == 11 &&
            newCode.count == Self.codeLength
    }

    var canVerifyOldPhone: Bool {
        oldPhoneDigits.count == 11 && oldCode.count == Self.codeLength
    }

    var canSendOldPhoneCode: Bool {
        !isBusy && oldPhoneResendSeconds == 0 && oldPhoneDigits.count == 11
    }

    var canSendNewPhoneCode: Bool {
        !isBusy && newPhoneResendSeconds == 0 && newPhoneDigits.count == 11
    }

    var oldCodeSendTitle: String {
        oldPhoneResendSeconds > 0 ? "\(oldPhoneResendSeconds)s" : "发送当前手机号验证码"
    }

    var newCodeSendTitle: String {
        newPhoneResendSeconds > 0 ? "\(newPhoneResendSeconds)s" : "发送新手机号验证码"
    }

    func sanitizePhoneInputs() {
        oldPhone = String(oldPhone.filter(\.isNumber).prefix(11))
        newPhone = String(newPhone.filter(\.isNumber).prefix(11))
    }

    func sanitizeCodeInputs() {
        oldCode = String(oldCode.filter(\.isNumber).prefix(Self.codeLength))
        newCode = String(newCode.filter(\.isNumber).prefix(Self.codeLength))
    }

    func savePassword(session: AppSession) async {
        guard canSavePassword else { return }
        isBusy = true
        errorMessage = nil
        statusMessage = nil
        defer { isBusy = false }
        do {
            if oldPassword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                try await session.setPassword(newPassword)
            } else {
                try await session.changePassword(oldPassword: oldPassword, newPassword: newPassword)
            }
            oldPassword = ""
            newPassword = ""
            statusMessage = "密码已保存"
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func verifyOldPhone(session: AppSession) async {
        guard canVerifyOldPhone else { return }
        isBusy = true
        errorMessage = nil
        statusMessage = nil
        defer { isBusy = false }
        do {
            verificationToken = try await session.verifyCurrentPhoneForChange(oldPhone: oldPhoneDigits, oldCode: oldCode)
            statusMessage = "当前手机号已验证"
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func sendNewPhoneCode(session: AppSession) async {
        guard canSendNewPhoneCode else { return }
        isBusy = true
        errorMessage = nil
        statusMessage = nil
        defer { isBusy = false }
        do {
            _ = try await session.sendSmsCode(phone: newPhoneDigits, scene: .changePhone)
            statusMessage = "验证码已发送"
            startNewPhoneCountdown()
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func sendOldPhoneCode(session: AppSession) async {
        guard canSendOldPhoneCode else { return }
        isBusy = true
        errorMessage = nil
        statusMessage = nil
        defer { isBusy = false }
        do {
            _ = try await session.sendSmsCode(phone: oldPhoneDigits, scene: .changePhone)
            statusMessage = "验证码已发送"
            startOldPhoneCountdown()
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func changePhone(session: AppSession) async {
        guard canChangePhone else { return }
        isBusy = true
        errorMessage = nil
        statusMessage = nil
        defer { isBusy = false }
        do {
            try await session.changePhone(oldPhone: oldPhoneDigits, verificationToken: verificationToken, newPhone: newPhoneDigits, newCode: newCode)
            oldCode = ""
            newCode = ""
            verificationToken = ""
            oldPhone = newPhoneDigits
            newPhone = ""
            oldPhoneResendSeconds = 0
            newPhoneResendSeconds = 0
            statusMessage = "手机号已换绑"
        } catch {
            errorMessage = userMessage(error)
        }
    }

    deinit {
        oldPhoneCountdownTask?.cancel()
        newPhoneCountdownTask?.cancel()
    }

    private var oldPhoneDigits: String {
        oldPhone.filter(\.isNumber)
    }

    private var newPhoneDigits: String {
        newPhone.filter(\.isNumber)
    }

    private func startOldPhoneCountdown() {
        oldPhoneCountdownTask?.cancel()
        oldPhoneResendSeconds = 60
        oldPhoneCountdownTask = Task { [weak self] in
            await self?.runCountdown(kind: .oldPhone)
        }
    }

    private func startNewPhoneCountdown() {
        newPhoneCountdownTask?.cancel()
        newPhoneResendSeconds = 60
        newPhoneCountdownTask = Task { [weak self] in
            await self?.runCountdown(kind: .newPhone)
        }
    }

    private enum CountdownKind {
        case oldPhone
        case newPhone
    }

    private func runCountdown(kind: CountdownKind) async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            if Task.isCancelled { return }
            switch kind {
            case .oldPhone:
                if oldPhoneResendSeconds <= 1 {
                    oldPhoneResendSeconds = 0
                    return
                }
                oldPhoneResendSeconds -= 1
            case .newPhone:
                if newPhoneResendSeconds <= 1 {
                    newPhoneResendSeconds = 0
                    return
                }
                newPhoneResendSeconds -= 1
            }
        }
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }
}
