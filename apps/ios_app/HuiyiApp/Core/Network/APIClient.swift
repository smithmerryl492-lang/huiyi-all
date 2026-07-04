import Foundation

final class APIClient {
    typealias AccessTokenProvider = () -> String?
    typealias AuthFailureHandler = @MainActor (String) -> Void

    let baseURL: URL
    var accessTokenProvider: AccessTokenProvider?
    var authFailureHandler: AuthFailureHandler?

    private let session: URLSession
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
        encoder = JSONEncoder()
        decoder = JSONDecoder()
    }

    func sendSmsCode(phone: String, scene: SmsCodeScene) async throws -> SmsCodeResponse {
        try await request(APIEndpoint.sendSmsCode, body: SmsCodeRequest(phone: phone, scene: scene))
    }

    func loginBySms(phone: String, code: String) async throws -> LoginResponse {
        try await request(APIEndpoint.smsLogin, body: SmsLoginRequest(phone: phone, code: code))
    }

    func loginByPassword(phone: String, password: String) async throws -> LoginResponse {
        try await request(APIEndpoint.passwordLogin, body: PasswordLoginRequest(phone: phone, password: password))
    }

    func registerByPassword(phone: String, code: String, password: String) async throws -> LoginResponse {
        try await request(APIEndpoint.passwordRegister, body: PasswordRegisterRequest(phone: phone, code: code, password: password))
    }

    func resetPassword(phone: String, code: String, password: String) async throws -> LoginResponse {
        try await request(APIEndpoint.passwordReset, body: PasswordResetRequest(phone: phone, code: code, password: password))
    }

    func setPassword(user: CloudUser, password: String) async throws -> MessageResponse {
        try await request(APIEndpoint.passwordSet, user: user, body: PasswordSetRequest(password: password))
    }

    func changePassword(user: CloudUser, oldPassword: String, newPassword: String) async throws -> MessageResponse {
        try await request(APIEndpoint.passwordChange, user: user, body: PasswordChangeRequest(oldPassword: oldPassword, newPassword: newPassword))
    }

    func verifyCurrentPhoneForChange(user: CloudUser, oldPhone: String, oldCode: String) async throws -> PhoneChangeVerifyResponse {
        try await request(APIEndpoint.verifyPhoneChange, user: user, body: PhoneChangeVerifyRequest(oldPhone: oldPhone, oldCode: oldCode))
    }

    func changePhone(
        user: CloudUser,
        oldPhone: String,
        oldVerificationToken: String,
        newPhone: String,
        newCode: String
    ) async throws -> LoginResponse {
        try await request(
            APIEndpoint.changePhone,
            user: user,
            body: PhoneChangeRequest(
                oldPhone: oldPhone,
                oldVerificationToken: oldVerificationToken,
                newPhone: newPhone,
                newCode: newCode
            )
        )
    }

    func getMembershipProfile(user: CloudUser) async throws -> MembershipProfile {
        try await request(APIEndpoint.membershipMe, user: user)
    }

    func createLiveSession(user: CloudUser) async throws -> LiveDirectSession {
        try await request(APIEndpoint.liveSession, user: user)
    }

    func bootstrapCloud(user: CloudUser) async throws -> CloudBootstrapResponse {
        try await request(APIEndpoint.syncBootstrap, user: user)
    }

    func listOrders(user: CloudUser) async throws -> [PaymentOrder] {
        let response: PaymentOrderListResponse = try await request(APIEndpoint.paymentOrders, user: user)
        return response.items
    }

    func getOrder(_ id: String, user: CloudUser) async throws -> PaymentOrder? {
        let response: PaymentOrderResponse = try await request(.paymentOrder(id), user: user)
        return response.order
    }

    func syncAlipayPaymentOrder(_ id: String, user: CloudUser) async throws -> PaymentOrder? {
        let response: PaymentOrderSyncResponse = try await request(.syncAlipayOrder(id), user: user)
        return response.order
    }

    func confirmAppleTransaction(_ body: AppleTransactionConfirmRequest, user: CloudUser) async throws -> PaymentOrder? {
        let response: PaymentOrderResponse = try await request(.confirmAppleTransaction, user: user, body: body)
        return response.order
    }

    func getTask(_ id: String, user: CloudUser) async throws -> RemoteTaskDetail {
        try await request(.task(id), user: user)
    }

    func updateTask(_ id: String, user: CloudUser, body: TaskUpdateRequest) async throws -> RemoteMeetingTask {
        try await request(.updateTask(id), user: user, body: body)
    }

    func processTask(_ id: String, user: CloudUser, context: TaskProcessingContextRequest) async throws -> RemoteTaskDetail {
        try await request(.processTask(id), user: user, body: context)
    }

    func retryTask(_ id: String, user: CloudUser, context: TaskProcessingContextRequest) async throws -> RemoteTaskDetail {
        try await request(.retryTask(id), user: user, body: context)
    }

    func cancelTask(_ id: String, user: CloudUser) async throws -> RemoteMeetingTask {
        try await request(.cancelTask(id), user: user)
    }

    func deleteTask(_ id: String, user: CloudUser) async throws -> MessageResponse {
        try await request(.deleteTask(id), user: user)
    }

    func getTaskResult(_ id: String, user: CloudUser) async throws -> MeetingProcessingResult {
        try await request(.taskResult(id), user: user)
    }

    func updateTaskResult(_ id: String, user: CloudUser, body: ResultUpdateRequest) async throws -> MeetingProcessingResult {
        try await request(.updateTaskResult(id), user: user, body: body)
    }

    func regenerateMinutes(_ id: String, user: CloudUser, transcripts: [TranscriptSegment]) async throws -> MeetingProcessingResult {
        try await request(.regenerateMinutes(id), user: user, body: RegenerateMinutesRequest(transcripts: transcripts))
    }

    func regenerateLocalMinutes(_ body: RegenerateLocalMinutesRequest, user: CloudUser) async throws -> MeetingProcessingResult {
        try await request(.regenerateLocalMinutes, user: user, body: body)
    }

    func exportTaskText(_ id: String, user: CloudUser, format: String = "markdown", includeTranscript: Bool = false) async throws -> String {
        let data = try await requestData(.exportTask(id, format: format, includeTranscript: includeTranscript), user: user)
        return String(data: data, encoding: .utf8) ?? ""
    }

    func downloadTaskAudio(_ id: String, user: CloudUser) async throws -> Data {
        try await requestData(.taskAudio(id), user: user, timeout: 600)
    }

    func upsertSchedule(_ schedule: ScheduledMeeting, user: CloudUser) async throws -> ScheduledMeeting {
        try await request(.upsertSchedule(schedule.id), user: user, body: schedule)
    }

    func deleteSchedule(_ id: String, user: CloudUser) async throws -> MessageResponse {
        try await request(.deleteSchedule(id), user: user)
    }

    func clearCloudData(user: CloudUser) async throws -> MessageResponse {
        try await request(APIEndpoint.clearCloudData, user: user)
    }

    func askKnowledge(_ requestBody: KnowledgeAskRequest, user: CloudUser) async throws -> KnowledgeAskResponse {
        try await request(APIEndpoint.knowledgeAsk, user: user, body: requestBody)
    }

    func listSpeakerProfiles(user: CloudUser) async throws -> [SpeakerProfile] {
        try await request(APIEndpoint.speakerProfiles, user: user)
    }

    func enrollSpeakerProfileFromTask(
        user: CloudUser,
        taskId: String,
        speakerId: String,
        speakerName: String,
        displayName: String
    ) async throws -> SpeakerProfile {
        try await request(
            APIEndpoint.enrollSpeakerFromTask,
            user: user,
            body: SpeakerProfileEnrollFromTaskRequest(taskId: taskId, speakerId: speakerId, speakerName: speakerName, displayName: displayName)
        )
    }

    func enrollSpeakerProfileFromAudio(
        user: CloudUser,
        displayName: String,
        localFileURL: URL,
        profileId: String? = nil
    ) async throws -> SpeakerProfile {
        guard FileManager.default.fileExists(atPath: localFileURL.path) else {
            throw APIError.fileMissing(localFileURL.path)
        }
        return try await uploadMultipart(
            APIEndpoint.enrollSpeakerFromAudio,
            fileURL: localFileURL,
            user: user,
            fields: [
                "display_name": displayName,
                "profile_id": profileId ?? ""
            ].filter { !$0.value.isEmpty }
        )
    }

    func updateSpeakerProfile(user: CloudUser, profileId: String, displayName: String? = nil, active: Bool? = nil) async throws -> SpeakerProfile {
        try await request(
            .updateSpeakerProfile(profileId),
            user: user,
            body: SpeakerProfileUpdateRequest(displayName: displayName, active: active)
        )
    }

    func deleteSpeakerProfile(user: CloudUser, profileId: String) async throws -> SpeakerProfileDeleteResponse {
        try await request(.deleteSpeakerProfile(profileId), user: user)
    }

    func uploadFile(
        fileURL: URL,
        user: CloudUser,
        source: MeetingTaskSource,
        clientTaskId: String,
        confirmed: Bool,
        isPrivate: Bool,
        deviceId: String?,
        createdAtMillis: Int64?,
        persistToCloud: Bool
    ) async throws -> UploadResponse {
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            throw APIError.fileMissing(fileURL.path)
        }
        var endpoint = APIEndpoint.uploadFile(
            source: source,
            clientTaskId: persistToCloud ? clientTaskId : nil,
            confirmed: confirmed,
            isPrivate: isPrivate,
            deviceId: deviceId,
            createdAtMillis: createdAtMillis
        )
        endpoint.queryItems.append(URLQueryItem(name: "persist_to_cloud", value: persistToCloud ? "true" : "false"))
        return try await uploadMultipart(endpoint, fileURL: fileURL, user: user)
    }

    func request<Response: Decodable>(_ endpoint: APIEndpoint, user: CloudUser? = nil) async throws -> Response {
        var request = try makeURLRequest(endpoint, user: user)
        request.httpMethod = endpoint.method.rawValue
        let (data, response) = try await session.data(for: request)
        return try await decodeResponse(data: data, response: response)
    }

    func request<Body: Encodable, Response: Decodable>(_ endpoint: APIEndpoint, user: CloudUser? = nil, body: Body) async throws -> Response {
        var request = try makeURLRequest(endpoint, user: user)
        request.httpMethod = endpoint.method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        do {
            request.httpBody = try encoder.encode(body)
        } catch {
            throw APIError.encoding(error.localizedDescription)
        }
        let (data, response) = try await session.data(for: request)
        return try await decodeResponse(data: data, response: response)
    }

    func requestData(_ endpoint: APIEndpoint, user: CloudUser? = nil, timeout: TimeInterval = 30) async throws -> Data {
        var request = try makeURLRequest(endpoint, user: user)
        request.httpMethod = endpoint.method.rawValue
        request.timeoutInterval = timeout
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(httpResponse.statusCode) else {
            let message = extractErrorMessage(from: data)
            await notifyAuthFailureIfNeeded(statusCode: httpResponse.statusCode, message: message)
            throw APIError.httpStatus(httpResponse.statusCode, message)
        }
        return data
    }

    private func uploadMultipart<Response: Decodable>(
        _ endpoint: APIEndpoint,
        fileURL: URL,
        user: CloudUser,
        fields: [String: String] = [:]
    ) async throws -> Response {
        let boundary = "Boundary-\(UUID().uuidString)"
        var request = try makeURLRequest(endpoint, user: user)
        request.httpMethod = endpoint.method.rawValue
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        let body = try multipartBody(fileURL: fileURL, fieldName: "file", boundary: boundary, fields: fields)
        let (data, response) = try await session.upload(for: request, from: body)
        return try await decodeResponse(data: data, response: response)
    }

    private func makeURLRequest(_ endpoint: APIEndpoint, user: CloudUser?) throws -> URLRequest {
        let base = baseURL.absoluteString.trimmedTrailingSlash
        let urlString = "\(base)/\(endpoint.path.trimmedLeadingSlash)"
        guard var components = URLComponents(string: urlString) else {
            throw APIError.invalidURL
        }
        components.queryItems = endpoint.queryItems.isEmpty ? nil : endpoint.queryItems
        guard let url = components.url else { throw APIError.invalidURL }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        let token = user?.accessToken ?? accessTokenProvider?()
        if let token, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        request.setValue("huiyi-ios/0.1", forHTTPHeaderField: "User-Agent")
        return request
    }

    private func decodeResponse<Response: Decodable>(data: Data, response: URLResponse) async throws -> Response {
        guard let httpResponse = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(httpResponse.statusCode) else {
            let message = extractErrorMessage(from: data)
            await notifyAuthFailureIfNeeded(statusCode: httpResponse.statusCode, message: message)
            throw APIError.httpStatus(httpResponse.statusCode, message)
        }
        guard !data.isEmpty else {
            if Response.self == EmptyResponse.self, let empty = EmptyResponse() as? Response {
                return empty
            }
            if Response.self == MessageResponse.self, let message = MessageResponse() as? Response {
                return message
            }
            if Response.self == SpeakerProfileDeleteResponse.self,
               let deleteResponse = SpeakerProfileDeleteResponse() as? Response {
                return deleteResponse
            }
            throw APIError.emptyResponse
        }
        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            throw APIError.decoding(error.localizedDescription)
        }
    }

    private func extractErrorMessage(from data: Data) -> String {
        guard !data.isEmpty else { return "" }
        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let detail = object["detail"] as? String { return detail }
            if let message = object["message"] as? String { return message }
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    private func notifyAuthFailureIfNeeded(statusCode: Int, message: String) async {
        guard statusCode == 401 else { return }
        let cleanMessage = message.trimmingCharacters(in: .whitespacesAndNewlines)
        await authFailureHandler?(cleanMessage.isEmpty ? "登录已过期，请重新登录" : cleanMessage)
    }

    private func multipartBody(fileURL: URL, fieldName: String, boundary: String, fields: [String: String] = [:]) throws -> Data {
        var body = Data()
        for (name, value) in fields {
            body.appendString("--\(boundary)\r\n")
            body.appendString("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n")
            body.appendString("\(value)\r\n")
        }
        let filename = fileURL.lastPathComponent
        let asciiFilename = filename.asciiUploadFilename
        let encodedFilename = filename.percentEncodedUploadFilename
        let mimeType = MIMEType.guess(for: filename)
        body.appendString("--\(boundary)\r\n")
        body.appendString("Content-Disposition: form-data; name=\"\(fieldName)\"; filename=\"\(asciiFilename)\"; filename*=UTF-8''\(encodedFilename)\r\n")
        body.appendString("Content-Type: \(mimeType)\r\n\r\n")
        body.append(try Data(contentsOf: fileURL))
        body.appendString("\r\n--\(boundary)--\r\n")
        return body
    }
}

