import Foundation
import Security

final class TokenStore {
    private let service = "com.kunqiong.huiyi.auth"
    private let account = "current-user"
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    func loadUser() -> CloudUser? {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        query.removeAll()
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return try? decoder.decode(CloudUser.self, from: data)
    }

    func saveUser(_ user: CloudUser) {
        guard let data = try? encoder.encode(user) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            attributes.forEach { item[$0.key] = $0.value }
            SecItemAdd(item as CFDictionary, nil)
        }
    }

    func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}
