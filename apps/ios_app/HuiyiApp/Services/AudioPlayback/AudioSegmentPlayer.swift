import AVFoundation
import Foundation

final class AudioSegmentPlayer: NSObject, AVAudioPlayerDelegate {
    private var player: AVAudioPlayer?
    private var stopTask: Task<Void, Never>?
    private var onFinish: (() -> Void)?

    func play(url: URL, startMs: Int64?, endMs: Int64?, onFinish: (() -> Void)? = nil) throws {
        stop()
        self.onFinish = onFinish
        let player = try AVAudioPlayer(contentsOf: url)
        self.player = player
        player.delegate = self
        if let startMs {
            player.currentTime = TimeInterval(startMs) / 1000.0
        }
        player.prepareToPlay()
        player.play()
        if let endMs {
            let delay = max(0, TimeInterval(endMs - (startMs ?? 0)) / 1000.0)
            stopTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                guard let player = self, !Task.isCancelled else { return }
                await MainActor.run {
                    player.finishPlayback()
                }
            }
        }
    }

    func stop() {
        stopTask?.cancel()
        stopTask = nil
        player?.stop()
        player = nil
        onFinish = nil
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        finishPlayback()
    }

    private func finishPlayback() {
        stopTask?.cancel()
        stopTask = nil
        player?.stop()
        player = nil
        let callback = onFinish
        onFinish = nil
        DispatchQueue.main.async {
            callback?()
        }
    }
}
