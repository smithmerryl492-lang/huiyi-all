import Foundation
import StoreKit

enum PurchaseState: Equatable, Sendable {
    case idle
    case loadingProducts
    case purchasing
    case restoring
    case completed(productId: String)
    case pending
    case failed(String)
}

struct StoreKitPurchaseReceipt: Equatable, Sendable {
    let productId: String
    let transactionId: String
    let originalTransactionId: String
    let environment: String
    let purchaseDateMs: Int64
    let signedTransactionInfo: String
}

@available(iOS 15.0, *)
final class StoreKitPaymentService {
    private(set) var products: [Product] = []

    func loadProducts(productIds: [String]) async throws -> [Product] {
        products = try await Product.products(for: productIds)
        return products
    }

    func product(withId productId: String) -> Product? {
        products.first { $0.id == productId }
    }

    func purchase(_ product: Product) async throws -> StoreKitPurchaseReceipt? {
        let result = try await product.purchase()
        switch result {
        case let .success(verification):
            let signedTransactionInfo = verification.jwsRepresentation
            let transaction = try checkVerified(verification)
            let receipt = StoreKitPurchaseReceipt(
                productId: product.id,
                transactionId: String(transaction.id),
                originalTransactionId: String(transaction.originalID),
                environment: String(describing: transaction.environment),
                purchaseDateMs: Int64(transaction.purchaseDate.timeIntervalSince1970 * 1000),
                signedTransactionInfo: signedTransactionInfo
            )
            await transaction.finish()
            return receipt
        case .pending:
            return nil
        case .userCancelled:
            return nil
        @unknown default:
            return nil
        }
    }

    func purchaseState(_ product: Product) async -> PurchaseState {
        do {
            guard let receipt = try await purchase(product) else { return .idle }
            return .completed(productId: receipt.productId)
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    func restorePurchases() async -> [VerificationResult<Transaction>] {
        var transactions: [VerificationResult<Transaction>] = []
        for await transaction in Transaction.currentEntitlements {
            transactions.append(transaction)
        }
        return transactions
    }

    func restorePurchaseReceipts() async throws -> [StoreKitPurchaseReceipt] {
        var receipts: [StoreKitPurchaseReceipt] = []
        for await verification in Transaction.currentEntitlements {
            let signedTransactionInfo = verification.jwsRepresentation
            let transaction = try checkVerified(verification)
            receipts.append(
                StoreKitPurchaseReceipt(
                    productId: transaction.productID,
                    transactionId: String(transaction.id),
                    originalTransactionId: String(transaction.originalID),
                    environment: String(describing: transaction.environment),
                    purchaseDateMs: Int64(transaction.purchaseDate.timeIntervalSince1970 * 1000),
                    signedTransactionInfo: signedTransactionInfo
                )
            )
        }
        return receipts
    }

    private func checkVerified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case let .unverified(_, error):
            throw error
        case let .verified(safe):
            return safe
        }
    }
}
