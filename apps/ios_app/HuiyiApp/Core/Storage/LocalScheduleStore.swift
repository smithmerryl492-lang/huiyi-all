import Foundation

final class LocalScheduleStore {
    private let cacheStore: ClientCacheStore

    init(cacheStore: ClientCacheStore) {
        self.cacheStore = cacheStore
    }

    func load(userId: String) -> [ScheduledMeeting] {
        cacheStore.load([ScheduledMeeting].self, key: key(userId: userId)) ?? []
    }

    func save(_ schedules: [ScheduledMeeting], userId: String) {
        try? cacheStore.save(schedules, key: key(userId: userId))
    }

    func upsert(_ schedule: ScheduledMeeting, userId: String) {
        let next = (load(userId: userId).filter { $0.id != schedule.id } + [schedule])
            .sorted { ($0.startAtMillis ?? $0.createdAtMillis) < ($1.startAtMillis ?? $1.createdAtMillis) }
        save(next, userId: userId)
    }

    func delete(id: String, userId: String) {
        save(load(userId: userId).filter { $0.id != id }, userId: userId)
    }

    func clear(userId: String) {
        cacheStore.remove(key: key(userId: userId))
    }

    private func key(userId: String) -> String {
        "local_schedules_\(userId)"
    }
}
