import SwiftUI

struct SmartPageBackdrop: View {
    var body: some View {
        ZStack(alignment: .topLeading) {
            HuiyiTheme.pageGradient
            Circle()
                .fill(HuiyiTheme.brandPurple.opacity(0.22))
                .frame(width: 280, height: 280)
                .blur(radius: 10)
                .offset(x: -118, y: -132)
            Circle()
                .fill(HuiyiTheme.brandCyan.opacity(0.18))
                .frame(width: 220, height: 220)
                .blur(radius: 14)
                .offset(x: 230, y: 70)
        }
    }
}

struct SmartPage<Content: View>: View {
    var topPadding: CGFloat = 14
    var horizontalPadding: CGFloat = 18
    var bottomPadding: CGFloat = 24
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                content()
            }
            .padding(.horizontal, horizontalPadding)
            .padding(.top, topPadding)
            .padding(.bottom, bottomPadding)
        }
        .scrollIndicators(.hidden)
        .huiyiScreenBackground()
    }
}

struct SmartScreenScaffold<Content: View, Trailing: View>: View {
    let title: String
    var onBack: (() -> Void)?
    var trailing: () -> Trailing
    @ViewBuilder let content: () -> Content

    init(
        title: String,
        onBack: (() -> Void)? = nil,
        @ViewBuilder trailing: @escaping () -> Trailing,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.onBack = onBack
        self.trailing = trailing
        self.content = content
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                if let onBack {
                    SmartRoundIconButton(systemImage: "chevron.left", accessibilityLabel: "返回", action: onBack)
                }
                Text(title)
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(HuiyiTheme.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
                    .frame(maxWidth: .infinity, alignment: onBack == nil ? .leading : .center)
                trailing()
                    .frame(minWidth: onBack == nil ? 0 : 40, alignment: .trailing)
            }
            .padding(.horizontal, 14)
            .padding(.top, 12)
            .padding(.bottom, 8)

            SmartPage(topPadding: 6, content: content)
        }
        .huiyiScreenBackground()
        .navigationBarHidden(true)
    }
}

extension SmartScreenScaffold where Trailing == EmptyView {
    init(title: String, onBack: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, onBack: onBack, trailing: { EmptyView() }, content: content)
    }
}

struct SmartCard<Content: View>: View {
    var radius: CGFloat = 16
    var padding: CGFloat = 16
    var color: Color = Color.white.opacity(0.96)
    var borderColor: Color = Color.white.opacity(0.72)
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            content()
        }
        .padding(padding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(color, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: radius, style: .continuous)
                .stroke(borderColor, lineWidth: 1)
        )
        .shadow(color: HuiyiTheme.brandDark.opacity(0.08), radius: 10, x: 0, y: 4)
    }
}

struct SmartGradientPanel<Content: View>: View {
    var radius: CGFloat = 24
    var gradient: LinearGradient = HuiyiTheme.recordGradient
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack {
            gradient
            content()
        }
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

struct SmartRoundIconButton: View {
    let systemImage: String
    var accessibilityLabel: String?
    var tint: Color = HuiyiTheme.ink
    var container: Color = Color.white.opacity(0.88)
    var size: CGFloat = 40
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: size, height: size)
                .background(container, in: Circle())
                .shadow(color: HuiyiTheme.brandDark.opacity(0.10), radius: 8, x: 0, y: 3)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel ?? "")
    }
}

struct SmartGradientIcon: View {
    let systemImage: String
    var tint: Color = HuiyiTheme.brand
    var size: CGFloat = 48

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.48, weight: .semibold))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(Color.white.opacity(0.72), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Color.white.opacity(0.72), lineWidth: 1)
            )
            .shadow(color: HuiyiTheme.brandDark.opacity(0.08), radius: 6, x: 0, y: 2)
    }
}

struct SmartSearchPill: View {
    let text: String
    var systemImage: String = "magnifyingglass"
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                    .font(.system(size: 15, weight: .semibold))
                Text(text)
                    .font(.system(size: 14, weight: .semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Spacer(minLength: 0)
            }
            .foregroundStyle(.white)
            .frame(height: 44)
            .padding(.horizontal, 16)
            .background(Color.white.opacity(0.30), in: Capsule())
            .overlay(Capsule().stroke(Color.white.opacity(0.18), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

struct SmartSectionHeader: View {
    let title: String
    var primaryAction: String?
    var onPrimaryAction: (() -> Void)?
    var secondaryAction: String?
    var onSecondaryAction: (() -> Void)?

    var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(HuiyiTheme.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
            Spacer()
            if let primaryAction, let onPrimaryAction {
                Button(primaryAction, action: onPrimaryAction)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(HuiyiTheme.muted)
                    .buttonStyle(.plain)
            }
            if let secondaryAction, let onSecondaryAction {
                Button(secondaryAction, action: onSecondaryAction)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(HuiyiTheme.brand)
                    .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

struct SmartStatusBadge: View {
    let text: String
    var color: Color = HuiyiTheme.brand
    var background: Color? = nil

    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: .bold))
            .lineLimit(1)
            .minimumScaleFactor(0.85)
            .foregroundStyle(color)
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .background(background ?? color.opacity(0.14), in: Capsule())
    }
}

struct SmartSegmentedPicker<Item: Identifiable & Equatable>: View {
    let items: [Item]
    @Binding var selection: Item
    let title: (Item) -> String
    var enabled: Bool = true

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items) { item in
                let selected = item == selection
                Button {
                    if enabled && !selected {
                        selection = item
                    }
                } label: {
                    Text(title(item))
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(selected ? HuiyiTheme.brand : HuiyiTheme.muted)
                        .lineLimit(1)
                        .minimumScaleFactor(0.72)
                        .frame(maxWidth: .infinity, minHeight: 40)
                        .background(selected ? Color.white : Color.clear, in: Capsule())
                }
                .disabled(!enabled || selected)
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(Color(red: 0.945, green: 0.969, blue: 1.000), in: Capsule())
    }
}

struct SmartBottomNavigationBar: View {
    @Binding var current: AppScreen
    let roots: [AppScreen]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(roots, id: \.rawValue) { screen in
                let selected = current == screen
                Button {
                    current = screen
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: screen.tabSystemImage)
                            .font(.system(size: 20, weight: .semibold))
                        Text(screen.tabTitle)
                            .font(.system(size: 12, weight: selected ? .bold : .medium))
                            .lineLimit(1)
                            .minimumScaleFactor(0.78)
                    }
                    .foregroundStyle(selected ? HuiyiTheme.brand : Color(red: 0.549, green: 0.576, blue: 0.627))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .background(selected ? HuiyiTheme.brandSoft : Color.clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background(Color.white.opacity(0.96))
        .overlay(Rectangle().fill(Color.white.opacity(0.82)).frame(height: 1), alignment: .top)
        .shadow(color: HuiyiTheme.brandDark.opacity(0.12), radius: 14, x: 0, y: -4)
    }
}

struct PrimaryActionButton: View {
    let title: String
    var systemImage: String?
    var isLoading: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if isLoading {
                    ProgressView()
                        .tint(.white)
                } else if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 18, weight: .semibold))
                }
                Text(title)
                    .font(.system(size: 16, weight: .bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(HuiyiTheme.primaryGradient, in: Capsule())
            .shadow(color: HuiyiTheme.brand.opacity(0.20), radius: 10, x: 0, y: 5)
        }
        .buttonStyle(.plain)
        .disabled(isLoading)
        .opacity(isLoading ? 0.72 : 1)
    }
}

