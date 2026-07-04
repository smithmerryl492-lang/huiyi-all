import Foundation

final class LocalResultStore {
    private let cacheStore: ClientCacheStore
    private let key = "local_meeting_results"

    init(cacheStore: ClientCacheStore) {
        self.cacheStore = cacheStore
    }

    func save(_ result: MeetingProcessingResult, taskId: String) {
        var results = loadAll()
        results[taskId] = result
        try? cacheStore.save(results, key: key)
    }

    func load(taskId: String) -> MeetingProcessingResult? {
        loadAll()[taskId]
    }

    func remove(taskId: String) {
        var results = loadAll()
        results.removeValue(forKey: taskId)
        try? cacheStore.save(results, key: key)
    }

    func clearAll() {
        cacheStore.remove(key: key)
    }

    private func loadAll() -> [String: MeetingProcessingResult] {
        cacheStore.load([String: MeetingProcessingResult].self, key: key) ?? [:]
    }
}
