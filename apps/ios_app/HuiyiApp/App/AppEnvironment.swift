import Foundation

struct AppEnvironment: Sendable {
    let apiBaseURL: URL
    let liveWebSocketURL: URL?
    let appDisplayName: String
    let buildChannel: BuildChannel

    static let staging = AppEnvironment(
        apiBaseURL: URL(string: "http://43.154.197.96:28080/api/v1")!,
        liveWebSocketURL: URL(string: "ws://43.154.197.96:28080/api/v1/live/ws"),
        appDisplayName: "鲲穹会纪",
        buildChannel: .staging
    )

    static let local = AppEnvironment(
        apiBaseURL: URL(string: "http://127.0.0.1:8080/api/v1")!,
        liveWebSocketURL: URL(string: "ws://127.0.0.1:8080/api/v1/live/ws"),
        appDisplayName: "鲲穹会纪",
        buildChannel: .local
    )
}

enum BuildChannel: String, Codable, Sendable {
    case local
    case staging
    case production
}