struct EmptyResponse: Decodable, Sendable {}

enum SmsCodeScene: String, Codable, Sendable {
    case login
    case register
    case changePhone = "change_phone"
    case changePassword = "change_password"
}

struct SmsCodeRequest: Codable, Sendable {
    let phone: String
    let scene: SmsCodeScene
}

struct SmsCodeResponse: Codable, Sendable {
    let message: String
    let expiresIn: Int
    let resendAfter: Int

    enum CodingKeys: String, CodingKey {
        case message
        case expiresIn = "expires_in"
        case resendAfter = "resend_after"
    }

    init(message: String = "", expiresIn: Int = 0, resendAfter: Int = 0) {
        self.message = message
        self.expiresIn = expiresIn
        self.resendAfter = resendAfter
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            message: container.decodeStringIfPresent(.message) ?? "",
            expiresIn: container.decodeIntIfPresent(.expiresIn) ?? 0,
            resendAfter: container.decodeIntIfPresent(.resendAfter) ?? 0
        )
    }
}

struct SmsLoginRequest: Codable, Sendable {
    let phone: String
    let code: String
}

struct PasswordLoginRequest: Codable, Sendable {
    let phone: String
    let password: String
}

struct PasswordRegisterRequest: Codable, Sendable {
    let phone: String
    let code: String
    let password: String
}

