import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct DriveFile: Equatable {
    public var id: String
    public var name: String
    public var createdTime: Date?
    public var trashed: Bool

    public init(id: String, name: String, createdTime: Date?, trashed: Bool = false) {
        self.id = id
        self.name = name
        self.createdTime = createdTime
        self.trashed = trashed
    }
}

public enum DriveError: Error, Equatable, LocalizedError {
    /// HTTP error from the Drive API.
    case http(status: Int, message: String)
    /// 404 - the file (or its folder) was deleted by the user.
    case notFound(String)
    /// The user has not granted (or revoked) drive.file.
    case consentRequired
    case network(String)
    case parse(String)

    /// Network errors, 429, 5xx and 403 rate limits are worth retrying.
    public var retryable: Bool {
        switch self {
        case .network: return true
        case let .http(status, message):
            return status == 429 || status >= 500
                || (status == 403 && message.range(of: "rateLimitExceeded", options: .caseInsensitive) != nil)
        default: return false
        }
    }

    public var errorDescription: String? {
        switch self {
        case let .http(status, message): return "HTTP \(status): \(message)"
        case let .notFound(message): return message
        case .consentRequired: return "Google Drive permission is required"
        case let .network(message): return message
        case let .parse(message): return message
        }
    }
}

/// The few Drive operations SOSlive needs. Files are found by their appProperties tag
/// ({"soslive": tag}); with the drive.file scope the app only sees files it created.
public protocol DriveAPI: AnyObject {
    /// Non-trashed files with the tag, optionally inside `parentId`, ordered by creation time.
    func find(tag: DriveTag, parentId: String?, newestFirst: Bool, folderOnly: Bool) async throws -> [DriveFile]
    /// Metadata, or nil when the file does not exist any more.
    func file(id: String) async throws -> DriveFile?
    func createFolder(name: String, tag: DriveTag) async throws -> DriveFile
    func createFile(name: String, mimeType: String, parentId: String, tag: DriveTag, content: Data) async throws -> DriveFile
    /// Replaces the whole content of the file.
    func updateContent(id: String, mimeType: String, content: Data) async throws
    func download(id: String) async throws -> Data
    /// "anyone with the link" reader permission.
    func shareAnyoneReader(id: String) async throws
    func trash(id: String) async throws
    /// URL that shows a publicly shared image in an <img> tag.
    func publicImageURL(id: String) -> String
}

public extension DriveAPI {
    func publicImageURL(id: String) -> String { "https://drive.google.com/thumbnail?id=\(id)&sz=w1600" }
}

/// Supplies OAuth access tokens with the drive.file scope.
public protocol AccessTokenProvider: AnyObject {
    /// `forceRefresh` is true after a 401 - the cached token is stale.
    func accessToken(forceRefresh: Bool) async throws -> String
}

/// Drive REST API v3 over URLSession, authenticated with the user's own Google token.
public final class GoogleDriveAPI: DriveAPI {
    private let session: URLSession
    private let tokens: AccessTokenProvider
    private let api: URL
    private let upload: URL

    public init(tokens: AccessTokenProvider, session: URLSession = .shared,
                baseURL: URL = URL(string: "https://www.googleapis.com/")!) {
        self.tokens = tokens
        self.session = session
        api = baseURL.appendingPathComponent("drive/v3")
        upload = baseURL.appendingPathComponent("upload/drive/v3")
    }

    private static let fileFields = "id,name,createdTime,trashed"

    public func find(tag: DriveTag, parentId: String?, newestFirst: Bool, folderOnly: Bool) async throws -> [DriveFile] {
        var q = "appProperties has { key='soslive' and value='\(tag.rawValue)' } and trashed=false"
        if folderOnly { q += " and mimeType='\(DriveNames.folderMime)'" }
        if let parentId { q += " and '\(parentId)' in parents" }
        var result: [DriveFile] = []
        var pageToken: String?
        repeat {
            var query = [
                URLQueryItem(name: "q", value: q),
                URLQueryItem(name: "orderBy", value: newestFirst ? "createdTime desc" : "createdTime"),
                URLQueryItem(name: "pageSize", value: "1000"),
                URLQueryItem(name: "spaces", value: "drive"),
                URLQueryItem(name: "fields", value: "nextPageToken,files(\(Self.fileFields))"),
            ]
            if let pageToken { query.append(URLQueryItem(name: "pageToken", value: pageToken)) }
            let body = try JSON.object(from: try await send("GET", url(api, "files", query: query)))
            result += (body["files"] as? [JSONObject] ?? []).compactMap(Self.driveFile)
            pageToken = body["nextPageToken"] as? String
        } while pageToken != nil
        return result
    }

