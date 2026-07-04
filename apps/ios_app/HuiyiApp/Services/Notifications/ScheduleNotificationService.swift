import Foundation
import UserNotifications

struct ScheduleNotificationService {
    private let center: UNUserNotificationCenter

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    func scheduleReminder(for schedule: ScheduledMeeting, requestingPermission: Bool = false) async {
        guard let startAtMillis = schedule.startAtMillis else { return }
        let triggerDate = Date(timeIntervalSince1970: TimeInterval(startAtMillis - 5 * 60_000) / 1000)
        guard triggerDate > Date() else { return }

        do {
            let granted: Bool
            if requestingPermission {
                granted = try await center.requestAuthorization(options: [.alert, .sound, .badge])
            } else {
                let settings = await center.notificationSettings()
                granted = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
            }
            guard granted else { return }
            let content = UNMutableNotificationContent()
            content.title = "会议即将开始"
            content.body = schedule.title
            content.sound = .default
            if !schedule.participants.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                content.subtitle = schedule.participants
            }

            let components = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: triggerDate)
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            let request = UNNotificationRequest(identifier: identifier(for: schedule.id), content: content, trigger: trigger)
            try await center.add(request)
        } catch {
            // Notification delivery is a best-effort reminder; the app-internal reminder still covers the workflow.
        }
    }

    func cancelReminder(scheduleId: String) {
        center.removePendingNotificationRequests(withIdentifiers: [identifier(for: scheduleId)])
        center.removeDeliveredNotifications(withIdentifiers: [identifier(for: scheduleId)])
    }

    func cancelAllReminders() {
        let notificationCenter = center
        notificationCenter.getPendingNotificationRequests { requests in
            let ids = requests.map(\.identifier).filter { $0.hasPrefix("schedule-reminder.") }
            notificationCenter.removePendingNotificationRequests(withIdentifiers: ids)
        }
        notificationCenter.getDeliveredNotifications { notifications in
            let ids = notifications.map(\.request.identifier).filter { $0.hasPrefix("schedule-reminder.") }
            notificationCenter.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    private func identifier(for scheduleId: String) -> String {
        "schedule-reminder.\(scheduleId)"
    }
}