struct PasswordResetRequest: Codable, Sendable {
    let phone: String
    let code: String
    let password: String
}

struct PasswordSetRequest: Codable, Sendable {
    let password: String
}

struct PasswordChangeRequest: Codable, Sendable {
    let oldPassword: String
    let newPassword: String

    enum CodingKeys: String, CodingKey {
        case oldPassword = "old_password"
        case newPassword = "new_password"
    }
}

struct PhoneChangeVerifyRequest: Codable, Sendable {
    let oldPhone: String
    let oldCode: String

    enum CodingKeys: String, CodingKey {
        case oldPhone = "old_phone"
        case oldCode = "old_code"
    }
}

struct PhoneChangeVerifyResponse: Codable, Sendable {
    let verificationToken: String

    enum CodingKeys: String, CodingKey {
        case verificationToken = "verification_token"
    }

    init(verificationToken: String = "") {
        self.verificationToken = verificationToken
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(verificationToken: container.decodeStringIfPresent(.verificationToken) ?? "")
    }
}

struct PhoneChangeRequest: Codable, Sendable {
    let oldPhone: String
    let oldVerificationToken: String
    let newPhone: String
    let newCode: String

    enum CodingKeys: String, CodingKey {
        case oldPhone = "old_phone"
        case oldVerificationToken = "old_verification_token"
        case newPhone = "new_phone"
        case newCode = "new_code"
    }
}

