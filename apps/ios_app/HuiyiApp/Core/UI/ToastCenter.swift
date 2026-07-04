import SwiftUI

@MainActor
final class ToastCenter: ObservableObject {
    @Published var message: String?

    func show(_ message: String) {
        self.message = message
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            self?.message = nil
        }
    }
}

struct ToastOverlay: ViewModifier {
    @ObservedObject var center: ToastCenter

    func body(content: Content) -> some View {
        ZStack(alignment: .top) {
            content
            if let message = center.message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(.black.opacity(0.82), in: Capsule())
                    .padding(.top, 12)
                    .padding(.horizontal, 16)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: center.message)
    }
}

extension View {
    func toastOverlay(_ center: ToastCenter) -> some View {
        modifier(ToastOverlay(center: center))
    }
}
