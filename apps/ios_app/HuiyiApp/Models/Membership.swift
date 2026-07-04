import Foundation

struct MembershipProfile: Codable, Equatable, Sendable {
    var active: Bool
    var accountStatus: String
    var planId: String
    var planName: String
    var expiresAt: String?
    var periodMonth: String
    var transcriptionMinutesTotal: Int
    var transcriptionMinutesUsed: Int
    var knowledgeQaTotal: Int
    var knowledgeQaUsed: Int
    var paymentEnabled: Bool
    var appleIapEnabled: Bool
    var plans: [MembershipPlan]
    var addons: [MembershipAddon]

    static let empty = MembershipProfile(
        active: false,
        accountStatus: "normal",
        planId: "none",
        planName: "无套餐",
        expiresAt: nil,
        periodMonth: "",
        transcriptionMinutesTotal: 0,
        transcriptionMinutesUsed: 0,
        knowledgeQaTotal: 0,
        knowledgeQaUsed: 0,
        paymentEnabled: false,
        appleIapEnabled: false,
        plans: [],
        addons: []
    )

    var frozen: Bool {
        accountStatus == "frozen"
    }

    var transcriptionMinutesRemaining: Int {
        max(0, transcriptionMinutesTotal - transcriptionMinutesUsed)
    }

    var knowledgeQaRemaining: Int {
        max(0, knowledgeQaTotal - knowledgeQaUsed)
    }

    enum CodingKeys: String, CodingKey {
        case active
        case accountStatus = "account_status"
        case planId = "plan_id"
        case planName = "plan_name"
        case expiresAt = "expires_at"
        case periodMonth = "period_month"
        case transcriptionMinutesTotal = "transcription_minutes_total"
        case transcriptionMinutesUsed = "transcription_minutes_used"
        case knowledgeQaTotal = "knowledge_qa_total"
        case knowledgeQaUsed = "knowledge_qa_used"
        case paymentEnabled = "payment_enabled"
        case appleIapEnabled = "apple_iap_enabled"
        case plans
        case addons
    }

    init(
        active: Bool = false,
        accountStatus: String = "normal",
        planId: String = "none",
        planName: String = "无套餐",
        expiresAt: String? = nil,
        periodMonth: String = "",
        transcriptionMinutesTotal: Int = 0,
        transcriptionMinutesUsed: Int = 0,
        knowledgeQaTotal: Int = 0,
        knowledgeQaUsed: Int = 0,
        paymentEnabled: Bool = false,
        appleIapEnabled: Bool = false,
        plans: [MembershipPlan] = [],
        addons: [MembershipAddon] = []
    ) {
        self.active = active
        self.accountStatus = accountStatus.isEmpty ? "normal" : accountStatus
        self.planId = planId.isEmpty ? "none" : planId
        self.planName = planName.isEmpty ? "无套餐" : planName
        self.expiresAt = expiresAt?.nonEmptyString
        self.periodMonth = periodMonth
        self.transcriptionMinutesTotal = transcriptionMinutesTotal
        self.transcriptionMinutesUsed = transcriptionMinutesUsed
        self.knowledgeQaTotal = knowledgeQaTotal
        self.knowledgeQaUsed = knowledgeQaUsed
        self.paymentEnabled = paymentEnabled
        self.appleIapEnabled = appleIapEnabled
        self.plans = plans
        self.addons = addons
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            active: container.decodeBoolIfPresent(.active) ?? false,
            accountStatus: container.decodeStringIfPresent(.accountStatus) ?? "normal",
            planId: container.decodeStringIfPresent(.planId) ?? "none",
            planName: container.decodeStringIfPresent(.planName) ?? "无套餐",
            expiresAt: container.decodeStringIfPresent(.expiresAt),
            periodMonth: container.decodeStringIfPresent(.periodMonth) ?? "",
            transcriptionMinutesTotal: container.decodeIntIfPresent(.transcriptionMinutesTotal) ?? 0,
            transcriptionMinutesUsed: container.decodeIntIfPresent(.transcriptionMinutesUsed) ?? 0,
            knowledgeQaTotal: container.decodeIntIfPresent(.knowledgeQaTotal) ?? 0,
            knowledgeQaUsed: container.decodeIntIfPresent(.knowledgeQaUsed) ?? 0,
            paymentEnabled: container.decodeBoolIfPresent(.paymentEnabled) ?? false,
            appleIapEnabled: container.decodeBoolIfPresent(.appleIapEnabled) ?? false,
            plans: (try? container.decode([MembershipPlan].self, forKey: .plans)) ?? [],
            addons: (try? container.decode([MembershipAddon].self, forKey: .addons)) ?? []
        )
    }
}