struct AppleTransactionConfirmRequest: Codable, Sendable {
    let productId: String
    let transactionId: String
    let originalTransactionId: String
    let environment: String
    let purchaseDateMs: Int64
    let signedTransactionInfo: String

    enum CodingKeys: String, CodingKey {
        case productId = "product_id"
        case transactionId = "transaction_id"
        case originalTransactionId = "original_transaction_id"
        case environment
        case purchaseDateMs = "purchase_date_ms"
        case signedTransactionInfo = "signed_transaction_info"
    }
}

struct MessageResponse: Codable, Sendable {
    let message: String

    init(message: String = "") {
        self.message = message
    }

    init(from decoder: Decoder) throws {
        let container = try? decoder.container(keyedBy: MessageCodingKeys.self)
        message = container?.decodeStringIfPresent(.message) ?? ""
    }

    private enum MessageCodingKeys: String, CodingKey {
        case message
    }
}

struct TaskUpdateRequest: Codable, Sendable {
    var title: String? = nil
    var confirmed: Bool? = nil
    var isPrivate: Bool? = nil
    var knowledgeScope: KnowledgeIndexScope? = nil
    var createdAtMillis: Int64? = nil

    enum CodingKeys: String, CodingKey {
        case title
        case confirmed
        case isPrivate = "is_private"
        case knowledgeScope = "knowledge_scope"
        case createdAtMillis = "created_at_millis"
    }
}

