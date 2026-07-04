import Foundation

struct CloudUser: Codable, Equatable, Sendable {
    let userId: String
    let username: String
    let displayName: String
    let phone: String
    let accessToken: String
    let expiresAtMillis: Int64

    var tokenValid: Bool {
        guard !accessToken.isEmpty else { return false }
        if expiresAtMillis <= 0 { return true }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return expiresAtMillis > now + 60_000
    }

    enum CodingKeys: String, CodingKey {
        case userId
        case username
        case displayName
        case phone
        case accessToken
        case expiresAtMillis
    }

    init(
        userId: String,
        username: String,
        displayName: String,
        phone: String = "",
        accessToken: String = "",
        expiresAtMillis: Int64 = 0
    ) {
        self.userId = userId
        self.username = username
        self.displayName = displayName.isEmpty ? username : displayName
        self.phone = phone
        self.accessToken = accessToken
        self.expiresAtMillis = expiresAtMillis
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let decodedUserId = container.decodeStringIfPresent(.userId) ?? ""
        let decodedUsername = container.decodeStringIfPresent(.username) ?? ""
        self.init(
            userId: decodedUserId,
            username: decodedUsername,
            displayName: container.decodeStringIfPresent(.displayName) ?? decodedUsername,
            phone: container.decodeStringIfPresent(.phone) ?? "",
            accessToken: container.decodeStringIfPresent(.accessToken) ?? "",
            expiresAtMillis: container.decodeInt64IfPresent(.expiresAtMillis) ?? 0
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(userId, forKey: .userId)
        try container.encode(username, forKey: .username)
        try container.encode(displayName, forKey: .displayName)
        try container.encode(phone, forKey: .phone)
        try container.encode(accessToken, forKey: .accessToken)
        try container.encode(expiresAtMillis, forKey: .expiresAtMillis)
    }
}

struct LoginResponse: Codable, Sendable {
    let userId: String
    let username: String
    let displayName: String
    let phone: String?
    let accessToken: String
    let tokenType: String
    let expiresIn: Int64

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case username
        case displayName = "display_name"
        case phone
        case accessToken = "access_token"
        case tokenType = "token_type"
        case expiresIn = "expires_in"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        userId = container.decodeStringIfPresent(.userId) ?? ""
        username = container.decodeStringIfPresent(.username) ?? ""
        displayName = container.decodeStringIfPresent(.displayName) ?? username
        phone = container.decodeStringIfPresent(.phone)
        accessToken = container.decodeStringIfPresent(.accessToken) ?? ""
        tokenType = container.decodeStringIfPresent(.tokenType) ?? "bearer"
        expiresIn = container.decodeInt64IfPresent(.expiresIn) ?? 0
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(userId, forKey: .userId)
        try container.encode(username, forKey: .username)
        try container.encode(displayName, forKey: .displayName)
        try container.encodeIfPresent(phone, forKey: .phone)
        try container.encode(accessToken, forKey: .accessToken)
        try container.encode(tokenType, forKey: .tokenType)
        try container.encode(expiresIn, forKey: .expiresIn)
    }

    func toCloudUser(now: Date = Date()) -> CloudUser {
        CloudUser(
            userId: userId,
            username: username,
            displayName: displayName,
            phone: phone ?? "",
            accessToken: accessToken,
            expiresAtMillis: Int64(now.timeIntervalSince1970 * 1000) + max(0, expiresIn) * 1000
        )
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
        if let value = try? decodeIfPresent(Int64.self, forKey: key) {
            return String(value)
        }
        return nil
    }

    func decodeInt64IfPresent(_ key: Key) -> Int64? {
        if let value = try? decodeIfPresent(Int64.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return Int64(value)
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return Int64(value)
        }
        if let value = try? decodeIfPresent(String.self, forKey: key),
           let number = Int64(value.trimmingCharacters(in: .whitespacesAndNewlines)) {
            return number
        }
        return nil
    }
}
