import Foundation
import SwiftUI

@MainActor
final class AppRouter: ObservableObject {
    @Published var screen: AppScreen = .home
    @Published var selectedMeetingId: String?
    @Published var selectedTaskId: String?
    @Published var selectedOrderId: String?
    @Published var selectedRecordingSchedule: ScheduledMeeting?
    @Published var pendingSourceTodo: TodoItem?
    @Published var pendingKnowledgeSource: KnowledgeSource?
    @Published var autoStartProcessing = false
    private var detailReturnScreen: AppScreen = .home
    private var processingReturnScreen: AppScreen = .home
    private var paymentOrdersReturnScreen: AppScreen = .profile

    func go(_ screen: AppScreen) {
        if screen == .recording {
            selectedRecordingSchedule = nil
        }
        if screen == .processing && self.screen != .processing {
            processingReturnScreen = self.screen.returnTargetForTransientScreen
        }
        if screen == .paymentOrders && self.screen != .paymentOrders {
            paymentOrdersReturnScreen = self.screen.returnTargetForTransientScreen
        }
        self.screen = screen
    }

    func startRecording(schedule: ScheduledMeeting? = nil) {
        selectedRecordingSchedule = schedule
        screen = .recording
    }

    func openMeeting(_ id: String) {
        rememberDetailReturnScreen()
        selectedMeetingId = id
        pendingSourceTodo = nil
        pendingKnowledgeSource = nil
        screen = .meetingDetail
    }

    func openMeeting(_ id: String, sourceTodo: TodoItem) {
        rememberDetailReturnScreen()
        selectedMeetingId = id
        pendingSourceTodo = sourceTodo
        pendingKnowledgeSource = nil
        screen = .meetingDetail
    }

    func openMeeting(_ id: String, knowledgeSource: KnowledgeSource) {
        rememberDetailReturnScreen()
        selectedMeetingId = id
        pendingSourceTodo = nil
        pendingKnowledgeSource = knowledgeSource
        screen = .meetingDetail
    }

    func openProcessing(taskId: String, autoStart: Bool = false) {
        if screen != .processing {
            processingReturnScreen = screen.returnTargetForTransientScreen
        }
        selectedTaskId = taskId
        autoStartProcessing = autoStart
        screen = .processing
    }

    func openOrder(_ id: String) {
        if screen != .paymentOrders {
            paymentOrdersReturnScreen = screen.returnTargetForTransientScreen
        }
        selectedOrderId = id
        screen = .paymentOrders
    }

    func backFromDetail() {
        screen = detailReturnScreen.returnTargetForTransientScreen
    }

    func backFromProcessing() {
        screen = processingReturnScreen.returnTargetForTransientScreen
    }

    func backFromPaymentOrders() {
        screen = paymentOrdersReturnScreen.returnTargetForTransientScreen
    }

    private func rememberDetailReturnScreen() {
        if screen != .meetingDetail {
            detailReturnScreen = screen.returnTargetForTransientScreen
        }
    }
}

enum AppScreen: String, Codable, Sendable {
    case login
    case home
    case tasks
    case meetings
    case schedules
    case search
    case meetingDetail
    case recording
    case importFile
    case processing
    case knowledge
    case membership
    case paymentOrders
    case accountSecurity
    case voiceprints
    case userAgreement
    case privacyPolicy
    case profile
}

private extension AppScreen {
    var returnTargetForTransientScreen: AppScreen {
        switch self {
        case .meetingDetail, .recording, .processing:
            return .home
        default:
            return self
        }
    }
}
