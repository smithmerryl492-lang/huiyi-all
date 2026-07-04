import SwiftUI

struct MembershipView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @StateObject private var viewModel = MembershipViewModel()
    @State private var pendingPlanConfirmation: MembershipPlan?

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                SmartScreenScaffold(
                    title: "会员中心",
                    onBack: { router.go(.profile) }
                ) {
                    if let errorMessage = viewModel.errorMessage {
                        ErrorBanner(message: errorMessage)
                    }
                    if let purchaseStatusMessage = viewModel.purchaseStatusMessage {
                        HStack(alignment: .top, spacing: 9) {
                            Image(systemName: "info.circle.fill")
                            Text(purchaseStatusMessage)
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(HuiyiTheme.muted)
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }

                    MembershipHeroCard(profile: viewModel.profile)
                    SmartRowButton(systemImage: "receipt.fill", title: "订单记录", subtitle: "查看购买订单和支付状态") {
                        router.go(.paymentOrders)
                    }
                    .padding(16)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(HuiyiTheme.line, lineWidth: 1))

                    if viewModel.isLoading {
                        SmartCard(radius: 22, padding: 20) {
                            Text("正在读取套餐")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(HuiyiTheme.ink)
                            MembershipPlanLoadingStrip()
                        }
                    } else if viewModel.profile.plans.isEmpty {
                        SmartCard(radius: 22, padding: 20) {
                            Text("当前没有可售套餐")
                                .font(.system(size: 15))
                                .foregroundStyle(HuiyiTheme.muted)
                        }
                    } else {
                        SmartSectionHeader(title: "选择套餐")
                        if viewModel.profile.active {
                            MembershipNoticeCard(text: "已有会员再买套餐会追加本月额度，截止日期刷新为从今天起 1 个月。", systemImage: "clock.fill")
                        }
                        ForEach(viewModel.profile.plans) { plan in
                            MembershipPlanCard(
                                plan: plan,
                                selected: viewModel.selectedPlanId == plan.id && viewModel.selectedAddonId == nil
                            ) {
                                viewModel.selectPlan(plan)
                            }
                        }
                        if !viewModel.profile.addons.isEmpty {
                            SmartSectionHeader(title: "加量包")
                            if !viewModel.profile.active || viewModel.profile.frozen {
                                MembershipNoticeCard(text: "加量包仅限会员购买，请先开通会员套餐。", systemImage: "lock.fill", warm: false)
                            }
                            ForEach(viewModel.profile.addons) { addon in
                                MembershipAddonCard(
                                    addon: addon,
                                    selected: viewModel.selectedAddonId == addon.id,
                                    enabled: viewModel.profile.active && !viewModel.profile.frozen
                                ) {
                                    viewModel.selectAddon(addon)
                                }
                            }
                        }
                    }
                    Color.clear.frame(height: 150)
                }
                .refreshable { await viewModel.load(session: session) }

                MembershipBottomPurchaseBar(
                    title: purchaseButtonTitle,
                    selectedName: selectedProductName,
                    selectedPrice: selectedProductPrice,
                    enabled: viewModel.canBeginPurchase,
                    onPurchase: {
                        if let plan = viewModel.selectedPlan, viewModel.profile.active {
                            pendingPlanConfirmation = plan
                        } else {
                            Task { await viewModel.beginPurchase(session: session) }
                        }
                    },
                    onRestore: {
                        Task { await viewModel.restorePurchases(session: session) }
                    }
                )
            }
            .alert("确认购买套餐", isPresented: Binding(
                get: { pendingPlanConfirmation != nil },
                set: { if !$0 { pendingPlanConfirmation = nil } }
            )) {
                Button("继续") {
                    pendingPlanConfirmation = nil
                    Task { await viewModel.beginPurchase(session: session) }
                }
                Button("取消", role: .cancel) {
                    pendingPlanConfirmation = nil
                }
            } message: {
                if let plan = pendingPlanConfirmation {
                    Text("你当前已有会员。本次购买会追加 \(plan.hours.cleanHourText) 小时转写和 \(plan.knowledgeQa) 次问答额度；会员截止日期刷新为从今天起 1 个月，不会按原截止日期继续顺延。")
                }
            }
            .task {
                if viewModel.profile.planId == "none" {
                    await viewModel.load(session: session)
                }
            }
        }
    }

    private var purchaseButtonTitle: String {
        if viewModel.profile.frozen { return "账号已冻结" }
        if viewModel.isPurchasing { return "正在确认" }
        if viewModel.isLoadingProducts { return "正在读取商品" }
        if viewModel.selectedPlan == nil && viewModel.selectedAddon == nil { return "请选择套餐" }
        if !viewModel.profile.appleIapEnabled { return "iOS 内购暂未开通" }
        return "立即购买"
    }

    private var selectedProductName: String? {
        viewModel.selectedAddon?.name ?? viewModel.selectedPlan?.name
    }

    private var selectedProductPrice: Double? {
        viewModel.selectedAddon?.price ?? viewModel.selectedPlan?.price
    }
}

