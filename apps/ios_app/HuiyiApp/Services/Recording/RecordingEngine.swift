import AVFoundation
import Foundation

enum RecordingEngineState: Equatable, Sendable {
    case idle
    case preparingASR
    case readyToRecord
    case recording
    case paused
    case stopping
    case finished(URL)
    case failedBeforeStart(String)
}

struct RecordingAudioLevel: Equatable, Sendable {
    var peak: Float
    var rms: Float

    var levelPercent: Int {
        Int(min(100, max(0, rms * 100)))
    }
}

final class RecordingEngine {
    typealias PCMHandler = @MainActor (Data, Int64) async -> Void
    typealias LevelHandler = @MainActor (RecordingAudioLevel) -> Void
    typealias StateHandler = @MainActor (RecordingEngineState) -> Void

    private let audioSessionManager: AudioSessionManager
    private let engine = AVAudioEngine()
    private var writer: WavFileWriter?
    private var converter: AVAudioConverter?
    private var outputFormat: AVAudioFormat?
    private var pcmBytes: Int64 = 0
    private var pcmHandler: PCMHandler?
    private var levelHandler: LevelHandler?
    private var stateHandler: StateHandler?

    init(audioSessionManager: AudioSessionManager = AudioSessionManager()) {
        self.audioSessionManager = audioSessionManager
    }

    func start(
        outputURL: URL,
        onPCM: @escaping PCMHandler,
        onLevel: @escaping LevelHandler,
        onState: @escaping StateHandler
    ) async {
        pcmHandler = onPCM
        levelHandler = onLevel
        stateHandler = onState
        await emit(.readyToRecord)
        do {
            try await audioSessionManager.configureForRecording()
            pcmBytes = 0
            writer = try WavFileWriter(url: outputURL)
            let inputNode = engine.inputNode
            let inputFormat = inputNode.outputFormat(forBus: 0)
            guard let targetFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: true) else {
                throw APIError.invalidResponse
            }
            outputFormat = targetFormat
            converter = AVAudioConverter(from: inputFormat, to: targetFormat)
            inputNode.removeTap(onBus: 0)
            inputNode.installTap(onBus: 0, bufferSize: 1_920, format: inputFormat) { [weak self] buffer, _ in
                self?.consume(buffer)
            }
            try engine.start()
            await emit(.recording)
        } catch {
            await emit(.failedBeforeStart(error.localizedDescription))
        }
    }

    func stop() async -> URL? {
        await emit(.stopping)
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        audioSessionManager.deactivate()
        let url = writer?.url
        try? writer?.finish()
        writer = nil
        if let url {
            await emit(.finished(url))
        }
        return url
    }

    func pause() async {
        guard engine.isRunning else { return }
        engine.pause()
        await emit(.paused)
    }

    func resume() async {
        guard !engine.isRunning else { return }
        do {
            try engine.start()
            await emit(.recording)
        } catch {
            await emit(.failedBeforeStart(error.localizedDescription))
        }
    }

    func cancel() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        audioSessionManager.deactivate()
        writer = nil
    }

    private func consume(_ inputBuffer: AVAudioPCMBuffer) {
        guard let converter, let outputFormat else { return }
        let ratio = outputFormat.sampleRate / inputBuffer.format.sampleRate
        let frameCapacity = AVAudioFrameCount(Double(inputBuffer.frameLength) * ratio) + 16
        guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: frameCapacity) else { return }
        var consumed = false
        let status = converter.convert(to: outputBuffer, error: nil) { _, outStatus in
            if consumed {
                outStatus.pointee = .noDataNow
                return nil
            }
            consumed = true
            outStatus.pointee = .haveData
            return inputBuffer
        }
        guard status != .error, outputBuffer.frameLength > 0 else { return }
        guard let data = outputBuffer.interleavedPCM16Data else { return }
        try? writer?.appendPCM16(data)
        pcmBytes += Int64(data.count)
        let endBytes = pcmBytes
        Task { [pcmHandler] in
            await pcmHandler?(data, endBytes)
        }
        let level = outputBuffer.audioLevel
        Task { @MainActor in
            levelHandler?(level)
        }
    }

    @MainActor
    private func emit(_ state: RecordingEngineState) {
        stateHandler?(state)
    }
}

private extension AVAudioPCMBuffer {
    var interleavedPCM16Data: Data? {
        guard let channelData = int16ChannelData else { return nil }
        let frameCount = Int(frameLength)
        let channels = Int(format.channelCount)
        var data = Data(capacity: frameCount * channels * 2)
        for frame in 0..<frameCount {
            for channel in 0..<channels {
                var sample = channelData[channel][frame].littleEndian
                withUnsafeBytes(of: &sample) { data.append(contentsOf: $0) }
            }
        }
        return data
    }

    var audioLevel: RecordingAudioLevel {
        guard let channelData = int16ChannelData, frameLength > 0 else {
            return RecordingAudioLevel(peak: 0, rms: 0)
        }
        var peak: Float = 0
        var sumSquares: Float = 0
        let samples = Int(frameLength)
        for index in 0..<samples {
            let value = Float(abs(Int(channelData[0][index]))) / Float(Int16.max)
            peak = max(peak, value)
            sumSquares += value * value
        }
        return RecordingAudioLevel(peak: peak, rms: sqrt(sumSquares / Float(samples)))
    }
}