struct MembershipPlan: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let name: String
    let priceCents: Int
    let price: Double
    let transcriptionMinutes: Int
    let hours: Double
    let knowledgeQa: Int
    let enabled: Bool
    let appleProductId: String

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case priceCents = "price_cents"
        case price
        case transcriptionMinutes = "transcription_minutes"
        case hours
        case knowledgeQa = "knowledge_qa"
        case enabled
        case appleProductId = "apple_product_id"
    }

    init(id: String, name: String, priceCents: Int, price: Double, transcriptionMinutes: Int, hours: Double, knowledgeQa: Int, enabled: Bool = true, appleProductId: String = "") {
        self.id = id
        self.name = name
        self.priceCents = priceCents
        self.price = price
        self.transcriptionMinutes = transcriptionMinutes
        self.hours = hours
        self.knowledgeQa = knowledgeQa
        self.enabled = enabled
        self.appleProductId = appleProductId.isEmpty ? "com.kunqiong.huiyi.plan.\(id)" : appleProductId
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let minutes = container.decodeIntIfPresent(.transcriptionMinutes) ?? 0
        self.init(
            id: container.decodeStringIfPresent(.id) ?? "",
            name: container.decodeStringIfPresent(.name) ?? "",
            priceCents: container.decodeIntIfPresent(.priceCents) ?? 0,
            price: container.decodeDoubleIfPresent(.price) ?? 0,
            transcriptionMinutes: minutes,
            hours: container.decodeDoubleIfPresent(.hours) ?? Double(minutes) / 60.0,
            knowledgeQa: container.decodeIntIfPresent(.knowledgeQa) ?? 0,
            enabled: container.decodeBoolIfPresent(.enabled) ?? true,
            appleProductId: container.decodeStringIfPresent(.appleProductId) ?? ""
        )
    }
}

struct MembershipAddon: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let name: String
    let unit: String
    let priceCents: Int
    let price: Double
    let enabled: Bool
    let appleProductId: String

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case unit
        case priceCents = "price_cents"
        case price
        case enabled
        case appleProductId = "apple_product_id"
    }

    init(id: String, name: String, unit: String, priceCents: Int, price: Double, enabled: Bool = true, appleProductId: String = "") {
        self.id = id
        self.name = name
        self.unit = unit.isEmpty ? "hour" : unit
        self.priceCents = priceCents
        self.price = price
        self.enabled = enabled
        self.appleProductId = appleProductId.isEmpty ? "com.kunqiong.huiyi.addon.\(id)" : appleProductId
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: container.decodeStringIfPresent(.id) ?? "",
            name: container.decodeStringIfPresent(.name) ?? "",
            unit: container.decodeStringIfPresent(.unit) ?? "hour",
            priceCents: container.decodeIntIfPresent(.priceCents) ?? 0,
            price: container.decodeDoubleIfPresent(.price) ?? 0,
            enabled: container.decodeBoolIfPresent(.enabled) ?? true,
            appleProductId: container.decodeStringIfPresent(.appleProductId) ?? ""
        )
    }
}

private extension String {
    var nonEmptyString: String? {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty ? nil : clean
    }
}

private extension KeyedDecodingContainer {
    func decodeStringIfPresent(_ key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return value
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
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return Int(value)
        }
        return nil
    }

    func decodeDoubleIfPresent(_ key: Key) -> Double? {
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return Double(value)
        }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return Double(value)
        }
        return nil
    }

    func decodeBoolIfPresent(_ key: Key) -> Bool? {
        if let value = try? decodeIfPresent(Bool.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return value != 0
        }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return ["true", "1", "yes"].contains(value.lowercased())
        }
        return nil
    }
}
