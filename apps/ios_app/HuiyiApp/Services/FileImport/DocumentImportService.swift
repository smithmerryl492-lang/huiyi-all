import Foundation
import UniformTypeIdentifiers
import UIKit

struct ImportedLocalFile: Equatable, Sendable {
    let displayName: String
    let localFileURL: URL
    let sizeBytes: Int64

    var sizeLabel: String {
        if sizeBytes >= 1_048_576 {
            return String(format: "%.1f MB", Double(sizeBytes) / 1_048_576.0)
        }
        if sizeBytes >= 1_024 {
            return String(format: "%.1f KB", Double(sizeBytes) / 1_024.0)
        }
        return "\(sizeBytes) B"
    }
}

final class DocumentImportService {
    static let supportedExtensions: Set<String> = ["mp3", "m4a", "wav", "aac", "mp4", "mov"]
    static let maxImportBytes: Int64 = 500 * 1024 * 1024

    let supportedContentTypes: [UTType] = [.audio, .movie, .mpeg4Audio, .wav, .mpeg4Movie, .quickTimeMovie]

    private let audioFileStore: AudioFileStore

    init(audioFileStore: AudioFileStore = AudioFileStore()) {
        self.audioFileStore = audioFileStore
    }

    func copyIntoSandbox(sourceURL: URL) throws -> ImportedLocalFile {
        let didAccess = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if didAccess {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }
        let displayName = sourceURL.lastPathComponent
        try validate(displayName: displayName)
        let target = try audioFileStore.uniqueImportURL(displayName: displayName)
        if FileManager.default.fileExists(atPath: target.path) {
            try FileManager.default.removeItem(at: target)
        }
        try FileManager.default.copyItem(at: sourceURL, to: target)
        let size = try fileSize(target)
        try validate(sizeBytes: size)
        return ImportedLocalFile(displayName: displayName, localFileURL: target, sizeBytes: size)
    }

    private func validate(displayName: String) throws {
        let ext = (displayName as NSString).pathExtension.lowercased()
        if !Self.supportedExtensions.contains(ext) {
            throw APIError.encoding("不支持的文件格式，请选择 mp3、m4a、wav、aac、mp4 或 mov")
        }
    }

    private func validate(sizeBytes: Int64) throws {
        if sizeBytes <= 0 {
            throw APIError.encoding("文件为空，无法处理")
        }
        if sizeBytes > Self.maxImportBytes {
            throw APIError.encoding("文件超过 500MB，请拆分后再导入")
        }
    }

    private func fileSize(_ url: URL) throws -> Int64 {
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        return (attributes[.size] as? NSNumber)?.int64Value ?? 0
    }
}
