import Foundation

enum CloudSyncOperationType: String, Codable, Sendable {
    case upload
    case updateResult
    case delete
    case upsertSchedule
    case deleteSchedule

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        switch (try? container.decode(String.self))?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "updateresult", "update_result":
            self = .updateResult
        case "delete":
            self = .delete
        case "upsertschedule", "upsert_schedule":
            self = .upsertSchedule
        case "deleteschedule", "delete_schedule":
            self = .deleteSchedule
        default:
            self = .upload
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

struct CloudSyncOperation: Codable, Identifiable, Equatable, Sendable {
    let id: String
    let type: CloudSyncOperationType
    let localTaskId: String
    let remoteTaskId: String?
    let userId: String
    let createdAtMillis: Int64
    var lastError: String?

    enum CodingKeys: String, CodingKey {
        case id
        case type
        case localTaskId
        case remoteTaskId
        case userId
        case createdAtMillis
        case lastError
    }

    init(
        id: String,
        type: CloudSyncOperationType,
        localTaskId: String,
        remoteTaskId: String?,
        userId: String,
        createdAtMillis: Int64,
        lastError: String? = nil
    ) {
        self.id = id
        self.type = type
        self.localTaskId = localTaskId
        self.remoteTaskId = remoteTaskId
        self.userId = userId
        self.createdAtMillis = createdAtMillis
        self.lastError = lastError
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: container.decodeStringIfPresent(.id) ?? UUID().uuidString,
            type: (try? container.decode(CloudSyncOperationType.self, forKey: .type)) ?? .upload,
            localTaskId: container.decodeStringIfPresent(.localTaskId) ?? "",
            remoteTaskId: container.decodeStringIfPresent(.remoteTaskId),
            userId: container.decodeStringIfPresent(.userId) ?? "",
            createdAtMillis: container.decodeInt64IfPresent(.createdAtMillis) ?? Int64(Date().timeIntervalSince1970 * 1000),
            lastError: container.decodeStringIfPresent(.lastError)
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(type, forKey: .type)
        try container.encode(localTaskId, forKey: .localTaskId)
        try container.encodeIfPresent(remoteTaskId, forKey: .remoteTaskId)
        try container.encode(userId, forKey: .userId)
        try container.encode(createdAtMillis, forKey: .createdAtMillis)
        try container.encodeIfPresent(lastError, forKey: .lastError)
    }
}

final class CloudSyncOperationStore {
    private let cacheStore: ClientCacheStore

    init(cacheStore: ClientCacheStore) {
        self.cacheStore = cacheStore
    }

    func load(userId: String) -> [CloudSyncOperation] {
        cacheStore.load([CloudSyncOperation].self, key: key(userId: userId)) ?? []
    }

    func save(_ operations: [CloudSyncOperation], userId: String) {
        try? cacheStore.save(operations, key: key(userId: userId))
    }

    func clear(userId: String) {
        cacheStore.remove(key: key(userId: userId))
    }

    private func key(userId: String) -> String {
        "cloud_sync_operations_\(userId)"
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
