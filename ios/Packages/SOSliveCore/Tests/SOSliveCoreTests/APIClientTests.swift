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

final class APIClientTests: XCTestCase {
    private let userJSON = #"{"id":1,"email":"a@b.hu","displayName":"A"}"#
    private var store: InMemorySessionStore!
    private var api: APIClient!

    override func setUp() {
        super.setUp()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        store = InMemorySessionStore(Session(accessToken: "old-access", refreshToken: "old-refresh",
                                             user: User(id: 1, email: "a@b.hu", displayName: "A")))
        api = APIClient(baseURL: URL(string: "http://test.local/")!, store: store, urlSession: URLSession(configuration: config))
    }

    func testExpiredTokenIsRefreshedOnceAndRequestRetried() async throws {
        StubURLProtocol.reset([
            .init(status: 401, body: #"{"error":{"code":"token_expired","message":"jwt expired"}}"#),
            .init(status: 200, body: #"{"accessToken":"new-access","refreshToken":"new-refresh","user":\#(userJSON)}"#),
            .init(status: 200, body: userJSON),
        ])

        let user: User = try await api.get("me")

        XCTAssertEqual(user.displayName, "A")
        let requests = StubURLProtocol.requests
        XCTAssertEqual(requests.count, 3)
        XCTAssertEqual(requests[0].value(forHTTPHeaderField: "Authorization"), "Bearer old-access")
        XCTAssertEqual(requests[1].url?.path, "/auth/refresh")
        XCTAssertTrue(String(decoding: requests[1].httpBody ?? Data(), as: UTF8.self).contains("old-refresh"))
        XCTAssertEqual(requests[2].value(forHTTPHeaderField: "Authorization"), "Bearer new-access")
        XCTAssertEqual(store.load()?.refreshToken, "new-refresh")
    }

    func testRejectedRefreshTokenClearsSessionAndNotifies() async {
        StubURLProtocol.reset([
            .init(status: 401, body: #"{"error":{"code":"token_expired","message":"jwt expired"}}"#),
            .init(status: 401, body: #"{"error":{"code":"invalid_refresh_token","message":"nope"}}"#),
        ])
        var notified: [Session?] = []
        api.onSessionChange = { notified.append($0) }

        do {
            let _: User = try await api.get("me")
            XCTFail("expected an error")
        } catch let error as APIError {
            XCTAssertEqual(error.httpStatus, 401)
            XCTAssertEqual(error.code, "token_expired")
        } catch {
            XCTFail("unexpected \(error)")
        }
        XCTAssertNil(store.load())
        XCTAssertEqual(notified.count, 1)
        XCTAssertNil(notified.first ?? nil)
    }

    func testDecodesEventWithMillisecondDates() async throws {
        StubURLProtocol.reset([
            .init(status: 201, body: """
            {"id":5,"type":"SOS","status":"LIVE","createdAt":"2026-09-28T14:01:04.752Z","stoppedAt":null,
             "lastLocation":{"lat":47.5,"lng":19.04,"at":"2026-09-28T14:01:04.752Z"},"shareUrl":"http://x/e/5",
             "stream":{"url":"rtmp://h:1935/live","streamKey":"k","publishUrl":"rtmp://h:1935/live/k"},
             "commentCount":0,"photoCount":0}
            """),
        ])
        let defaults = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
        let events = EventService(api: api, activeEvents: ActiveEventStore(defaults: defaults))

        let event = try await events.create(type: .sos, location: GeoPoint(lat: 47.5, lng: 19.04))

        XCTAssertEqual(event.stream?.streamKey, "k")
        XCTAssertEqual(event.status, .live)
        XCTAssertEqual(event.createdAt.timeIntervalSince1970, 1_790_604_064.752, accuracy: 0.001)
        XCTAssertEqual(events.activeEvents.current?.id, 5)
        let body = String(decoding: StubURLProtocol.requests[0].httpBody ?? Data(), as: UTF8.self)
        XCTAssertTrue(body.contains(#""type":"SOS""#), body)
    }

    func testLoginStoresSessionAndMapsErrors() async throws {
        let auth = AuthService(api: api, activeEvents: ActiveEventStore(defaults: UserDefaults(suiteName: "t-\(UUID())")!))
        StubURLProtocol.reset([
            .init(status: 401, body: #"{"error":{"code":"invalid_credentials","message":"Wrong"}}"#),
            .init(status: 200, body: #"{"accessToken":"a2","refreshToken":"r2","user":\#(userJSON)}"#),
        ])

        do {
            _ = try await auth.login(email: "a@b.hu", password: "bad")
            XCTFail("expected an error")
        } catch let error as APIError {
            XCTAssertEqual(error.code, "invalid_credentials")
        }
        XCTAssertNil(StubURLProtocol.requests[0].value(forHTTPHeaderField: "Authorization"))

        let user = try await auth.login(email: " a@b.hu ", password: "good")
        XCTAssertEqual(user.id, 1)
        XCTAssertEqual(store.load()?.accessToken, "a2")
    }
}
