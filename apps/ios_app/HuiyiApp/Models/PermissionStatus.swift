import AVFoundation
import Foundation
import UIKit
import UserNotifications

struct PermissionStatus: Equatable, Sendable {
    var microphoneEnabled: Bool
    var notificationEnabled: Bool
    var fileAccessLabel: String

    static let unknown = PermissionStatus(
        microphoneEnabled: false,
        notificationEnabled: false,
        fileAccessLabel: "按次授权"
    )
}

enum PermissionStatusReader {
    static func read() async -> PermissionStatus {
        let microphone = AVAudioSession.sharedInstance().recordPermission == .granted
        let notificationSettings = await UNUserNotificationCenter.current().notificationSettings()
        let notification = notificationSettings.authorizationStatus == .authorized || notificationSettings.authorizationStatus == .provisional
        return PermissionStatus(
            microphoneEnabled: microphone,
            notificationEnabled: notification,
            fileAccessLabel: "按次授权"
        )
    }

    static func requestMicrophonePermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    static func requestNotificationPermission() async -> Bool {
        do {
            return try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound])
        } catch {
            return false
        }
    }

    static func openAppSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}