struct TaskProcessingContextRequest: Codable, Sendable {
    var meetingNote: String?
    var scheduleId: String?
    var recognitionLanguage: String?
    var transcripts: [TranscriptSegment]?

    enum CodingKeys: String, CodingKey {
        case meetingNote = "meeting_note"
        case scheduleId = "schedule_id"
        case recognitionLanguage = "recognition_language"
        case transcripts
    }
}

struct ResultUpdateRequest: Codable, Sendable {
    var participants: String? = nil
    var tags: [String]? = nil
    var summary: String? = nil
    var topics: [TopicItem]? = nil
    var decisions: [String]? = nil
    var todos: [TodoItem]? = nil
    var risks: [RiskItem]? = nil
    var transcripts: [TranscriptSegment]? = nil
}

struct RegenerateMinutesRequest: Codable, Sendable {
    var transcripts: [TranscriptSegment]
}

struct RegenerateLocalMinutesRequest: Codable, Sendable {
    var taskId: String
    var title: String
    var sourceFilePath: String
    var participants: String?
    var meetingNote: String?
    var tags: [String]
    var transcripts: [TranscriptSegment]

    enum CodingKeys: String, CodingKey {
        case taskId = "task_id"
        case title
        case sourceFilePath = "source_file_path"
        case participants
        case meetingNote = "meeting_note"
        case tags
        case transcripts
    }
}