struct SecondaryActionButton: View {
    let title: String
    var systemImage: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 16, weight: .semibold))
                }
                Text(title)
                    .font(.system(size: 15, weight: .bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }
            .foregroundStyle(HuiyiTheme.brand)
            .frame(maxWidth: .infinity, minHeight: 46)
            .background(Color.white.opacity(0.92), in: Capsule())
            .overlay(Capsule().stroke(HuiyiTheme.brand.opacity(0.22), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

struct EmptyStateView: View {
    let title: String
    let message: String
    var systemImage: String = "tray"

    var body: some View {
        VStack(spacing: 12) {
            SmartGradientIcon(systemImage: systemImage, tint: Color(red: 0.718, green: 0.784, blue: 0.937), size: 52)
            Text(title)
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(HuiyiTheme.ink)
                .multilineTextAlignment(.center)
            Text(message)
                .font(.system(size: 13))
                .foregroundStyle(HuiyiTheme.muted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity)
        .padding(22)
    }
}

struct ErrorBanner: View {
    let message: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(HuiyiTheme.danger)
            Text(message)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Color(red: 0.761, green: 0.239, blue: 0.165))
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(red: 1.000, green: 0.941, blue: 0.929), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color(red: 1.000, green: 0.788, blue: 0.745), lineWidth: 1)
        )
    }
}

struct SmartInfoBlock<Content: View>: View {
    let title: String
    var actionTitle: String?
    var action: (() -> Void)?
    @ViewBuilder let content: () -> Content

    var body: some View {
        SmartCard(radius: 16, padding: 16) {
            HStack {
                Text(title)
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(HuiyiTheme.ink)
                Spacer()
                if let actionTitle, let action {
                    Button(actionTitle, action: action)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(HuiyiTheme.brand)
                        .buttonStyle(.plain)
                }
            }
            content()
        }
    }
}

struct SmartRowButton: View {
    let systemImage: String
    let title: String
    var subtitle: String?
    var tint: Color = HuiyiTheme.brandDark
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(tint)
                    .frame(width: 40, height: 40)
                    .background(HuiyiTheme.brandSoftCyan, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(HuiyiTheme.ink)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.system(size: 13))
                            .foregroundStyle(HuiyiTheme.muted)
                            .lineLimit(2)
                    }
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(HuiyiTheme.muted)
            }
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }
}

struct WaveBars: View {
    var active: Bool = true
    var levelPercent: Int? = nil
    var barCount: Int = 6
    var maxHeight: CGFloat = 30

    var body: some View {
        HStack(alignment: .center, spacing: 5) {
            ForEach(0..<barCount, id: \.self) { index in
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(index == 3 ? HuiyiTheme.brandCyan : Color(red: 0.494, green: 0.569, blue: 1.000))
                    .frame(width: 4, height: barHeight(index: index))
                    .animation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true).delay(Double(index) * 0.07), value: active)
            }
        }
        .frame(height: maxHeight)
    }

    private func barHeight(index: Int) -> CGFloat {
        let base: [CGFloat] = [14, 23, 18, 30, 22, 27, 16, 25]
        let scale = CGFloat(levelPercent.map { 0.45 + Double(max(0, min(100, $0))) / 100.0 * 0.75 } ?? 1)
        return min(maxHeight, max(6, base[index % base.count] * scale))
    }
}

extension AppScreen {
    var tabTitle: String {
        switch self {
        case .home: return "会议"
        case .tasks: return "待办"
        case .knowledge: return "知识库"
        case .profile: return "我的"
        default: return ""
        }
    }

    var tabSystemImage: String {
        switch self {
        case .home: return "list.bullet.rectangle"
        case .tasks: return "checklist"
        case .knowledge: return "magnifyingglass"
        case .profile: return "person.crop.circle"
        default: return "circle"
        }
    }
}
