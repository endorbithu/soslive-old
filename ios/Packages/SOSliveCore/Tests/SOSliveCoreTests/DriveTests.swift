import XCTest
@testable import SOSliveCore

final class DriveModelsTests: XCTestCase {
    func testConfigKeepsUnknownFieldsAndDefaults() throws {
        let config = try SosConfig(data: Data(#"{"v":1,"notification_emails":["mom@example.com"],"notification_phones":["+36 30 123 4567"],"future":{"x":1}}"#.utf8))
        XCTAssertEqual(config.notificationEmails, ["mom@example.com"])
        XCTAssertEqual(config.notificationPhones, ["+36 30 123 4567"])
        XCTAssertEqual(config.maxEvents, 100)

        var changed = config
        changed.maxEvents = 5
        let written = try JSON.object(from: changed.encoded())
        XCTAssertEqual(written["max_events"] as? Int, 5)
        XCTAssertEqual(written["v"] as? Int, 1)
        XCTAssertNotNil(written["future"] as? JSONObject)

        XCTAssertEqual(try SosConfig(data: Data(#"{"max_events":0}"#.utf8)).maxEvents, 100)
    }

    func testEventFileNameIsUTCStart() {
        let start = parseISO("2026-09-29T14:03:22Z")!
        XCTAssertEqual(eventFileName(start: start), "2026-09-29 14:03:22.json")
        XCTAssertEqual(eventTitle(fileName: "2026-09-29 14:03:22.json"), "2026-09-29 14:03:22")
    }

    func testEventDocumentRoundTrip() throws {
        let t = parseISO("2026-09-29T14:03:22Z")!
        let doc = EventDocument(stream: "https://s/live/abc.m3u8", entries: [
            .position(t, lat: 47.4979, lng: 19.0402),
            .message(t.addingTimeInterval(18), name: "Anna", text: "Elindultam haza"),
            .image(t.addingTimeInterval(43), url: "https://img/abc.jpg"),
        ])
        let parsed = try EventDocument(data: doc.encoded())
        XCTAssertEqual(parsed.stream, "https://s/live/abc.m3u8")
        XCTAssertEqual(parsed.entries.map(\.type), ["pos", "msg", "img"])
        XCTAssertEqual(parsed.entries[0].time, "2026-09-29T14:03:22Z")
        XCTAssertEqual(parsed.entries[0].double("lat"), 47.4979)
        XCTAssertEqual(parsed.entries[1].string("name"), "Anna")
        let raw = try JSON.object(from: doc.encoded())
        XCTAssertEqual(raw["v"] as? Int, 1)
    }

    func testContactRules() {
        XCTAssertTrue(ContactRules.isValidPhone("+36 30 123 4567"))
        XCTAssertTrue(ContactRules.isValidPhone("+36(30)123-4567"))
        XCTAssertFalse(ContactRules.isValidPhone("06301234567"))
        XCTAssertFalse(ContactRules.isValidPhone("+36 30 abc"))
        XCTAssertEqual(ContactRules.dialable("+36 (30) 123-4567"), "+36301234567")
        XCTAssertTrue(ContactRules.isValidEmail("mom@example.com"))
        XCTAssertFalse(ContactRules.isValidEmail("mom@example"))
        XCTAssertEqual(ContactRules.split(" a@b.hu ,c@d.hu;\n a@b.hu \n"), ["a@b.hu", "c@d.hu"])
    }

    func testTemplateStreamProvider() async throws {
        let provider = TemplateStreamProvider(rtmpURL: "rtmp://h:1935/live/", hlsTemplate: "https://h/live/{key}/index.m3u8")
        let session = try await provider.createStream()
        XCTAssertEqual(session.rtmpURL, "rtmp://h:1935/live")
        XCTAssertNotNil(session.streamKey.range(of: "^[0-9a-f]{32}$", options: .regularExpression))
        XCTAssertEqual(session.publishURL, "rtmp://h:1935/live/\(session.streamKey)")
        XCTAssertEqual(session.playbackURL, "https://h/live/\(session.streamKey)/index.m3u8")
    }
}

final class SosliveDriveTests: XCTestCase {
    private var api: FakeDriveAPI!
    private var cache: MemoryDriveCache!
    private var drive: SosliveDrive!

    override func setUp() {
        api = FakeDriveAPI()
        cache = MemoryDriveCache()
        drive = SosliveDrive(drive: api, cache: cache, webappURL: "https://web.example/")
    }

    func testOldestFolderWins() async throws {
        api.addFolder("newer", created: Date(timeIntervalSince1970: 2_000))
        api.addFolder("older", created: Date(timeIntervalSince1970: 1_000))
        let id = try await drive.folderId()
        XCTAssertEqual(id, "older")
        XCTAssertEqual(cache.folderId, "older")
    }

    func testFolderIsRecreatedWhenCachedOneWasTrashed() async throws {
        let first = try await drive.folderId()
        try await api.trash(id: first)
        let second = try await drive.folderId()
        XCTAssertNotEqual(first, second)
    }

    func testEventIsCreatedSharedAndLinked() async throws {
        let created = try await drive.createEvent(start: parseISO("2026-09-29T14:03:22Z")!, document: EventDocument(stream: "https://s/x.m3u8"))
        XCTAssertEqual(created.name, "2026-09-29 14:03:22.json")
        XCTAssertEqual(created.link, "https://web.example/e/\(created.fileId)")
        XCTAssertTrue(created.shared)
        let item = api.item(created.fileId)
        XCTAssertTrue(item.shared)
        XCTAssertEqual(item.tag, .event)
        XCTAssertEqual(item.parentId, cache.folderId)
        XCTAssertEqual(try EventDocument(data: item.content).stream, "https://s/x.m3u8")
    }

    func testShareFailureIsReported() async throws {
        api.shareFailure = DriveError.http(status: 403, message: "Sharing is not allowed by the domain policy")
        let created = try await drive.createEvent(start: Date(), document: EventDocument())
        XCTAssertFalse(created.shared)
        XCTAssertTrue(created.shareError?.contains("domain policy") == true)
    }

    func testConfigCreatedOnceThenOverwritten() async throws {
        let none = try await drive.readRemoteConfig()
        XCTAssertNil(none)
        try await drive.saveConfig(SosConfig(notificationPhones: ["+36 30 123 4567"]))
        try await drive.saveConfig(SosConfig(notificationPhones: ["+36 30 765 4321"], maxEvents: 3))
        let configs = api.items.filter { $0.tag == .config }
        XCTAssertEqual(configs.count, 1)
        XCTAssertFalse(configs[0].shared)
        let remote = try await drive.readRemoteConfig()
        XCTAssertEqual(remote?.notificationPhones, ["+36 30 765 4321"])
        XCTAssertEqual(drive.cachedConfig?.maxEvents, 3)
    }

    func testRotationTrashesOldestBeyondMax() async throws {
        var ids: [String] = []
        for i in 0..<5 {
            ids.append(try await drive.createEvent(start: Date(timeIntervalSince1970: Double(i * 60)), document: EventDocument()).fileId)
        }
        let trashed = try await drive.rotate(maxEvents: 3)
        XCTAssertEqual(trashed, 2)
        let remaining = try await drive.listEvents().map(\.fileId)
        XCTAssertEqual(remaining, Array(ids.suffix(3).reversed()))
        XCTAssertTrue(api.item(ids[0]).file.trashed)
    }

    func testRecoversWhenCachedFolderWasDeleted() async throws {
        _ = try await drive.folderId()
        api.clear()
        let created = try await drive.createEvent(start: Date(), document: EventDocument())
        XCTAssertEqual(api.item(created.fileId).parentId, cache.folderId)
    }
}

final class EventWriterTests: XCTestCase {
    private func makeWriter(_ api: FakeDriveAPI, now: @escaping @Sendable () -> Date = Date.init,
                            onStopped: @escaping @Sendable (Error) -> Void = { _ in }) async throws -> EventWriter {
        let folder = try await api.createFolder(name: DriveNames.folder, tag: .root)
        let file = try await api.createFile(name: "e.json", mimeType: DriveNames.jsonMime, parentId: folder.id, tag: .event, content: EventDocument().encoded())
        return EventWriter(drive: api, fileId: file.id, initial: EventDocument(stream: "s"), now: now,
                           debounce: 0.05, initialBackoff: 0.02, maxBackoff: 0.1, onStopped: onStopped)
    }

    func testEntriesCloseTogetherGoUpInOneUpload() async throws {
        let api = FakeDriveAPI()
        let writer = try await makeWriter(api)
        await writer.addMessage(name: "Anna", text: "one")
        await writer.addMessage(name: "Anna", text: "two")
        await writer.setStream("https://x/y.m3u8")
        await writer.close()
        XCTAssertEqual(api.uploads.count, 1)
        let doc = try EventDocument(data: api.uploads.last!)
        XCTAssertEqual(doc.entries.count, 2)
        XCTAssertEqual(doc.stream, "https://x/y.m3u8")
        let pending = await writer.hasPendingChanges
        XCTAssertFalse(pending)
    }

    func testRetryableErrorsAreRetried() async throws {
        let api = FakeDriveAPI()
        api.failNextUpdates(DriveError.network("offline"), DriveError.http(status: 503, message: "backend"))
        let writer = try await makeWriter(api)
        await writer.addMessage(name: "Anna", text: "one")
        await writer.close()
        XCTAssertEqual(api.uploads.count, 1)
        XCTAssertEqual(try EventDocument(data: api.uploads[0]).entries.map { $0.string("text") }, ["one"])
    }

    func testNotFoundStopsWriting() async throws {
        let api = FakeDriveAPI()
        api.failNextUpdates(DriveError.notFound("deleted"))
        let stoppedWith = Box<Error>()
        let writer = try await makeWriter(api, onStopped: { stoppedWith.value = $0 })
        await writer.addMessage(name: "Anna", text: "one")
        await writer.close()
        let stopped = await writer.stopped
        XCTAssertTrue(stopped)
        XCTAssertEqual(stoppedWith.value as? DriveError, .notFound("deleted"))
        await writer.addMessage(name: "Anna", text: "ignored")
        await writer.close()
        XCTAssertEqual(api.uploads.count, 0)
    }

    func testPositionsAtMostEvery30Seconds() async throws {
        let api = FakeDriveAPI()
        let clock = Box<Date>(parseISO("2026-09-29T14:03:22Z")!)
        let writer = try await makeWriter(api, now: { clock.value! })
        var added = await writer.addPosition(lat: 47.0, lng: 19.0)
        XCTAssertTrue(added)
        clock.value = clock.value!.addingTimeInterval(10)
        added = await writer.addPosition(lat: 47.1, lng: 19.1)
        XCTAssertFalse(added)
        clock.value = clock.value!.addingTimeInterval(20)
        added = await writer.addPosition(lat: 47.2, lng: 19.2)
        XCTAssertTrue(added)
        added = await writer.addPosition(lat: 47.3, lng: 19.3, force: true)
        XCTAssertTrue(added)
        await writer.close()
        let entries = try EventDocument(data: api.uploads.last!).entries
        XCTAssertEqual(entries.map { $0.double("lat") }, [47.0, 47.2, 47.3])
        XCTAssertEqual(entries.first?.time, "2026-09-29T14:03:22Z")
    }
}

/// Thread-safe mutable box for test closures.
final class Box<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: T?
    init(_ value: T? = nil) { _value = value }
    var value: T? {
        get { lock.lock(); defer { lock.unlock() }; return _value }
        set { lock.lock(); _value = newValue; lock.unlock() }
    }
}
