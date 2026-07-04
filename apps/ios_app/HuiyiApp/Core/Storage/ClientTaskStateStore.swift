import Foundation

final class ClientTaskStateStore {
    private let fileURL: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(rootDirectory: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]) {
        fileURL = rootDirectory.appendingPathComponent("client-tasks.json")
    }

    func load() -> [MeetingTask] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        return (try? decoder.decode([MeetingTask].self, from: data)) ?? []
    }

    func save(_ tasks: [MeetingTask]) throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try encoder.encode(tasks)
        try data.write(to: fileURL, options: [.atomic])
    }

    func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }
}
