import SwiftUI

enum HuiyiTheme {
    static let appBg = Color(red: 0.961, green: 0.973, blue: 1.000)
    static let ink = Color(red: 0.102, green: 0.102, blue: 0.102)
    static let muted = Color(red: 0.420, green: 0.486, blue: 0.576)
    static let line = Color(red: 0.902, green: 0.929, blue: 0.973)
    static let brand = Color(red: 0.357, green: 0.541, blue: 1.000)
    static let brandDark = Color(red: 0.271, green: 0.431, blue: 0.655)
    static let brandCyan = Color(red: 0.353, green: 0.784, blue: 0.980)
    static let brandPurple = Color(red: 0.655, green: 0.545, blue: 0.980)
    static let brandSoft = Color(red: 0.929, green: 0.953, blue: 1.000)
    static let brandSoftCyan = Color(red: 0.918, green: 0.969, blue: 1.000)
    static let pageBlueTop = Color(red: 0.773, green: 0.878, blue: 1.000)
    static let pageBlueMid = Color(red: 0.878, green: 0.941, blue: 1.000)
    static let headerBlue = Color(red: 0.369, green: 0.522, blue: 1.000)
    static let headerCyan = Color(red: 0.153, green: 0.643, blue: 1.000)
    static let warmSoft = Color(red: 1.000, green: 0.953, blue: 0.894)
    static let success = Color(red: 0.188, green: 0.682, blue: 0.420)
    static let warning = Color(red: 0.780, green: 0.545, blue: 0.000)
    static let danger = Color(red: 0.894, green: 0.345, blue: 0.259)

    static let accent = brand
    static let background = appBg
    static let surface = Color.white.opacity(0.96)
    static let textPrimary = ink
    static let textSecondary = muted

    static var pageGradient: LinearGradient {
        LinearGradient(
            colors: [pageBlueTop, pageBlueMid, Color(red: 0.961, green: 0.973, blue: 1.000), appBg],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    static var headerGradient: LinearGradient {
        LinearGradient(
            stops: [
                .init(color: headerBlue, location: 0.0),
                .init(color: headerCyan, location: 0.55),
                .init(color: headerCyan.opacity(0.0), location: 1.0)
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    static var primaryGradient: LinearGradient {
        LinearGradient(colors: [brand, Color(red: 0.486, green: 0.659, blue: 1.000), brandCyan], startPoint: .leading, endPoint: .trailing)
    }

    static var recordGradient: LinearGradient {
        LinearGradient(colors: [brandCyan.opacity(0.88), Color(red: 0.482, green: 0.624, blue: 1.000), brandPurple.opacity(0.88)], startPoint: .leading, endPoint: .trailing)
    }
}

struct ScreenBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(
                SmartPageBackdrop()
                    .ignoresSafeArea()
            )
    }
}

extension View {
    func huiyiScreenBackground() -> some View {
        modifier(ScreenBackground())
    }
}
