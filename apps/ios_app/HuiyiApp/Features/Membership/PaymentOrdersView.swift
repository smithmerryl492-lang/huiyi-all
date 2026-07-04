import SwiftUI
import UIKit

struct PaymentOrdersView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @EnvironmentObject private var toastCenter: ToastCenter
    @StateObject private var viewModel = PaymentOrdersViewModel()

    var body: some View {
        NavigationStack {
            SmartScreenScaffold(
                title: "订单记录",
                onBack: { router.backFromPaymentOrders() },
                trailing: {
                    SmartRoundIconButton(
                        systemImage: viewModel.isLoading ? "hourglass" : "arrow.clockwise",
                        accessibilityLabel: "刷新",
                        tint: HuiyiTheme.brand
                    ) {
                        Task { await viewModel.load(session: session) }
                    }
                    .disabled(viewModel.isLoading)
                }
            ) {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }

                SmartInfoBlock(title: "订单记录") {
                    Text("订单记录用于核对会员套餐、加量包和支付状态。")
                        .font(.system(size: 14))
                        .foregroundStyle(HuiyiTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                    if viewModel.isLoading && viewModel.orders.isEmpty {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("正在读取订单")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(HuiyiTheme.muted)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 18)
                    } else if viewModel.orders.isEmpty {
                        EmptyStateView(title: "暂无订单", message: "购买会员套餐或加量包后会显示在这里。", systemImage: "creditcard")
                    } else {
                        VStack(spacing: 12) {
                            ForEach(viewModel.orders) { order in
                                SmartPaymentOrderCard(
                                    order: order,
                                    syncing: viewModel.syncingOrderIds.contains(order.id),
                                    onCopy: {
                                        UIPasteboard.general.string = order.id
                                        toastCenter.show("订单号已复制")
                                    },
                                    onSync: {
                                        Task {
                                            if let message = await viewModel.syncOrder(order, session: session) {
                                                toastCenter.show(message)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            .refreshable { await viewModel.load(session: session) }
            .task {
                if viewModel.orders.isEmpty {
                    await viewModel.load(session: session)
                }
            }
        }
    }
}

private struct SmartPaymentOrderCard: View {
    let order: PaymentOrder
    let syncing: Bool
    let onCopy: () -> Void
    let onSync: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text(order.displayProductName)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(HuiyiTheme.ink)
                    .lineLimit(2)
                Spacer()
                Text(String(format: "¥%.2f", order.amount))
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(HuiyiTheme.brand)
                    .monospacedDigit()
            }
            HStack {
                SmartStatusBadge(text: order.status.isEmpty ? "未知状态" : order.status, color: statusColor)
                Spacer()
                Text(order.displayChannel)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(HuiyiTheme.muted)
            }
            VStack(spacing: 8) {
                PaymentOrderInfoLine(title: "订单类型", value: order.productType == "addon" ? "加量包" : "会员套餐")
                if order.transcriptionMinutes > 0 {
                    PaymentOrderInfoLine(title: "转写额度", value: "\(order.transcriptionMinutes) 分钟")
                }
                if !order.paidAt.isEmpty {
                    PaymentOrderInfoLine(title: "支付时间", value: order.paidAt)
                }
                if !order.createdAt.isEmpty {
                    PaymentOrderInfoLine(title: "创建时间", value: order.createdAt)
                }
                PaymentOrderInfoLine(title: "订单号", value: order.id)
            }
            HStack(spacing: 10) {
                SecondaryActionButton(title: "复制订单号", systemImage: "doc.on.doc", action: onCopy)
                if order.canSyncStatus {
                    PrimaryActionButton(title: syncing ? "刷新中" : "刷新状态", systemImage: syncing ? nil : "arrow.clockwise", isLoading: syncing, action: onSync)
                        .disabled(syncing)
                }
            }
        }
        .padding(16)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(HuiyiTheme.line, lineWidth: 1))
    }

    private var statusColor: Color {
        if order.status.contains("成功") || order.status.contains("已支付") {
            return HuiyiTheme.success
        }
        if order.status.contains("待") || order.status.contains("中") {
            return HuiyiTheme.warning
        }
        if order.status.contains("失败") || order.status.contains("关闭") || order.status.contains("取消") {
            return HuiyiTheme.danger
        }
        return HuiyiTheme.muted
    }
}

private struct PaymentOrderInfoLine: View {
    let title: String
    let value: String

    var body: some View {
        HStack(alignment: .top) {
            Text(title)
                .foregroundStyle(HuiyiTheme.muted)
            Spacer(minLength: 12)
            Text(value)
                .foregroundStyle(HuiyiTheme.ink)
                .multilineTextAlignment(.trailing)
        }
        .font(.system(size: 13))
    }
}

private struct LegacyPaymentOrdersView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @EnvironmentObject private var toastCenter: ToastCenter
    @StateObject private var viewModel = PaymentOrdersViewModel()

    var body: some View {
        NavigationStack {
            List {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                }

                if viewModel.isLoading && viewModel.orders.isEmpty {
                    ProgressView("正在读取订单")
                } else if viewModel.orders.isEmpty {
                    EmptyStateView(title: "暂无订单", message: "购买会员套餐或加量包后会显示在这里。", systemImage: "creditcard")
                        .listRowInsets(EdgeInsets())
                } else {
                    ForEach(viewModel.orders) { order in
                        PaymentOrderCard(
                            order: order,
                            syncing: viewModel.syncingOrderIds.contains(order.id),
                            onCopy: {
                                UIPasteboard.general.string = order.id
                                toastCenter.show("订单号已复制")
                            },
                            onSync: {
                                Task {
                                    if let message = await viewModel.syncOrder(order, session: session) {
                                        toastCenter.show(message)
                                    }
                                }
                            }
                        )
                    }
                }

                Section {
                    Text("订单记录用于核对会员套餐、加量包和支付状态。")
                        .font(.footnote)
                        .foregroundStyle(HuiyiTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .navigationTitle("订单记录")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("返回") { router.backFromPaymentOrders() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await viewModel.load(session: session) }
                    } label: {
                        if viewModel.isLoading {
                            ProgressView()
                        } else {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                }
            }
            .refreshable { await viewModel.load(session: session) }
            .task {
                if viewModel.orders.isEmpty {
                    await viewModel.load(session: session)
                }
            }
        }
    }
}

@MainActor
final class PaymentOrdersViewModel: ObservableObject {
    @Published private(set) var orders: [PaymentOrder] = []
    @Published private(set) var syncingOrderIds: Set<String> = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    func load(session: AppSession) async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            orders = try await session.loadOrders()
        } catch {
            if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
                errorMessage = localized
            } else {
                errorMessage = error.localizedDescription
            }
        }
    }

    func syncOrder(_ order: PaymentOrder, session: AppSession) async -> String? {
        syncingOrderIds.insert(order.id)
        errorMessage = nil
        defer { syncingOrderIds.remove(order.id) }
        do {
            if let updated = try await session.syncPaymentOrder(id: order.id) {
                orders = orders.map { $0.id == updated.id ? updated : $0 }
                return updated.status.isEmpty ? "订单状态已刷新" : "订单状态：\(updated.status)"
            }
            return "未找到该订单"
        } catch {
            if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
                errorMessage = localized
            } else {
                errorMessage = error.localizedDescription
            }
            return nil
        }
    }
}

