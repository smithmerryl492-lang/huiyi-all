import Foundation

final class WavFileWriter {
    let url: URL
    private let fileHandle: FileHandle
    private(set) var pcmBytesWritten: Int64 = 0
    private let sampleRate: Int
    private let channels: Int

    init(url: URL, sampleRate: Int = 16_000, channels: Int = 1) throws {
        self.url = url
        self.sampleRate = sampleRate
        self.channels = channels
        FileManager.default.createFile(atPath: url.path, contents: nil)
        fileHandle = try FileHandle(forWritingTo: url)
        try fileHandle.write(contentsOf: Data(repeating: 0, count: 44))
    }

    func appendPCM16(_ data: Data) throws {
        try fileHandle.seekToEnd()
        try fileHandle.write(contentsOf: data)
        pcmBytesWritten += Int64(data.count)
        if pcmBytesWritten % (16_000 * 2 * 5) == 0 {
            try updateHeader()
        }
    }

    func finish() throws {
        try updateHeader()
        try fileHandle.close()
    }

    private func updateHeader() throws {
        var header = Data()
        header.appendASCII("RIFF")
        header.appendUInt32LE(UInt32(clamping: 36 + pcmBytesWritten))
        header.appendASCII("WAVE")
        header.appendASCII("fmt ")
        header.appendUInt32LE(16)
        header.appendUInt16LE(1)
        header.appendUInt16LE(UInt16(channels))
        header.appendUInt32LE(UInt32(sampleRate))
        header.appendUInt32LE(UInt32(sampleRate * channels * 2))
        header.appendUInt16LE(UInt16(channels * 2))
        header.appendUInt16LE(16)
        header.appendASCII("data")
        header.appendUInt32LE(UInt32(clamping: pcmBytesWritten))
        try fileHandle.seek(toOffset: 0)
        try fileHandle.write(contentsOf: header)
    }
}

private extension Data {
    mutating func appendASCII(_ value: String) {
        append(Data(value.utf8))
    }

    mutating func appendUInt16LE(_ value: UInt16) {
        append(UInt8(value & 0xff))
        append(UInt8((value >> 8) & 0xff))
    }

    mutating func appendUInt32LE(_ value: UInt32) {
        append(UInt8(value & 0xff))
        append(UInt8((value >> 8) & 0xff))
        append(UInt8((value >> 16) & 0xff))
        append(UInt8((value >> 24) & 0xff))
    }
}