private struct MembershipHeroCard: View {
    let profile: MembershipProfile

    var body: some View {
        SmartCard(radius: 28, padding: 22) {
            HStack(spacing: 14) {
                Image(systemName: "checkmark.shield.fill")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(HuiyiTheme.brand)
                    .frame(width: 54, height: 54)
                    .background(HuiyiTheme.brandSoft, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                VStack(alignment: .leading, spacing: 5) {
                    Text(heroTitle)
                        .font(.system(size: 21, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                        .lineLimit(1)
                        .minimumScaleFactor(0.76)
                    Text(heroSubtitle)
                        .font(.system(size: 13))
                        .foregroundStyle(HuiyiTheme.muted)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                }
                Spacer()
                if profile.frozen {
                    SmartStatusBadge(text: "冻结", color: HuiyiTheme.danger, background: Color(red: 1.000, green: 0.925, blue: 0.910))
                }
            }
            HStack(spacing: 12) {
                MembershipQuotaTile(value: "\(profile.transcriptionMinutesRemaining)", label: "转写分钟")
                MembershipQuotaTile(value: "\(profile.knowledgeQaRemaining)", label: "问答次数")
            }
        }
    }

    private var heroTitle: String {
        if profile.frozen { return "账号已冻结" }
        if profile.active { return profile.planName }
        if profile.transcriptionMinutesRemaining > 0 || profile.knowledgeQaRemaining > 0 { return "试用额度" }
        return "开通鲲穹会纪会员"
    }

    private var heroSubtitle: String {
        if profile.frozen { return "购买和支付已暂停" }
        if profile.active { return "有效期至 \(profile.expiresAt ?? "-")" }
        if profile.transcriptionMinutesRemaining > 0 || profile.knowledgeQaRemaining > 0 { return "剩余额度用完后可购买套餐继续使用" }
        return "获得转写时长和知识库问答额度"
    }
}

private struct MembershipQuotaTile: View {
    let value: String
    let label: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(value)
                .font(.system(size: 24, weight: .bold))
                .foregroundStyle(HuiyiTheme.brand)
                .monospacedDigit()
            Text(label)
                .font(.system(size: 12))
                .foregroundStyle(HuiyiTheme.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(red: 0.961, green: 0.973, blue: 0.992), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

private struct MembershipPlanCard: View {
    let plan: MembershipPlan
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(plan.name)
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                        .lineLimit(1)
                    Text("\(plan.hours.cleanHourText)小时转写 / \(plan.knowledgeQa)次知识库问答")
                        .font(.system(size: 13))
                        .foregroundStyle(HuiyiTheme.muted)
                        .lineLimit(2)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("¥\(plan.price.cleanPriceText)")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundStyle(selected ? HuiyiTheme.brand : HuiyiTheme.ink)
                        .monospacedDigit()
                    Text("每月")
                        .font(.system(size: 12))
                        .foregroundStyle(HuiyiTheme.muted)
                }
            }
            .padding(16)
            .background(selected ? HuiyiTheme.brandSoft : Color.white, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .stroke(selected ? HuiyiTheme.brand : HuiyiTheme.line, lineWidth: selected ? 2 : 1)
            )
            .shadow(color: HuiyiTheme.brandDark.opacity(0.08), radius: 8, x: 0, y: 3)
        }
        .buttonStyle(.plain)
    }
}

private struct MembershipAddonCard: View {
    let addon: MembershipAddon
    let selected: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: "clock.fill")
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(Color(red: 0.878, green: 0.471, blue: 0.180))
                    .frame(width: 40, height: 40)
                    .background(HuiyiTheme.warmSoft, in: Circle())
                VStack(alignment: .leading, spacing: 6) {
                    Text(addon.name)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                    Text("补充 1 \(addon.unit.unitLabel)转写额度")
                        .font(.system(size: 13))
                        .foregroundStyle(HuiyiTheme.muted)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("¥\(addon.price.cleanPriceText)")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(selected ? HuiyiTheme.brand : HuiyiTheme.ink)
                        .monospacedDigit()
                    Text("每\(addon.unit.unitLabel)")
                        .font(.system(size: 12))
                        .foregroundStyle(HuiyiTheme.muted)
                }
            }
            .opacity(enabled ? 1 : 0.48)
            .padding(16)
            .background(selected ? HuiyiTheme.brandSoft : Color.white, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .stroke(selected ? HuiyiTheme.brand : HuiyiTheme.line, lineWidth: selected ? 2 : 1)
            )
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

