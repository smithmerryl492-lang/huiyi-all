import Foundation

enum APIError: Error, Equatable, Sendable {
    case authRequired
    case invalidURL
    case invalidResponse
    case emptyResponse
    case httpStatus(Int, String)
    case decoding(String)
    case encoding(String)
    case fileMissing(String)
}

extension APIError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .authRequired:
            return "请先登录后再继续"
        case .invalidURL:
            return "请求地址无效"
        case .invalidResponse:
            return "服务返回异常，请稍后重试"
        case .emptyResponse:
            return "服务未返回数据，请稍后重试"
        case let .httpStatus(status, message):
            return message.isEmpty ? "请求失败（\(status)）" : message
        case let .decoding(message):
            return message.isEmpty ? "数据解析失败" : message
        case let .encoding(message):
            return message.isEmpty ? "请求数据无效" : message
        case let .fileMissing(path):
            return "文件不存在：\(path)"
        }
    }
}
