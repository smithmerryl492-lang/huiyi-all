import Foundation

final class AudioFileStore {
    enum Bucket: String {
        case recordings = "Recordings"
        case imports = "Imports"
        case cache = "AudioCache"
    }

    private let documentsDirectory: URL

    init(documentsDirectory: URL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]) {
        self.documentsDirectory = documentsDirectory
    }

    func directory(for bucket: Bucket) throws -> URL {
        let url = documentsDirectory.appendingPathComponent(bucket.rawValue, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    func recordingURL(taskId: String) throws -> URL {
        try directory(for: .recordings).appendingPathComponent(taskId).appendingPathExtension("wav")
    }

    func recoverableRecordings(minimumBytes: Int64 = 4096) -> [URL] {
        guard let directory = try? directory(for: .recordings),
              let urls = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey])
        else {
            return []
        }
        return urls.filter { url in
            let ext = url.pathExtension.lowercased()
            guard ["wav", "m4a", "caf"].contains(ext) else { return false }
            guard let values = try? url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]),
                  values.isRegularFile == true,
                  Int64(values.fileSize ?? 0) > minimumBytes
            else {
                return false
            }
            return true
        }
    }

    func cachedAudioURL(taskId: String, originalName: String?) throws -> URL {
        let ext = originalName.flatMap { name -> String? in
            let value = (name as NSString).pathExtension
            return value.isEmpty ? nil : value
        } ?? "m4a"
        return try directory(for: .cache).appendingPathComponent(taskId.sanitizedImportName).appendingPathExtension(ext)
    }

    func uniqueImportURL(displayName: String) throws -> URL {
        let directory = try directory(for: .imports)
        let safeName = displayName.sanitizedImportName
        let base = (safeName as NSString).deletingPathExtension
        let ext = (safeName as NSString).pathExtension
        var index = 0
        while true {
            let suffix = index == 0 ? "" : "_\(index)"
            let filename = ext.isEmpty ? "\(base)\(suffix)" : "\(base)\(suffix).\(ext)"
            let candidate = directory.appendingPathComponent(filename)
            if !FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
            index += 1
        }
    }

    func removeFile(path: String) {
        let target = URL(fileURLWithPath: path)
        guard isManagedAudioFile(target) else { return }
        try? FileManager.default.removeItem(at: target)
    }

    func clearAllBuckets() {
        for bucket in [Bucket.recordings, .imports, .cache] {
            if let directory = try? directory(for: bucket) {
                try? FileManager.default.removeItem(at: directory)
            }
        }
    }

    private func isManagedAudioFile(_ url: URL) -> Bool {
        let managedDirectories = [Bucket.recordings, .imports, .cache].compactMap { try? directory(for: $0).standardizedFileURL.path }
        let targetPath = url.standardizedFileURL.path
        return managedDirectories.contains { directoryPath in
            targetPath.hasPrefix(directoryPath + "/") || targetPath.hasPrefix(directoryPath + "\\")
        }
    }
}

private extension String {
    var sanitizedImportName: String {
        let invalid = CharacterSet(charactersIn: "\\/:*?\"<>|")
        let clean = components(separatedBy: invalid).joined(separator: "_")
        return clean.isEmpty ? "import_\(Int(Date().timeIntervalSince1970))" : clean
    }
}
