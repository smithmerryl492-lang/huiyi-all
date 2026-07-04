import Foundation

final class ClientCacheStore {
    private let directory: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(rootDirectory: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]) {
        directory = rootDirectory.appendingPathComponent("HuiyiCache", isDirectory: true)
    }

    func load<Value: Decodable>(_ type: Value.Type, key: String) -> Value? {
        let url = cacheURL(for: key)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(Value.self, from: data)
    }

    func save<Value: Encodable>(_ value: Value, key: String) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try encoder.encode(value)
        try data.write(to: cacheURL(for: key), options: [.atomic])
    }

    func remove(key: String) {
        try? FileManager.default.removeItem(at: cacheURL(for: key))
    }

    func clearAll() {
        try? FileManager.default.removeItem(at: directory)
    }

    private func cacheURL(for key: String) -> URL {
        directory.appendingPathComponent(key.sanitizedFileName).appendingPathExtension("json")
    }
}

private extension String {
    var sanitizedFileName: String {
        let invalid = CharacterSet(charactersIn: "\\/:*?\"<>|")
        return components(separatedBy: invalid).joined(separator: "_")
    }
}