    public func file(id: String) async throws -> DriveFile? {
        do {
            let data = try await send("GET", url(api, "files/\(id)", query: [URLQueryItem(name: "fields", value: Self.fileFields)]))
            return Self.driveFile(try JSON.object(from: data))
        } catch DriveError.notFound {
            return nil
        }
    }

    public func createFolder(name: String, tag: DriveTag) async throws -> DriveFile {
        let metadata: JSONObject = ["name": name, "mimeType": DriveNames.folderMime, "appProperties": ["soslive": tag.rawValue]]
        let data = try await send("POST", url(api, "files", query: [URLQueryItem(name: "fields", value: Self.fileFields)]),
                                  body: JSON.data(metadata), contentType: "application/json; charset=UTF-8")
        guard let file = Self.driveFile(try JSON.object(from: data)) else { throw DriveError.parse("No file id") }
        return file
    }

    public func createFile(name: String, mimeType: String, parentId: String, tag: DriveTag, content: Data) async throws -> DriveFile {
        let metadata: JSONObject = ["name": name, "mimeType": mimeType, "parents": [parentId], "appProperties": ["soslive": tag.rawValue]]
        let boundary = "soslive-\(UUID().uuidString)"
        var body = Data()
        body.append(Data("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".utf8))
        body.append(JSON.data(metadata))
        body.append(Data("\r\n--\(boundary)\r\nContent-Type: \(mimeType)\r\n\r\n".utf8))
        body.append(content)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        let query = [URLQueryItem(name: "uploadType", value: "multipart"), URLQueryItem(name: "fields", value: Self.fileFields)]
        let data = try await send("POST", url(upload, "files", query: query), body: body,
                                  contentType: "multipart/related; boundary=\(boundary)")
        guard let file = Self.driveFile(try JSON.object(from: data)) else { throw DriveError.parse("No file id") }
        return file
    }

    public func updateContent(id: String, mimeType: String, content: Data) async throws {
        let query = [URLQueryItem(name: "uploadType", value: "media"), URLQueryItem(name: "fields", value: "id")]
        _ = try await send("PATCH", url(upload, "files/\(id)", query: query), body: content, contentType: mimeType)
    }

    public func download(id: String) async throws -> Data {
        try await send("GET", url(api, "files/\(id)", query: [URLQueryItem(name: "alt", value: "media")]))
    }

    public func shareAnyoneReader(id: String) async throws {
        _ = try await send("POST", url(api, "files/\(id)/permissions", query: [URLQueryItem(name: "fields", value: "id")]),
                           body: JSON.data(["type": "anyone", "role": "reader"]), contentType: "application/json; charset=UTF-8")
    }

    public func trash(id: String) async throws {
        _ = try await send("PATCH", url(api, "files/\(id)", query: [URLQueryItem(name: "fields", value: "id")]),
                           body: JSON.data(["trashed": true]), contentType: "application/json; charset=UTF-8")
    }

    // MARK: - Plumbing

    private func url(_ base: URL, _ path: String, query: [URLQueryItem]) -> URL {
        var components = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        components.queryItems = query
        // "+" is not encoded by URLComponents but means a space in query strings.
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return components.url!
    }

    /// Sends with a bearer token; on 401 refreshes the token once and retries.
    private func send(_ method: String, _ url: URL, body: Data? = nil, contentType: String? = nil) async throws -> Data {
        var (data, status) = try await perform(method, url, body, contentType, token: try await tokens.accessToken(forceRefresh: false))
        if status == 401 {
            (data, status) = try await perform(method, url, body, contentType, token: try await tokens.accessToken(forceRefresh: true))
        }
        switch status {
        case 200..<300: return data
        case 404: throw DriveError.notFound(Self.errorMessage(data) ?? "Not found")
        default: throw DriveError.http(status: status, message: Self.errorMessage(data) ?? HTTPURLResponse.localizedString(forStatusCode: status))
        }
    }

    private func perform(_ method: String, _ url: URL, _ body: Data?, _ contentType: String?, token: String) async throws -> (Data, Int) {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = 60
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let contentType { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        do {
            let (data, response) = try await session.data(for: request)
            return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
        } catch {
            throw DriveError.network(error.localizedDescription)
        }
    }

    private static func errorMessage(_ data: Data) -> String? {
        guard let object = try? JSON.object(from: data), let error = object["error"] as? JSONObject else { return nil }
        let reason = (error["errors"] as? [JSONObject])?.first?["reason"] as? String
        let parts = [error["message"] as? String, reason].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " / ")
    }

    static func driveFile(_ object: JSONObject) -> DriveFile? {
        guard let id = object["id"] as? String else { return nil }
        return DriveFile(
            id: id,
            name: object["name"] as? String ?? "",
            createdTime: (object["createdTime"] as? String).flatMap(parseISO),
            trashed: object["trashed"] as? Bool ?? false
        )
    }
}