private struct MembershipNoticeCard: View {
    let text: String
    let systemImage: String
    var warm: Bool = true

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(warm ? Color(red: 0.878, green: 0.471, blue: 0.180) : HuiyiTheme.muted)
                .frame(width: 30, height: 30)
                .background(Color.white, in: Circle())
            Text(text)
                .font(.system(size: 13))
                .foregroundStyle(warm ? Color(red: 0.478, green: 0.306, blue: 0.094) : HuiyiTheme.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(warm ? Color(red: 1.000, green: 0.969, blue: 0.910) : Color(red: 0.969, green: 0.976, blue: 0.988), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(warm ? Color(red: 1.000, green: 0.843, blue: 0.608) : HuiyiTheme.line, lineWidth: 1))
    }
}

private struct MembershipPlanLoadingStrip: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            RoundedRectangle(cornerRadius: 99)
                .fill(Color(red: 0.918, green: 0.941, blue: 0.965))
                .frame(maxWidth: .infinity)
                .frame(height: 10)
            RoundedRectangle(cornerRadius: 99)
                .fill(Color(red: 0.918, green: 0.941, blue: 0.965))
                .frame(width: 160, height: 10)
        }
    }
}

private struct MembershipBottomPurchaseBar: View {
    let title: String
    let selectedName: String?
    let selectedPrice: Double?
    let enabled: Bool
    let onPurchase: () -> Void
    let onRestore: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("iOS 购买")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                    Text(selectedName.map { "已选 \($0)" } ?? "请选择套餐")
                        .font(.system(size: 12))
                        .foregroundStyle(HuiyiTheme.muted)
                        .lineLimit(1)
                }
                Spacer()
                Text(selectedPrice.map { "¥\($0.cleanPriceText)" } ?? "-")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundStyle(HuiyiTheme.brand)
                    .monospacedDigit()
            }
            PrimaryActionButton(title: title, systemImage: "cart.fill", action: onPurchase)
                .disabled(!enabled)
            SecondaryActionButton(title: "恢复购买", systemImage: "arrow.clockwise", action: onRestore)
            Text("iOS 端使用 Apple 内购。购买成功后权益会同步到当前账号，订单记录可在 Android 和 iOS 查看。")
                .font(.system(size: 12))
                .foregroundStyle(HuiyiTheme.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 20)
        .padding(.top, 16)
        .padding(.bottom, 14)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .shadow(color: HuiyiTheme.brandDark.opacity(0.14), radius: 16, x: 0, y: -5)
    }
}

private struct LegacyMembershipView: View {
    @EnvironmentObject private var session: AppSession
    @StateObject private var viewModel = MembershipViewModel()
    @State private var pendingPlanConfirmation: MembershipPlan?

