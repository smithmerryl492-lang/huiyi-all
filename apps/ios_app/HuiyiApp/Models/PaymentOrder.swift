import Foundation

struct PaymentOrder: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let productType: String
    let planId: String
    let planName: String
    let addonId: String
    let addonName: String
    let productName: String
    let transcriptionMinutes: Int
    let amount: Double
    let status: String
    let channel: String
    let createdAt: String
    let paidAt: String
    let updatedAt: String
    let channelNo: String

    enum CodingKeys: String, CodingKey {
        case id
        case productType = "productType"
        case planId = "planId"
        case planName = "planName"
        case addonId = "addonId"
        case addonName = "addonName"
        case productName = "productName"
        case transcriptionMinutes = "transcriptionMinutes"
        case amount
        case status
        case channel
        case createdAt = "createdAt"
        case paidAt = "paidAt"
        case updatedAt = "updatedAt"
        case channelNo = "channelNo"
        case date
    }

    init(
        id: String = "",
        productType: String = "",
        planId: String = "",
        planName: String = "",
        addonId: String = "",
        addonName: String = "",
        productName: String = "",
        transcriptionMinutes: Int = 0,
        amount: Double = 0,
        status: String = "",
        channel: String = "",
        createdAt: String = "",
        paidAt: String = "",
        updatedAt: String = "",
        channelNo: String = ""
    ) {
        self.id = id
        self.productType = productType
        self.planId = planId
        self.planName = planName
        self.addonId = addonId
        self.addonName = addonName
        self.productName = productName
        self.transcriptionMinutes = transcriptionMinutes
        self.amount = amount
        self.status = status
        self.channel = channel
        self.createdAt = createdAt
        self.paidAt = paidAt
        self.updatedAt = updatedAt
        self.channelNo = channelNo
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: container.decodeStringIfPresent(.id) ?? "",
            productType: container.decodeStringIfPresent(.productType) ?? "",
            planId: container.decodeStringIfPresent(.planId) ?? "",
            planName: container.decodeStringIfPresent(.planName) ?? "",
            addonId: container.decodeStringIfPresent(.addonId) ?? "",
            addonName: container.decodeStringIfPresent(.addonName) ?? "",
            productName: container.decodeStringIfPresent(.productName) ?? "",
            transcriptionMinutes: container.decodeIntIfPresent(.transcriptionMinutes) ?? 0,
            amount: container.decodeDoubleIfPresent(.amount) ?? 0,
            status: container.decodeStringIfPresent(.status) ?? "",
            channel: container.decodeStringIfPresent(.channel) ?? "",
            createdAt: container.decodeStringIfPresent(.createdAt) ?? container.decodeStringIfPresent(.date) ?? "",
            paidAt: container.decodeStringIfPresent(.paidAt) ?? "",
            updatedAt: container.decodeStringIfPresent(.updatedAt) ?? "",
            channelNo: container.decodeStringIfPresent(.channelNo) ?? ""
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(productType, forKey: .productType)
        try container.encode(planId, forKey: .planId)
        try container.encode(planName, forKey: .planName)
        try container.encode(addonId, forKey: .addonId)
        try container.encode(addonName, forKey: .addonName)
        try container.encode(productName, forKey: .productName)
        try container.encode(transcriptionMinutes, forKey: .transcriptionMinutes)
        try container.encode(amount, forKey: .amount)
        try container.encode(status, forKey: .status)
        try container.encode(channel, forKey: .channel)
        try container.encode(createdAt, forKey: .createdAt)
        try container.encode(paidAt, forKey: .paidAt)
        try container.encode(updatedAt, forKey: .updatedAt)
        try container.encode(channelNo, forKey: .channelNo)
    }
}

struct PaymentOrderListResponse: Codable, Sendable {
    let items: [PaymentOrder]

    enum CodingKeys: String, CodingKey {
        case items
    }

    init(items: [PaymentOrder] = []) {
        self.items = items
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = (try? container.decodeIfPresent([PaymentOrder].self, forKey: .items)) ?? []
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
}

struct PaymentOrderResponse: Codable, Sendable {
    let order: PaymentOrder?
}

struct PaymentOrderSyncResponse: Codable, Sendable {
    let order: PaymentOrder?
    let tradeStatus: String?

    enum CodingKeys: String, CodingKey {
        case order
        case tradeStatus = "trade_status"
    }
}

extension PaymentOrder {
    var displayProductName: String {
        if !productName.isEmpty { return productName }
        if !planName.isEmpty { return planName }
        if !addonName.isEmpty { return addonName }
        return "会员订单"
    }

    var displayChannel: String {
        switch channel.lowercased() {
        case "alipay", "":
            return "支付宝"
        case "apple":
            return "Apple"
        case "manual":
            return "后台录入"
        default:
            return channel
        }
    }

    var canSyncStatus: Bool {
        (channel.lowercased() == "alipay" || channel == "支付宝") &&
            (status.contains("未支付") ||
            status.contains("待支付") ||
            status.contains("处理中") ||
            status.contains("支付中"))
    }
}
