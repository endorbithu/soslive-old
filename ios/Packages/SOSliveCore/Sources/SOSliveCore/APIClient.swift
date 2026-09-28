import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Response type for endpoints without a body (204).
public struct EmptyResponse: Decodable {
    public init() {}
    public init(from decoder: Decoder) throws {}
}

/// Serialises token refreshes: concurrent 401s wait for one refresh request.
private actor RefreshCoordinator {
    private var inFlight: Task<Bool, Never>?

    func run(_ operation: @escaping @Sendable () async -> Bool) async -> Bool {
        if let inFlight { return await inFlight.value }
        let task = Task { await operation() }
        inFlight = task
        let result = await task.value
        inFlight = nil
        return result
    }
}

/// JSON HTTP client for the SOSlive API. Adds the bearer token, and on 401 exchanges the
/// refresh token once and retries. A rejected refresh token clears the session
/// (observers get `onSessionChange(nil)` and the UI shows the login screen).
public final class APIClient {
    public let baseURL: URL
    private let store: SessionStore
    private let urlSession: URLSession
    private let refresher = RefreshCoordinator()

    /// Called (on an arbitrary thread) whenever the stored session changes.
    public var onSessionChange: ((Session?) -> Void)?

    public init(baseURL: URL, store: SessionStore, urlSession: URLSession = .shared) {
        self.baseURL = baseURL
        self.store = store
        self.urlSession = urlSession
    }

    public var currentSession: Session? { store.load() }

    public func setSession(_ session: Session?) {
        store.save(session)
        onSessionChange?(session)
    }

    // MARK: - Requests

    public func get<T: Decodable>(_ path: String, query: [URLQueryItem] = [], authenticated: Bool = true) async throws -> T {
        try await send(makeRequest("GET", path, query: query), authenticated: authenticated)
    }

    public func post<T: Decodable, B: Encodable>(_ path: String, body: B, authenticated: Bool = true) async throws -> T {
        var request = makeRequest("POST", path)
        request.httpBody = try Self.encoder.encode(body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return try await send(request, authenticated: authenticated)
    }

    public func post<T: Decodable>(_ path: String) async throws -> T {
        try await send(makeRequest("POST", path), authenticated: true)
    }

    public func patch<T: Decodable, B: Encodable>(_ path: String, body: B) async throws -> T {
        var request = makeRequest("PATCH", path)
        request.httpBody = try Self.encoder.encode(body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return try await send(request, authenticated: true)
    }

    public func upload<T: Decodable>(_ path: String, field: String, fileName: String, mimeType: String, data: Data) async throws -> T {
        let boundary = "SOSlive-\(UUID().uuidString)"
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"\(field)\"; filename=\"\(fileName)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)

        var request = makeRequest("POST", path)
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        request.timeoutInterval = 120
        return try await send(request, authenticated: true)
    }

    // MARK: - Internals

    private func makeRequest(_ method: String, _ path: String, query: [URLQueryItem] = []) -> URLRequest {
        var components = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 30
        return request
    }

    private func send<T: Decodable>(_ request: URLRequest, authenticated: Bool) async throws -> T {
        var request = request
        let usedToken = authenticated ? store.load()?.accessToken : nil
        if let usedToken { request.setValue("Bearer \(usedToken)", forHTTPHeaderField: "Authorization") }

        var (data, response) = try await perform(request)

        if response.statusCode == 401, let usedToken {
            let refreshed: Bool
            if let current = store.load()?.accessToken, current != usedToken {
                refreshed = true // another request already refreshed
            } else {
                refreshed = await refresher.run { [weak self] in await self?.refreshSession() ?? false }
            }
            if refreshed, let token = store.load()?.accessToken {
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                (data, response) = try await perform(request)
            }
        }

        guard (200..<300).contains(response.statusCode) else {
            throw Self.apiError(status: response.statusCode, data: data)
        }
        if T.self == EmptyResponse.self || data.isEmpty, let empty = EmptyResponse() as? T {
            return empty
        }
        do {
            return try Self.decoder.decode(T.self, from: data)
        } catch {
            throw APIError(httpStatus: response.statusCode, code: APIError.parseCode, message: "Unexpected response: \(error)")
        }
    }

    private func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            let (data, response) = try await urlSession.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw APIError.network("Invalid response") }
            return (data, http)
        } catch let error as APIError {
            throw error
        } catch {
            throw APIError.network(error.localizedDescription)
        }
    }

    /// POST /auth/refresh. Returns false when the session could not be refreshed.
    private func refreshSession() async -> Bool {
        guard let refreshToken = store.load()?.refreshToken else { return false }
        var request = makeRequest("POST", "auth/refresh")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? Self.encoder.encode(["refreshToken": refreshToken])
        guard let (data, response) = try? await perform(request) else { return false }
        if response.statusCode == 401 {
            setSession(nil)
            return false
        }
        guard (200..<300).contains(response.statusCode),
              let session = try? Self.decoder.decode(Session.self, from: data) else { return false }
        setSession(session)
        return true
    }

    static func apiError(status: Int, data: Data) -> APIError {
        if let envelope = try? decoder.decode(ErrorEnvelope.self, from: data) {
            return APIError(httpStatus: status, code: envelope.error.code, message: envelope.error.message)
        }
        return APIError(httpStatus: status, code: "http_\(status)", message: HTTPURLResponse.localizedString(forStatusCode: status))
    }

    // MARK: - JSON

    public static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let value = try decoder.singleValueContainer().decode(String.self)
            if let date = parseISODate(value) { return date }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid date \(value)"))
        }
        return decoder
    }()

    public static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }()

    /// The backend sends ISO-8601 with milliseconds ("2026-09-28T14:01:04.752Z").
    public static func parseISODate(_ value: String) -> Date? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = withFraction.date(from: value) { return date }
        return ISO8601DateFormatter().date(from: value)
    }
}
