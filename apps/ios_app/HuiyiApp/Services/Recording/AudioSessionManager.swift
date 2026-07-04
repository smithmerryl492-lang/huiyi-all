import AVFoundation
import Foundation

final class AudioSessionManager {
    private let session = AVAudioSession.sharedInstance()

    func configureForRecording() async throws {
        let granted = await requestRecordPermission()
        guard granted else {
            throw APIError.encoding("未获得麦克风权限，无法开始录音")
        }
        try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setPreferredSampleRate(16_000)
        try session.setPreferredIOBufferDuration(0.06)
        try session.setActive(true, options: [])
    }

    func deactivate() {
        try? session.setActive(false, options: [.notifyOthersOnDeactivation])
    }

    private func requestRecordPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            session.requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }
}
