import SwiftUI

@main
struct HuiyiApp: App {
    private let environment: AppEnvironment
    @StateObject private var session: AppSession
    @StateObject private var router = AppRouter()
    @StateObject private var toastCenter = ToastCenter()

    init() {
        let environment = AppEnvironment.staging
        self.environment = environment
        _session = StateObject(
            wrappedValue: AppSession(
                tokenStore: TokenStore(),
                apiClient: APIClient(baseURL: environment.apiBaseURL)
            )
        )
    }

    var body: some Scene {
        WindowGroup {
            RootContentView(environment: environment)
                .environmentObject(session)
                .environmentObject(router)
                .environmentObject(toastCenter)
                .toastOverlay(toastCenter)
        }
    }
}

private struct RootContentView: View {
    let environment: AppEnvironment
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var router: AppRouter
    @EnvironmentObject private var toastCenter: ToastCenter

    var body: some View {
        Group {
            if session.currentUser == nil {
                switch router.screen {
                case .userAgreement:
                    LegalDocumentView(document: .userAgreement)
                case .privacyPolicy:
                    LegalDocumentView(document: .privacyPolicy)
                default:
                    LoginView()
                }
            } else {
                switch router.screen {
                case .meetings:
                    AllMeetingsView()
                case .schedules:
                    SchedulesView()
                case .search:
                    SearchView()
                case .recording:
                    RecordingView()
                case .importFile:
                    FileImportView()
                case .processing:
                    ProcessingView()
                case .meetingDetail:
                    MeetingDetailView()
                case .membership:
                    MembershipView()
                case .paymentOrders:
                    PaymentOrdersView()
                case .accountSecurity:
                    AccountSecurityView()
                case .voiceprints:
                    VoiceprintsView()
                case .userAgreement:
                    LegalDocumentView(document: .userAgreement)
                case .privacyPolicy:
                    LegalDocumentView(document: .privacyPolicy)
                default:
                    MainTabView()
                }
            }
        }
        .onChange(of: session.authStateMessage) { message in
            guard let message = message, !message.isEmpty else { return }
            toastCenter.show(message)
            router.screen = .home
            _ = session.consumeAuthStateMessage()
        }
    }
}

private struct MainTabView: View {
    @EnvironmentObject private var router: AppRouter

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                switch router.screen {
                case .tasks:
                    TasksView()
                case .knowledge:
                    KnowledgeView()
                case .profile:
                    ProfileView()
                default:
                    MeetingHomeView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            SmartBottomNavigationBar(current: $router.screen, roots: [.home, .tasks, .knowledge, .profile])
                .ignoresSafeArea(.keyboard, edges: .bottom)
        }
        .huiyiScreenBackground()
    }
}