private struct PaymentOrderCard: View {
    let order: PaymentOrder
    let syncing: Bool
    let onCopy: () -> Void
    let onSync: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(order.displayProductName)
                    .font(.headline)
                    .lineLimit(2)
                Spacer()
                Text(String(format: "¥%.2f", order.amount))
                    .font(.headline.monospacedDigit())
            }
            HStack {
                Text(order.status.isEmpty ? "未知状态" : order.status)
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(statusColor.opacity(0.14), in: Capsule())
                    .foregroundStyle(statusColor)
                Spacer()
                Text(order.displayChannel)
                    .font(.subheadline)
                    .foregroundStyle(HuiyiTheme.textSecondary)
            }
            VStack(alignment: .leading, spacing: 6) {
                LabeledContent("订单类型", value: order.productType == "addon" ? "加量包" : "会员套餐")
                if order.transcriptionMinutes > 0 {
                    LabeledContent("转写额度", value: "\(order.transcriptionMinutes) 分钟")
                }
                if !order.paidAt.isEmpty {
                    LabeledContent("支付时间", value: order.paidAt)
                }
                if !order.createdAt.isEmpty {
                    LabeledContent("创建时间", value: order.createdAt)
                }
                LabeledContent("订单号", value: order.id)
            }
            .font(.caption)

            HStack(spacing: 10) {
                Button(action: onCopy) {
                    Label("复制订单号", systemImage: "doc.on.doc")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                if order.canSyncStatus {
                    Button(action: onSync) {
                        HStack {
                            if syncing {
                                ProgressView()
                            }
                            Text(syncing ? "刷新中" : "刷新状态")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(HuiyiTheme.accent)
                    .disabled(syncing)
                }
            }
            .font(.caption.weight(.semibold))
        }
        .padding(.vertical, 6)
    }

    private var statusColor: Color {
        if order.status.contains("成功") || order.status.contains("已支付") {
            return HuiyiTheme.success
        }
        if order.status.contains("待") || order.status.contains("中") {
            return HuiyiTheme.warning
        }
        if order.status.contains("失败") || order.status.contains("关闭") || order.status.contains("取消") {
            return HuiyiTheme.danger
        }
        return HuiyiTheme.textSecondary
    }
}
