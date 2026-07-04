import Foundation

struct ScheduledMeeting: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var time: String
    var title: String
    var participants: String
    var note: String
    var durationLabel: String
    var reminderLabel: String
    var startAtMillis: Int64?
    var endAtMillis: Int64?
    var createdAtMillis: Int64
    var status: String
    var calendarEventId: Int64?

    enum CodingKeys: String, CodingKey {
        case id
        case time
        case title
        case participants
        case note
        case durationLabel = "duration_label"
        case reminderLabel = "reminder_label"
        case startAtMillis = "start_at_millis"
        case endAtMillis = "end_at_millis"
        case createdAtMillis = "created_at_millis"
        case status
        case calendarEventId = "calendar_event_id"
    }

    init(
        id: String,
        time: String,
        title: String,
        participants: String,
        note: String = "",
        durationLabel: String,
        reminderLabel: String = "提前 5 分钟提醒",
        startAtMillis: Int64? = nil,
        endAtMillis: Int64? = nil,
        createdAtMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        status: String = "pending",
        calendarEventId: Int64? = nil
    ) {
        self.id = id
        self.time = time
        self.title = title
        self.participants = participants
        self.note = note
        self.durationLabel = durationLabel
        self.reminderLabel = reminderLabel.isEmpty ? "提前 5 分钟提醒" : reminderLabel
        self.startAtMillis = startAtMillis
        self.endAtMillis = endAtMillis
        self.createdAtMillis = createdAtMillis
        self.status = status.normalizedScheduleStatus
        self.calendarEventId = calendarEventId
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: container.decodeStringIfPresent(.id) ?? "",
            time: container.decodeStringIfPresent(.time) ?? "",
            title: container.decodeStringIfPresent(.title) ?? "",
            participants: container.decodeStringIfPresent(.participants) ?? "",
            note: container.decodeStringIfPresent(.note) ?? "",
            durationLabel: container.decodeStringIfPresent(.durationLabel) ?? "",
            reminderLabel: container.decodeStringIfPresent(.reminderLabel) ?? "提前 5 分钟提醒",
            startAtMillis: container.decodeInt64IfPresent(.startAtMillis),
            endAtMillis: container.decodeInt64IfPresent(.endAtMillis),
            createdAtMillis: container.decodeInt64IfPresent(.createdAtMillis) ?? Int64(Date().timeIntervalSince1970 * 1000),
            status: container.decodeStringIfPresent(.status) ?? "pending",
            calendarEventId: container.decodeInt64IfPresent(.calendarEventId)
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(time, forKey: .time)
        try container.encode(title, forKey: .title)
        try container.encode(participants, forKey: .participants)
        try container.encode(note, forKey: .note)
        try container.encode(durationLabel, forKey: .durationLabel)
        try container.encode(reminderLabel, forKey: .reminderLabel)
        try container.encodeIfPresent(startAtMillis, forKey: .startAtMillis)
        try container.encodeIfPresent(endAtMillis, forKey: .endAtMillis)
        try container.encode(createdAtMillis, forKey: .createdAtMillis)
        try container.encode(status, forKey: .status)
        try container.encodeIfPresent(calendarEventId, forKey: .calendarEventId)
    }

    func isFinished(nowMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        status == "finished"
    }

    func isToday(calendar: Calendar = .current) -> Bool {
        guard let startAtMillis else { return false }
        let date = Date(timeIntervalSince1970: TimeInterval(startAtMillis) / 1000)
        return calendar.isDateInToday(date)
    }

    func isOverdue(nowMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        let overdueAt = endAtMillis ?? startAtMillis
        if status == "overdue" {
            return true
        }
        guard status == "pending", let overdueAt else { return false }
        return overdueAt < nowMillis
    }
}

private extension String {
    var normalizedScheduleStatus: String {
        switch trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "overdue": return "overdue"
        case "finished": return "finished"
        default: return "pending"
        }
    }
}

private extension KeyedDecodingContainer {
    func decodeStringIfPresent(_ key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return clean.isEmpty || clean.lowercased() == "null" ? nil : clean
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return String(value)
        }
        if let value = try? decodeIfPresent(Int64.self, forKey: key) {
            return String(value)
        }
        return nil
    }

    func decodeInt64IfPresent(_ key: Key) -> Int64? {
        if let value = try? decodeIfPresent(Int64.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return Int64(value)
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return Int64(value)
        }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return Int64(value)
        }
        return nil
    }

    func decodeLossyArrayIfPresent<T: Decodable>(_ key: Key) -> [T] {
        guard var nested = try? nestedUnkeyedContainer(forKey: key) else { return [] }
        var values: [T] = []
        while !nested.isAtEnd {
            if let value = try? nested.decode(T.self) {
                values.append(value)
            } else {
                _ = try? nested.decode(DiscardedDecodable.self)
            }
        }
        return values
    }
}

private struct DiscardedDecodable: Decodable {
    init(from decoder: Decoder) throws {
        if var unkeyed = try? decoder.unkeyedContainer() {
            while !unkeyed.isAtEnd {
                _ = try? unkeyed.decode(DiscardedDecodable.self)
            }
            return
        }
        if let keyed = try? decoder.container(keyedBy: DiscardedCodingKey.self) {
            for key in keyed.allKeys {
                _ = try? keyed.decode(DiscardedDecodable.self, forKey: key)
            }
            return
        }
        if let single = try? decoder.singleValueContainer() {
            if single.decodeNil() { return }
            if (try? single.decode(Bool.self)) != nil { return }
            if (try? single.decode(Double.self)) != nil { return }
            _ = try? single.decode(String.self)
        }
    }
}

private struct DiscardedCodingKey: CodingKey {
    let stringValue: String
    let intValue: Int?

    init?(stringValue: String) {
        self.stringValue = stringValue
        intValue = nil
    }

    init?(intValue: Int) {
        stringValue = String(intValue)
        self.intValue = intValue
    }
}

struct CloudTaskItem: Codable, Sendable {
    let task: RemoteMeetingTask
    let file: FileRecord?
    let result: MeetingProcessingResult?
}

struct CloudBootstrapResponse: Codable, Sendable {
    let userId: String
    let tasks: [CloudTaskItem]
    let schedules: [ScheduledMeeting]

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case tasks
        case schedules
    }

    init(userId: String, tasks: [CloudTaskItem], schedules: [ScheduledMeeting]) {
        self.userId = userId
        self.tasks = tasks
        self.schedules = schedules
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        userId = container.decodeStringIfPresent(.userId) ?? ""
        tasks = container.decodeLossyArrayIfPresent(.tasks)
        schedules = container.decodeLossyArrayIfPresent(.schedules)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(userId, forKey: .userId)
        try container.encode(tasks, forKey: .tasks)
        try container.encode(schedules, forKey: .schedules)
    }
}

extension CloudBootstrapResponse {
    func mergingLocalSchedules(_ localSchedules: [ScheduledMeeting]) -> CloudBootstrapResponse {
        let merged = Dictionary(grouping: schedules + localSchedules, by: \.id)
            .compactMap { _, items in items.last }
            .sorted { ($0.startAtMillis ?? $0.createdAtMillis) < ($1.startAtMillis ?? $1.createdAtMillis) }
        return CloudBootstrapResponse(userId: userId, tasks: tasks, schedules: merged)
    }
}
