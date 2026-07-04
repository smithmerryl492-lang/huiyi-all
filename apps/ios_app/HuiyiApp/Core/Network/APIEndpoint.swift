import Foundation

enum HTTPMethod: String, Sendable {
    case get = "GET"
    case post = "POST"
    case put = "PUT"
    case patch = "PATCH"
    case delete = "DELETE"
}

struct APIEndpoint: Sendable {
    let method: HTTPMethod
    let path: String
    var queryItems: [URLQueryItem]

    init(_ method: HTTPMethod, _ path: String, queryItems: [URLQueryItem] = []) {
        self.method = method
        self.path = path
        self.queryItems = queryItems
    }

    static let sendSmsCode = APIEndpoint(.post, "/auth/sms/send-code")
    static let smsLogin = APIEndpoint(.post, "/auth/sms/login")
    static let passwordRegister = APIEndpoint(.post, "/auth/password/register")
    static let passwordLogin = APIEndpoint(.post, "/auth/password/login")
    static let passwordReset = APIEndpoint(.post, "/auth/password/reset")
    static let passwordSet = APIEndpoint(.post, "/auth/password/set")
    static let passwordChange = APIEndpoint(.post, "/auth/password/change")
    static let verifyPhoneChange = APIEndpoint(.post, "/auth/phone/change/verify-current")
    static let changePhone = APIEndpoint(.post, "/auth/phone/change")
    static let membershipMe = APIEndpoint(.get, "/membership/me")
    static let liveSession = APIEndpoint(.post, "/live/session")
    static let syncBootstrap = APIEndpoint(.get, "/sync/bootstrap")
    static let clearCloudData = APIEndpoint(.delete, "/sync/all")
    static let knowledgeAsk = APIEndpoint(.post, "/knowledge/ask")
    static let paymentOrders = APIEndpoint(.get, "/payments/orders")
    static let speakerProfiles = APIEndpoint(.get, "/voiceprints/profiles")
    static let enrollSpeakerFromTask = APIEndpoint(.post, "/voiceprints/profiles/from-task")
    static let enrollSpeakerFromAudio = APIEndpoint(.post, "/voiceprints/profiles/from-audio")

    static func task(_ id: String) -> APIEndpoint {
        APIEndpoint(.get, "/tasks/\(id.urlPathEscaped)")
    }

    static func updateTask(_ id: String) -> APIEndpoint {
        APIEndpoint(.patch, "/tasks/\(id.urlPathEscaped)")
    }

    static func processTask(_ id: String) -> APIEndpoint {
        APIEndpoint(.post, "/tasks/\(id.urlPathEscaped)/process")
    }

    static func retryTask(_ id: String) -> APIEndpoint {
        APIEndpoint(.post, "/tasks/\(id.urlPathEscaped)/retry")
    }

    static func cancelTask(_ id: String) -> APIEndpoint {
        APIEndpoint(.post, "/tasks/\(id.urlPathEscaped)/cancel")
    }

    static func deleteTask(_ id: String) -> APIEndpoint {
        APIEndpoint(.delete, "/tasks/\(id.urlPathEscaped)")
    }

    static func taskResult(_ id: String) -> APIEndpoint {
        APIEndpoint(.get, "/tasks/\(id.urlPathEscaped)/result")
    }

    static func updateTaskResult(_ id: String) -> APIEndpoint {
        APIEndpoint(.put, "/tasks/\(id.urlPathEscaped)/result")
    }

    static func regenerateMinutes(_ id: String) -> APIEndpoint {
        APIEndpoint(.post, "/tasks/\(id.urlPathEscaped)/regenerate-minutes")
    }

    static let regenerateLocalMinutes = APIEndpoint(.post, "/tasks/regenerate-local-minutes")

    static func exportTask(_ id: String, format: String, includeTranscript: Bool) -> APIEndpoint {
        APIEndpoint(
            .get,
            "/tasks/\(id.urlPathEscaped)/export",
            queryItems: [
                URLQueryItem(name: "format", value: format),
                URLQueryItem(name: "include_transcript", value: includeTranscript ? "true" : "false")
            ]
        )
    }

    static func taskAudio(_ id: String) -> APIEndpoint {
        APIEndpoint(.get, "/tasks/\(id.urlPathEscaped)/audio")
    }

    static func paymentOrder(_ id: String) -> APIEndpoint {
        APIEndpoint(.get, "/payments/orders/\(id.urlPathEscaped)")
    }

    static func syncAlipayOrder(_ id: String) -> APIEndpoint {
        APIEndpoint(.post, "/payments/alipay/orders/\(id.urlPathEscaped)/sync")
    }

    static let confirmAppleTransaction = APIEndpoint(.post, "/payments/apple/transactions/confirm")

    static func upsertSchedule(_ id: String) -> APIEndpoint {
        APIEndpoint(.put, "/sync/schedules/\(id.urlPathEscaped)")
    }

    static func deleteSchedule(_ id: String) -> APIEndpoint {
        APIEndpoint(.delete, "/sync/schedules/\(id.urlPathEscaped)")
    }

    static func updateSpeakerProfile(_ id: String) -> APIEndpoint {
        APIEndpoint(.patch, "/voiceprints/profiles/\(id.urlPathEscaped)")
    }

    static func deleteSpeakerProfile(_ id: String) -> APIEndpoint {
        APIEndpoint(.delete, "/voiceprints/profiles/\(id.urlPathEscaped)")
    }
}

private extension String {
    var urlPathEscaped: String {
        addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? self
    }
}
