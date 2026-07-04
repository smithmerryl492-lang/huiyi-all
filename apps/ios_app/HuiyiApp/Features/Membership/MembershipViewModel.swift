import Foundation

@MainActor
final class MembershipViewModel: ObservableObject {
    @Published private(set) var profile: MembershipProfile = .empty
    @Published private(set) var orders: [PaymentOrder] = []
    @Published var selectedPlanId: String?
    @Published var selectedAddonId: String?
    @Published var purchaseStatusMessage: String?
    @Published var isLoading = false
    @Published var isLoadingProducts = false
    @Published var isPurchasing = false
    @Published var errorMessage: String?

    private let paymentService = StoreKitPaymentService()

    func load(session: AppSession) async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            try await session.refreshMembership()
            profile = session.membershipProfile
            if selectedPlanId == nil {
                selectedPlanId = profile.plans.first { $0.id == profile.planId }?.id ?? profile.plans.first?.id
            }
            orders = try await session.loadOrders()
            await loadStoreProductsIfNeeded()
        } catch {
            errorMessage = userMessage(error)
        }
    }

    var selectedPlan: MembershipPlan? {
        guard selectedAddonId == nil, let selectedPlanId else { return nil }
        return profile.plans.first { $0.id == selectedPlanId }
    }

    var selectedAddon: MembershipAddon? {
        guard profile.active, !profile.frozen, let selectedAddonId else { return nil }
        return profile.addons.first { $0.id == selectedAddonId }
    }

    var canBeginPurchase: Bool {
        (selectedPlan != nil || selectedAddon != nil) && profile.appleIapEnabled && !profile.frozen && !isPurchasing && !isLoadingProducts
    }

    func selectPlan(_ plan: MembershipPlan) {
        selectedPlanId = plan.id
        selectedAddonId = nil
        purchaseStatusMessage = nil
    }

    func selectAddon(_ addon: MembershipAddon) {
        guard profile.active, !profile.frozen else {
            purchaseStatusMessage = "需先开通有效会员后才能购买加量包"
            return
        }
        selectedAddonId = addon.id
        purchaseStatusMessage = nil
    }

    func beginPurchase(session: AppSession) async {
        if profile.frozen {
            purchaseStatusMessage = "账号已冻结，暂时无法购买"
            return
        }
        guard profile.appleIapEnabled else {
            purchaseStatusMessage = "iOS 内购暂未开通"
            return
        }
        guard let productId = selectedAppleProductId else {
            purchaseStatusMessage = "请选择要购买的套餐或加量包"
            return
        }
        isPurchasing = true
        purchaseStatusMessage = "正在连接 Apple 支付"
        defer { isPurchasing = false }
        do {
            try await ensureStoreProductLoaded(productId: productId)
            guard let product = paymentService.product(withId: productId) else {
                purchaseStatusMessage = "Apple 商品暂未配置：\(productId)"
                return
            }
            guard let receipt = try await paymentService.purchase(product) else {
                purchaseStatusMessage = "支付未完成或正在等待 Apple 确认"
                return
            }
            purchaseStatusMessage = "正在确认会员权益"
            let order = try await session.confirmAppleTransaction(receipt.confirmRequest)
            if let order {
                orders = [order] + orders.filter { $0.id != order.id }
            }
            profile = session.membershipProfile
            purchaseStatusMessage = "购买成功，会员权益已更新"
        } catch {
            purchaseStatusMessage = userMessage(error)
        }
    }

    func restorePurchases(session: AppSession) async {
        guard profile.appleIapEnabled else {
            purchaseStatusMessage = "iOS 内购暂未开通"
            return
        }
        isPurchasing = true
        purchaseStatusMessage = "正在恢复 Apple 购买记录"
        defer { isPurchasing = false }
        do {
            let receipts = try await paymentService.restorePurchaseReceipts()
            if receipts.isEmpty {
                purchaseStatusMessage = "当前 Apple ID 暂无可恢复的购买记录"
                return
            }
            var restoredCount = 0
            for receipt in receipts {
                if let order = try await session.confirmAppleTransaction(receipt.confirmRequest) {
                    orders = [order] + orders.filter { $0.id != order.id }
                    restoredCount += 1
                }
            }
            profile = session.membershipProfile
            purchaseStatusMessage = restoredCount > 0 ? "已恢复 \(restoredCount) 笔购买记录" : "没有新的可恢复购买记录"
        } catch {
            purchaseStatusMessage = userMessage(error)
        }
    }

    private var selectedAppleProductId: String? {
        selectedAddon?.appleProductId ?? selectedPlan?.appleProductId
    }

    private func loadStoreProductsIfNeeded() async {
        guard profile.appleIapEnabled else { return }
        let productIds = Array(Set(profile.plans.map(\.appleProductId) + profile.addons.map(\.appleProductId))).filter { !$0.isEmpty }
        guard !productIds.isEmpty else { return }
        isLoadingProducts = true
        defer { isLoadingProducts = false }
        do {
            _ = try await paymentService.loadProducts(productIds: productIds)
        } catch {
            purchaseStatusMessage = "Apple 商品读取失败：\(userMessage(error))"
        }
    }

    private func ensureStoreProductLoaded(productId: String) async throws {
        if paymentService.product(withId: productId) != nil { return }
        isLoadingProducts = true
        defer { isLoadingProducts = false }
        _ = try await paymentService.loadProducts(productIds: [productId])
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }
}

private extension StoreKitPurchaseReceipt {
    var confirmRequest: AppleTransactionConfirmRequest {
        AppleTransactionConfirmRequest(
            productId: productId,
            transactionId: transactionId,
            originalTransactionId: originalTransactionId,
            environment: environment,
            purchaseDateMs: purchaseDateMs,
            signedTransactionInfo: signedTransactionInfo
        )
    }
}