struct SpeakerProfileEnrollFromTaskRequest: Codable, Sendable {
    let taskId: String
    let speakerId: String
    let speakerName: String
    let displayName: String

    enum CodingKeys: String, CodingKey {
        case taskId = "task_id"
        case speakerId = "speaker_id"
        case speakerName = "speaker_name"
        case displayName = "display_name"
    }
}

struct SpeakerProfileUpdateRequest: Codable, Sendable {
    let displayName: String?
    let active: Bool?

    enum CodingKeys: String, CodingKey {
        case displayName = "display_name"
        case active
    }
}

struct SpeakerProfileDeleteResponse: Codable, Sendable {
    let deleted: Bool?
    let profileId: String?
    let message: String?

    enum CodingKeys: String, CodingKey {
        case deleted
        case profileId = "profile_id"
        case message
    }

    init(deleted: Bool? = nil, profileId: String? = nil, message: String? = nil) {
        self.deleted = deleted
        self.profileId = profileId
        self.message = message
    }
}

private extension KeyedDecodingContainer {
    func decodeStringIfPresent(_ key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return clean.isEmpty || clean.lowercased() == "null" ? nil : clean
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return String(value)
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return String(value)
        }
        return nil
    }

    func decodeIntIfPresent(_ key: Key) -> Int? {
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return Int(value)
        }
        if let value = try? decodeIfPresent(String.self, forKey: key),
           let number = Int(value.trimmingCharacters(in: .whitespacesAndNewlines)) {
            return number
        }
        return nil
    }
}

private enum MIMEType {
    static func guess(for filename: String) -> String {
        switch filename.lowercased().split(separator: ".").last {
        case "mp3": return "audio/mpeg"
        case "m4a": return "audio/mp4"
        case "wav": return "audio/wav"
        case "aac": return "audio/aac"
        case "mp4": return "video/mp4"
        case "mov": return "video/quicktime"
        default: return "application/octet-stream"
        }
    }
}

private extension APIEndpoint {
    static func uploadFile(
        source: MeetingTaskSource,
        clientTaskId: String?,
        confirmed: Bool,
        isPrivate: Bool,
        deviceId: String?,
        createdAtMillis: Int64?
    ) -> APIEndpoint {
        var queryItems = [
            URLQueryItem(name: "source", value: source.rawValue),
            URLQueryItem(name: "confirmed", value: confirmed ? "true" : "false"),
            URLQueryItem(name: "is_private", value: isPrivate ? "true" : "false")
        ]
        if let clientTaskId, !clientTaskId.isEmpty {
            queryItems.append(URLQueryItem(name: "client_task_id", value: clientTaskId))
        }
        if let deviceId, !deviceId.isEmpty {
            queryItems.append(URLQueryItem(name: "device_id", value: deviceId))
        }
        if let createdAtMillis {
            queryItems.append(URLQueryItem(name: "created_at_millis", value: String(createdAtMillis)))
        }
        return APIEndpoint(.post, "/files/upload", queryItems: queryItems)
    }
}

private extension String {
    var trimmedLeadingSlash: String {
        var value = self
        while value.first == "/" {
            value.removeFirst()
        }
        return value
    }

    var trimmedTrailingSlash: String {
        var value = self
        while value.last == "/" {
            value.removeLast()
        }
        return value
    }

    var asciiUploadFilename: String {
        let ext = (self as NSString).pathExtension
        let basename = (self as NSString).deletingPathExtension
        let cleanBase = basename.unicodeScalars.map { scalar -> Character in
            if CharacterSet.alphanumerics.contains(scalar) || "-_.".unicodeScalars.contains(scalar) {
                return Character(scalar)
            }
            return "_"
        }
        let collapsed = String(cleanBase).trimmingCharacters(in: CharacterSet(charactersIn: "._-"))
        let safeBase = collapsed.isEmpty ? "upload" : collapsed
        return ext.isEmpty ? safeBase : "\(safeBase).\(ext.lowercased())"
    }

    var percentEncodedUploadFilename: String {
        addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? asciiUploadFilename
    }
}

private extension Data {
    mutating func appendString(_ string: String) {
        append(Data(string.utf8))
    }
}
