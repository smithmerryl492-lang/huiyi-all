import Foundation

final class SettingsStore {
    static let loginAgreementVersion = "2026-06-08"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var preferredRecognitionLanguage: RecognitionLanguage {
        get {
            guard let raw = defaults.string(forKey: Keys.preferredRecognitionLanguage) else { return .chinese }
            return RecognitionLanguage(rawValue: raw) ?? .chinese
        }
        set {
            defaults.set(newValue.rawValue, forKey: Keys.preferredRecognitionLanguage)
        }
    }

    var deviceId: String {
        if let existing = defaults.string(forKey: Keys.deviceId), !existing.isEmpty {
            return existing
        }
        let created = UUID().uuidString
        defaults.set(created, forKey: Keys.deviceId)
        return created
    }

    var loginAgreementAccepted: Bool {
        get {
            defaults.string(forKey: Keys.loginAgreementVersion) == Self.loginAgreementVersion
        }
        set {
            if newValue {
                defaults.set(Self.loginAgreementVersion, forKey: Keys.loginAgreementVersion)
                defaults.set(Int64(Date().timeIntervalSince1970 * 1000), forKey: Keys.loginAgreementAcceptedAt)
            } else {
                defaults.removeObject(forKey: Keys.loginAgreementVersion)
                defaults.removeObject(forKey: Keys.loginAgreementAcceptedAt)
            }
        }
    }

    func profileName(userId: String) -> String {
        defaults.string(forKey: Keys.profileName(userId: userId)) ?? ""
    }

    func saveProfileName(_ value: String, userId: String) {
        defaults.set(value, forKey: Keys.profileName(userId: userId))
    }

    func cloudSyncEnabled(userId: String) -> Bool {
        defaults.bool(forKey: Keys.cloudSyncEnabled(userId: userId))
    }

    func saveCloudSyncEnabled(_ enabled: Bool, userId: String) {
        defaults.set(enabled, forKey: Keys.cloudSyncEnabled(userId: userId))
    }

    func recentSearchKeywords(userId: String) -> [String] {
        defaults.stringArray(forKey: Keys.recentSearchKeywords(userId: userId)) ?? []
    }

    func saveRecentSearchKeywords(_ keywords: [String], userId: String) {
        defaults.set(Array(keywords.prefix(10)), forKey: Keys.recentSearchKeywords(userId: userId))
    }
}

private enum Keys {
    static let preferredRecognitionLanguage = "preferredRecognitionLanguage"
    static let deviceId = "deviceId"
    static let loginAgreementVersion = "loginAgreementVersion"
    static let loginAgreementAcceptedAt = "loginAgreementAcceptedAt"

    static func profileName(userId: String) -> String {
        "profileName.\(userId)"
    }

    static func cloudSyncEnabled(userId: String) -> String {
        "cloudSyncEnabled.\(userId)"
    }

    static func recentSearchKeywords(userId: String) -> String {
        "recentSearchKeywords.\(userId)"
    }
}
