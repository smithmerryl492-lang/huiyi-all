import Foundation

struct RealtimeTranscriptBuffer: Equatable, Sendable {
    private(set) var finalSegments: [TranscriptSegment] = []
    private(set) var partialSegment: TranscriptSegment?

    var displaySegments: [TranscriptSegment] {
        if let partialSegment {
            return finalSegments + [partialSegment]
        }
        return finalSegments
    }

    mutating func apply(_ event: RealtimeTranscriptEvent) {
        if event.replaceAll {
            finalSegments.removeAll()
            partialSegment = nil
        }
        let incomingSegments = event.segments.compactMap { $0.normalizedLiveSegment }
        if event.isFinal {
            partialSegment = nil
            if !event.replaceAll {
                finalSegments.removeAll { existing in
                    incomingSegments.contains { incoming in
                        existing.isCoveredByFinalLiveSegment(incoming)
                    }
                }
            }
            for incoming in incomingSegments {
                if let duplicateIndex = finalSegments.firstIndex(where: { $0.sameLiveContent(as: incoming) }) {
                    finalSegments[duplicateIndex] = finalSegments[duplicateIndex].mergingLiveUpdate(incoming)
                    continue
                }
                if let updateIndex = finalSegments.lastIndex(where: { $0.canBeUpdatedByLiveSegment(incoming) }) {
                    finalSegments[updateIndex] = finalSegments[updateIndex].mergingLiveUpdate(incoming)
                } else {
                    finalSegments.append(incoming)
                }
            }
            finalSegments.sort {
                let leftStart = $0.startMs ?? Int64.max
                let rightStart = $1.startMs ?? Int64.max
                if leftStart != rightStart {
                    return leftStart < rightStart
                }
                return ($0.endMs ?? 0) < ($1.endMs ?? 0)
            }
        } else {
            partialSegment = incomingSegments.last
        }
    }

    mutating func reset() {
        finalSegments = []
        partialSegment = nil
    }
}

private extension TranscriptSegment {
    var normalizedLiveSegment: TranscriptSegment? {
        let cleanText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanText.isEmpty else { return nil }
        let cleanSpeaker = speaker.trimmingCharacters(in: .whitespacesAndNewlines)
        let displaySpeaker = cleanSpeaker.isEmpty || cleanSpeaker == "未分离" ? "发言" : cleanSpeaker
        let cleanSpeakerId = speakerId?.trimmingCharacters(in: .whitespacesAndNewlines)
        var copy = self
        copy.speaker = displaySpeaker
        copy.text = cleanText
        if let cleanSpeakerId, !cleanSpeakerId.isEmpty {
            copy.speakerId = cleanSpeakerId
        } else {
            copy.speakerId = speakerIdentityId(for: displaySpeaker)
        }
        return copy
    }

    func sameLiveContent(as other: TranscriptSegment) -> Bool {
        speaker == other.speaker &&
            text == other.text &&
            startMs == other.startMs &&
            endMs == other.endMs
    }

    func canBeUpdatedByLiveSegment(_ incoming: TranscriptSegment) -> Bool {
        if speaker != incoming.speaker, speaker != "未分离", incoming.speaker != "未分离" {
            return false
        }
        guard let startMs, let incomingStart = incoming.startMs else { return false }
        guard abs(startMs - incomingStart) <= 300 else { return false }
        return text.isLiveRevisionPair(with: incoming.text)
    }

    func mergingLiveUpdate(_ incoming: TranscriptSegment) -> TranscriptSegment {
        let mergedText = incoming.text.count + 3 >= text.count ? incoming.text : text
        let mergedStart = [startMs, incoming.startMs].compactMap { $0 }.min()
        let mergedEnd = [endMs, incoming.endMs].compactMap { $0 }.max()
        var copy = self
        copy.text = mergedText
        copy.timestamp = mergedStart.map(Self.liveClock) ?? (incoming.timestamp.isEmpty ? timestamp : incoming.timestamp)
        copy.startMs = mergedStart
        copy.endMs = mergedEnd
        return copy
    }

    func isCoveredByFinalLiveSegment(_ finalSegment: TranscriptSegment) -> Bool {
        if sameLiveContent(as: finalSegment) { return false }
        if text == finalSegment.text, startMs == finalSegment.startMs, endMs == finalSegment.endMs {
            return true
        }
        if speaker != "未分离", speaker != finalSegment.speaker {
            return false
        }
        let coveredByText = !text.isEmpty && finalSegment.text.contains(text)
        let coveredByTime: Bool
        if let startMs, let finalStart = finalSegment.startMs, let finalEnd = finalSegment.endMs {
            coveredByTime = startMs >= finalStart - 500 && startMs <= finalEnd + 500
        } else {
            coveredByTime = false
        }
        return coveredByText || coveredByTime
    }

    private static func liveClock(_ millis: Int64) -> String {
        let totalSeconds = max(0, millis / 1000)
        return String(format: "%02lld:%02lld", totalSeconds / 60, totalSeconds % 60)
    }
}

private extension String {
    func isLiveRevisionPair(with other: String) -> Bool {
        let left = trimmingCharacters(in: .whitespacesAndNewlines)
        let right = other.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !left.isEmpty, !right.isEmpty else { return false }
        if left == right || right.contains(left) || left.contains(right) { return true }
        let shorter = min(left.count, right.count)
        let commonPrefix = zip(left, right).prefix { pair in pair.0 == pair.1 }.count
        return commonPrefix >= min(8, shorter)
    }
}
