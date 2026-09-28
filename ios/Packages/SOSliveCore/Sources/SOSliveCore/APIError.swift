import Foundation

/// Backend error ({"error":{"code","message"}}) or a local network / parse problem.
public struct APIError: Error, Equatable, LocalizedError {
    public var httpStatus: Int?
    public var code: String
    public var message: String

    public init(httpStatus: Int?, code: String, message: String) {
        self.httpStatus = httpStatus
        self.code = code
        self.message = message
    }

    public var errorDescription: String? { message }

    public static let networkCode = "network_error"
    public static let parseCode = "parse_error"

    public static func network(_ message: String) -> APIError {
        APIError(httpStatus: nil, code: networkCode, message: message)
    }
}
