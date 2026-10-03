import XCTest
@testable import SOSliveCore

/// Serves queued canned responses and records requests.
final class StubURLProtocol: URLProtocol {
    struct Stub {
        var status: Int
        var body: String
    }

    static var stubs: [Stub] = []
    static var requests: [URLRequest] = []
    static let lock = NSLock()

    static func reset(_ stubs: [Stub]) {
        lock.lock(); defer { lock.unlock() }
        self.stubs = stubs
        requests = []
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.lock()
        var recorded = request
        if recorded.httpBody == nil, let stream = request.httpBodyStream {
            recorded.httpBody = Data(reading: stream)
        }
        Self.requests.append(recorded)
        let stub = Self.stubs.isEmpty ? Stub(status: 500, body: "") : Self.stubs.removeFirst()
        Self.lock.unlock()

        let response = HTTPURLResponse(url: request.url!, statusCode: stub.status, httpVersion: nil,
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(stub.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private extension Data {
    init(reading stream: InputStream) {
        self.init()
        stream.open()
        defer { stream.close() }
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            append(buffer, count: read)
        }
    }
}

final class FakeTokens: AccessTokenProvider {
    var calls: [Bool] = []
    func accessToken(forceRefresh: Bool) async throws -> String {
        calls.append(forceRefresh)
        return forceRefresh ? "fresh" : "cached"
    }
}

final class GoogleDriveAPITests: XCTestCase {
    private var tokens: FakeTokens!
    private var api: GoogleDriveAPI!

    override func setUp() {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        tokens = FakeTokens()
        api = GoogleDriveAPI(tokens: tokens, session: URLSession(configuration: config), baseURL: URL(string: "https://drive.test/")!)
    }

    private func body(_ request: URLRequest) -> String { String(decoding: request.httpBody ?? Data(), as: UTF8.self) }

    func testFindUsesTagQueryOldestFirstAndBearer() async throws {
        StubURLProtocol.reset([.init(status: 200, body: #"{"files":[{"id":"f1","name":"SOSlive","createdTime":"2025-01-01T00:00:00.000Z"}]}"#)])
        let files = try await api.find(tag: .root, parentId: nil, newestFirst: false, folderOnly: true)
        XCTAssertEqual(files.map(\.id), ["f1"])
        XCTAssertNotNil(files[0].createdTime)

        let request = StubURLProtocol.requests[0]
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer cached")
        XCTAssertEqual(request.url?.path, "/drive/v3/files")
        let items = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
        let q = items.first { $0.name == "q" }!.value!
        XCTAssertTrue(q.contains("appProperties has { key='soslive' and value='root' }"), q)
        XCTAssertTrue(q.contains("trashed=false"))
        XCTAssertTrue(q.contains("mimeType='application/vnd.google-apps.folder'"))
        XCTAssertEqual(items.first { $0.name == "orderBy" }?.value, "createdTime")
    }

    func testUnauthorizedRefreshesTokenOnce() async throws {
        StubURLProtocol.reset([
            .init(status: 401, body: #"{"error":{"message":"Invalid Credentials"}}"#),
            .init(status: 200, body: #"{"id":"x","name":"n"}"#),
        ])
        let file = try await api.file(id: "x")
        XCTAssertEqual(file?.id, "x")
        XCTAssertEqual(tokens.calls, [false, true])
        XCTAssertEqual(StubURLProtocol.requests[1].value(forHTTPHeaderField: "Authorization"), "Bearer fresh")
    }

    func testNotFound() async throws {
        StubURLProtocol.reset([
            .init(status: 404, body: #"{"error":{"message":"File not found"}}"#),
            .init(status: 404, body: ""),
        ])
        do {
            _ = try await api.download(id: "gone")
            XCTFail("expected notFound")
        } catch let DriveError.notFound(message) {
            XCTAssertTrue(message.contains("File not found"))
        }
        let missing = try await api.file(id: "gone")
        XCTAssertNil(missing)
    }

    func testCreateFileIsMultipartRelated() async throws {
        StubURLProtocol.reset([.init(status: 200, body: #"{"id":"e1","name":"2026-09-29 14:03:22.json"}"#)])
        _ = try await api.createFile(name: "2026-09-29 14:03:22.json", mimeType: DriveNames.jsonMime, parentId: "folder1",
                                     tag: .event, content: Data(#"{"v":1}"#.utf8))
        let request = StubURLProtocol.requests[0]
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/upload/drive/v3/files")
        XCTAssertTrue(request.url!.query!.contains("uploadType=multipart"))
        XCTAssertTrue(request.value(forHTTPHeaderField: "Content-Type")!.hasPrefix("multipart/related; boundary="))
        let text = body(request)
        XCTAssertTrue(text.contains(#""parents":["folder1"]"#), text)
        XCTAssertTrue(text.contains(#""appProperties":{"soslive":"event"}"#), text)
        XCTAssertTrue(text.contains(#"{"v":1}"#))
    }

    func testUpdateShareTrashEndpoints() async throws {
        StubURLProtocol.reset(Array(repeating: .init(status: 200, body: #"{"id":"e1"}"#), count: 3))
        try await api.updateContent(id: "e1", mimeType: DriveNames.jsonMime, content: Data("{}".utf8))
        try await api.shareAnyoneReader(id: "e1")
        try await api.trash(id: "e1")
        let (update, share, trash) = (StubURLProtocol.requests[0], StubURLProtocol.requests[1], StubURLProtocol.requests[2])
        XCTAssertEqual(update.httpMethod, "PATCH")
        XCTAssertEqual(update.url?.path, "/upload/drive/v3/files/e1")
        XCTAssertTrue(update.url!.query!.contains("uploadType=media"))
        XCTAssertEqual(share.url?.path, "/drive/v3/files/e1/permissions")
        let permission = try JSON.object(from: share.httpBody!)
        XCTAssertEqual(permission["type"] as? String, "anyone")
        XCTAssertEqual(permission["role"] as? String, "reader")
        XCTAssertEqual(trash.httpMethod, "PATCH")
        XCTAssertEqual(try JSON.object(from: trash.httpBody!)["trashed"] as? Bool, true)
    }

    func testSharingEndpoints() async throws {
        StubURLProtocol.reset([
            .init(status: 200, body: #"{"id":"ev","name":"events"}"#),
            .init(status: 200, body: #"{"id":"e1"}"#),
            .init(status: 200, body: #"{"permissions":[{"id":"o","type":"user","role":"owner","emailAddress":"me@example.com"},{"id":"a","type":"anyone","role":"reader"},{"id":"p1","type":"user","role":"reader","emailAddress":"anna@example.com","displayName":"Anna"}]}"#),
            .init(status: 200, body: #"{"id":"p2","emailAddress":"bela@example.com"}"#),
            .init(status: 204, body: ""),
        ])
        _ = try await api.createFolder(name: DriveNames.eventsFolder, tag: .events, parentId: "root1")
        try await api.moveFile(id: "e1", from: "root1", to: "ev")
        let people = try await api.listUserPermissions(id: "ev")
        let added = try await api.shareWithUser(id: "ev", email: "bela@example.com")
        try await api.removePermission(id: "ev", permissionId: "p1")

        let r = StubURLProtocol.requests
        let folder = try JSON.object(from: r[0].httpBody!)
        XCTAssertEqual(folder["parents"] as? [String], ["root1"])
        XCTAssertEqual((folder["appProperties"] as? JSONObject)?["soslive"] as? String, "events")
        XCTAssertEqual(r[1].httpMethod, "PATCH")
        XCTAssertTrue(r[1].url!.query!.contains("addParents=ev"))
        XCTAssertTrue(r[1].url!.query!.contains("removeParents=root1"))
        XCTAssertEqual(people, [DrivePermission(id: "p1", email: "anna@example.com", displayName: "Anna")])
        XCTAssertEqual(r[2].url?.path, "/drive/v3/files/ev/permissions")
        XCTAssertTrue(r[3].url!.query!.contains("sendNotificationEmail=true"))
        let share = try JSON.object(from: r[3].httpBody!)
        XCTAssertEqual(share["type"] as? String, "user")
        XCTAssertEqual(share["role"] as? String, "reader")
        XCTAssertEqual(share["emailAddress"] as? String, "bela@example.com")
        XCTAssertEqual(added, DrivePermission(id: "p2", email: "bela@example.com"))
        XCTAssertEqual(r[4].httpMethod, "DELETE")
        XCTAssertEqual(r[4].url?.path, "/drive/v3/files/ev/permissions/p1")
    }

    func testRetryableClassification() {
        XCTAssertTrue(DriveError.http(status: 429, message: "").retryable)
        XCTAssertTrue(DriveError.http(status: 503, message: "").retryable)
        XCTAssertTrue(DriveError.http(status: 403, message: "userRateLimitExceeded").retryable)
        XCTAssertFalse(DriveError.http(status: 403, message: "insufficientFilePermissions").retryable)
        XCTAssertTrue(DriveError.network("offline").retryable)
        XCTAssertFalse(DriveError.notFound("x").retryable)
    }
}
