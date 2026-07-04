import Foundation

struct MeetingSummary: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var title: String
    var createdAt: String
    var createdAtMillis: Int64?
    var source: MeetingTaskSource
    var status: ClientMeetingTaskStatus
    var participants: String?
    var tags: [String]

    init(task: MeetingTask, result: MeetingProcessingResult?) {
        id = task.id
        title = task.title
        createdAt = ""
        createdAtMillis = task.createdAtMillis
        source = task.source
        status = task.status
        participants = result?.participants
        tags = result?.tags ?? []
    }
}

struct MeetingDetail: Codable, Equatable, Sendable {
    var task: MeetingTask
    var file: FileRecord?
    var result: MeetingProcessingResult?
}