    var body: some View {
        NavigationStack {
            List {
                if let errorMessage = viewModel.errorMessage {
                    ErrorBanner(message: errorMessage)
                        .listRowSeparator(.hidden)
                }
                if let purchaseStatusMessage = viewModel.purchaseStatusMessage {
                    Label(purchaseStatusMessage, systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(HuiyiTheme.textSecondary)
                }

                Section("当前权益") {
                    MembershipSummaryCard(profile: viewModel.profile)
                }

                Section("套餐") {
                    if viewModel.profile.plans.isEmpty {
                        EmptyStateView(title: "暂无套餐", message: "套餐配置由服务端后台下发。", systemImage: "creditcard")
                            .listRowInsets(EdgeInsets())
                    } else {
                        if viewModel.profile.active {
                            Label("已有会员再买套餐会追加本月额度，截止日期刷新为从今天起 1 个月。", systemImage: "clock")
                                .font(.footnote)
                                .foregroundStyle(HuiyiTheme.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        ForEach(viewModel.profile.plans) { plan in
                            Button {
                                viewModel.selectPlan(plan)
                            } label: {
                                PlanRow(plan: plan, selected: viewModel.selectedPlanId == plan.id && viewModel.selectedAddonId == nil)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                if !viewModel.profile.addons.isEmpty {
                    Section("加量包") {
                        if !viewModel.profile.active || viewModel.profile.frozen {
                            Text("需先开通有效会员后才能购买加量包。")
                                .font(.footnote)
                                .foregroundStyle(HuiyiTheme.textSecondary)
                        }
                        ForEach(viewModel.profile.addons) { addon in
                            Button {
                                viewModel.selectAddon(addon)
                            } label: {
                                AddonRow(
                                    addon: addon,
                                    selected: viewModel.selectedAddonId == addon.id,
                                    enabled: viewModel.profile.active && !viewModel.profile.frozen
                                )
                            }
                            .buttonStyle(.plain)
                            .disabled(!viewModel.profile.active || viewModel.profile.frozen)
                        }
                    }
                }

                Section("订单记录") {
                    if viewModel.orders.isEmpty {
                        Text("暂无订单")
                            .foregroundStyle(HuiyiTheme.textSecondary)
                    } else {
                        ForEach(viewModel.orders) { order in
                            OrderRow(order: order)
                        }
                    }
                }

                Section {
                    PrimaryActionButton(title: purchaseButtonTitle, systemImage: "cart") {
                        if let plan = viewModel.selectedPlan, viewModel.profile.active {
                            pendingPlanConfirmation = plan
                        } else {
                            Task { await viewModel.beginPurchase(session: session) }
                        }
                    }
                    .disabled(!viewModel.canBeginPurchase)
                    SecondaryActionButton(title: "恢复购买", systemImage: "arrow.clockwise") {
                        Task { await viewModel.restorePurchases(session: session) }
                    }
                    Text("iOS 端使用 Apple 内购。购买成功后权益会同步到同一账号，Android 和 iOS 共用会员与额度。")
                        .font(.footnote)
                        .foregroundStyle(HuiyiTheme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .navigationTitle("会员")
            .alert("确认购买套餐", isPresented: Binding(
                get: { pendingPlanConfirmation != nil },
                set: { if !$0 { pendingPlanConfirmation = nil } }
            )) {
                Button("继续") {
                    pendingPlanConfirmation = nil
                    Task { await viewModel.beginPurchase(session: session) }
                }
                Button("取消", role: .cancel) {
                    pendingPlanConfirmation = nil
                }
            } message: {
                if let plan = pendingPlanConfirmation {
                    Text("你当前已有会员。本次购买会追加 \(plan.hours.cleanHourText) 小时转写和 \(plan.knowledgeQa) 次问答额度；会员截止日期刷新为从今天起 1 个月，不会按原截止日期继续顺延。")
                }
            }
            .refreshable { await viewModel.load(session: session) }
            .task {
                if viewModel.profile.planId == "none" {
                    await viewModel.load(session: session)
                }
            }
        }
    }

    private var purchaseButtonTitle: String {
        if let addon = viewModel.selectedAddon {
            return "购买 \(addon.name)"
        }
        if let plan = viewModel.selectedPlan {
            return viewModel.profile.active ? "续费 \(plan.name)" : "购买 \(plan.name)"
        }
        if viewModel.profile.frozen {
            return "账号已冻结"
        }
        if viewModel.isPurchasing {
            return "正在确认"
        }
        if viewModel.isLoadingProducts {
            return "正在读取商品"
        }
        if !viewModel.profile.appleIapEnabled {
            return "iOS 内购暂未开通"
        }
        return "选择套餐"
    }
}

private struct MembershipSummaryCard: View {
    let profile: MembershipProfile

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(profile.planName)
                        .font(.title3.weight(.semibold))
                        .lineLimit(2)
                    Text(profile.expiresAt ?? "未开通有效期")
                        .font(.footnote)
                        .foregroundStyle(HuiyiTheme.textSecondary)
                }
                Spacer()
                Text(profile.active ? "有效" : "未开通")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background((profile.active ? HuiyiTheme.success : HuiyiTheme.textSecondary).opacity(0.14), in: Capsule())
                    .foregroundStyle(profile.active ? HuiyiTheme.success : HuiyiTheme.textSecondary)
            }

            QuotaRow(title: "转写分钟", used: profile.transcriptionMinutesUsed, total: profile.transcriptionMinutesTotal)
            QuotaRow(title: "知识库问答", used: profile.knowledgeQaUsed, total: profile.knowledgeQaTotal)
        }
        .padding(.vertical, 8)
    }
}

private struct QuotaRow: View {
    let title: String
    let used: Int
    let total: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title)
                Spacer()
                Text("\(max(0, total - used)) / \(total)")
                    .monospacedDigit()
                    .foregroundStyle(HuiyiTheme.textSecondary)
            }
            ProgressView(value: total > 0 ? min(1, max(0, Double(used) / Double(total))) : 0)
        }
        .font(.subheadline)
    }
}

private struct PlanRow: View {
    let plan: MembershipPlan
    var selected = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(plan.name)
                    .font(.headline)
                    .lineLimit(2)
                Spacer()
                Text(String(format: "¥%.2f", plan.price))
                    .font(.headline.monospacedDigit())
            }
            Text("转写 \(plan.transcriptionMinutes) 分钟 · 知识库问答 \(plan.knowledgeQa) 次")
                .font(.subheadline)
                .foregroundStyle(HuiyiTheme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 4)
        .padding(.horizontal, selected ? 8 : 0)
        .background(selected ? HuiyiTheme.accent.opacity(0.08) : Color.clear, in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct AddonRow: View {
    let addon: MembershipAddon
    let selected: Bool
    let enabled: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: selected ? "checkmark.circle.fill" : "plus.circle")
                .foregroundStyle(selected ? HuiyiTheme.accent : HuiyiTheme.textSecondary)
            VStack(alignment: .leading, spacing: 6) {
                Text(addon.name)
                    .font(.headline)
                    .lineLimit(2)
                Text("补充 1 \(addon.unit.unitLabel)转写额度")
                    .font(.subheadline)
                    .foregroundStyle(HuiyiTheme.textSecondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(String(format: "¥%.2f", addon.price))
                    .font(.headline.monospacedDigit())
                Text("每\(addon.unit.unitLabel)")
                    .font(.caption)
                    .foregroundStyle(HuiyiTheme.textSecondary)
            }
        }
        .opacity(enabled ? 1 : 0.5)
        .padding(.vertical, 4)
        .padding(.horizontal, selected ? 8 : 0)
        .background(selected ? HuiyiTheme.accent.opacity(0.08) : Color.clear, in: RoundedRectangle(cornerRadius: 8))
    }
}

private extension String {
    var unitLabel: String {
        switch lowercased() {
        case "hour", "hours": return "小时"
        case "minute", "minutes": return "分钟"
        default: return self
        }
    }
}

private extension Double {
    var cleanHourText: String {
        truncatingRemainder(dividingBy: 1) == 0 ? String(Int(self)) : String(format: "%.1f", self)
    }
}

private struct OrderRow: View {
    let order: PaymentOrder

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(order.productName)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(2)
                Spacer()
                Text(String(format: "¥%.2f", order.amount))
                    .font(.subheadline.monospacedDigit())
            }
            HStack {
                Text(order.status)
                Spacer()
                Text(order.createdAt)
                    .lineLimit(1)
            }
            .font(.caption)
            .foregroundStyle(HuiyiTheme.textSecondary)
        }
        .padding(.vertical, 4)
    }
}
