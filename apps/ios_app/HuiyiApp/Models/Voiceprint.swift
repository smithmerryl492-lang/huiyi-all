import Foundation

struct SpeakerProfile: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let displayName: String
    let sampleCount: Int
    let active: Bool
    let createdAt: String
    let updatedAt: String

    enum CodingKeys: String, CodingKey {
        case id
        case displayName = "display_name"
        case sampleCount = "sample_count"
        case active
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }

    init(
        id: String,
        displayName: String,
        sampleCount: Int,
        active: Bool,
        createdAt: String,
        updatedAt: String
    ) {
        self.id = id
        self.displayName = displayName
        self.sampleCount = sampleCount
        self.active = active
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = container.decodeStringIfPresent(.id) ?? ""
        displayName = container.decodeStringIfPresent(.displayName) ?? "未命名声纹"
        sampleCount = container.decodeIntIfPresent(.sampleCount) ?? 0
        active = container.decodeBoolIfPresent(.active) ?? true
        createdAt = container.decodeStringIfPresent(.createdAt) ?? ""
        updatedAt = container.decodeStringIfPresent(.updatedAt) ?? ""
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(displayName, forKey: .displayName)
        try container.encode(sampleCount, forKey: .sampleCount)
        try container.encode(active, forKey: .active)
        try container.encode(createdAt, forKey: .createdAt)
        try container.encode(updatedAt, forKey: .updatedAt)
    }
}

private extension String {
    var nilIfBlank: String? {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty ? nil : clean
    }
}

private extension KeyedDecodingContainer {
    func decodeStringIfPresent(_ key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return value.nilIfBlank
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return String(value)
        }
        return nil
    }

    func decodeIntIfPresent(_ key: Key) -> Int? {
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(String.self, forKey: key),
           let number = Int(value.trimmingCharacters(in: .whitespacesAndNewlines)) {
            return number
        }
        return nil
    }

    func decodeBoolIfPresent(_ key: Key) -> Bool? {
        if let value = try? decodeIfPresent(Bool.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            switch value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "true", "1", "yes":
                return true
            case "false", "0", "no":
                return false
            default:
                return nil
            }
        }
        return nil
    }
}
