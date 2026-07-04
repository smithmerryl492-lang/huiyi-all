import Foundation

@MainActor
final class MeetingsViewModel: ObservableObject {
    @Published private(set) var meetings: [MeetingDetail] = []
    @Published private(set) var schedules: [ScheduledMeeting] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    var visibleMeetings: [MeetingDetail] {
        meetings.sorted { left, right in
            left.task.createdAtMillis > right.task.createdAtMillis
        }
    }

    func load(session: AppSession) async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let bootstrap = try await session.loadCloudBootstrap()
            let cloudMeetings = bootstrap.tasks.map { item in
                MeetingDetail(task: item.task.toClientTask(), file: item.file, result: item.result)
            }
            let cloudIds = Set(cloudMeetings.flatMap { [$0.task.id, $0.task.remoteTaskId].compactMap { $0 } })
            let localMeetings = session.localMeetingDetails().filter { !cloudIds.contains($0.task.id) && !cloudIds.contains($0.task.remoteTaskId ?? "") }
            meetings = cloudMeetings + localMeetings
            schedules = bootstrap.schedules
        } catch {
            meetings = session.localMeetingDetails()
            errorMessage = userMessage(error)
        }
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }
}
