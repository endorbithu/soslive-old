import XCTest
@testable import SOSliveCore

final class ValidationTests: XCTestCase {
    func testParsesSeparatorsStripsSpacesAndDuplicates() {
        XCTAssertEqual(
            SosContacts.parse("+36 30 123-4567, +36301234567;\n+441234567890\n\n"),
            .valid(["+36301234567", "+441234567890"])
        )
    }

    func testRejectsNumbersWithoutInternationalPrefix() {
        XCTAssertEqual(SosContacts.parse("06301234567\n+36301234567"), .invalid(["06301234567"]))
    }

    func testEmptyInputIsValidEmptyList() {
        XCTAssertEqual(SosContacts.parse("  "), .valid([]))
    }

    func testTooManyNumbers() {
        let input = (0...SosContacts.maxContacts).map { "+3630123456\($0)" }.joined(separator: "\n")
        XCTAssertEqual(SosContacts.parse(input), .tooMany)
    }

    func testSimulatedSsoTokenMatchesMockBackend() {
        XCTAssertEqual(SimulatedSso.token(email: " a@b.hu ", displayName: " Anna "), "mock:a@b.hu|Anna")
        XCTAssertEqual(SimulatedSso.token(email: "a@b.hu", displayName: ""), "mock:a@b.hu")
    }

    func testSosMessageFallback() {
        XCTAssertEqual(SosMessage.build(template: " ", fallback: "Help", shareUrl: "http://x/e/1"), "Help - http://x/e/1")
        XCTAssertEqual(SosMessage.build(template: "Mine ", fallback: "Help", shareUrl: "http://x/e/1"), "Mine - http://x/e/1")
    }

    func testEmail() {
        XCTAssertTrue(Email.isValid("a@b.hu"))
        XCTAssertFalse(Email.isValid("a@b"))
    }

    func testActiveEventExpiry() {
        let start = Date(timeIntervalSince1970: 1_000)
        let event = ActiveEvent(id: 1, type: .photo, startedAt: start, shareUrl: "")
        XCTAssertFalse(event.isExpired(now: start.addingTimeInterval(999), window: 1_000))
        XCTAssertTrue(event.isExpired(now: start.addingTimeInterval(1_000), window: 1_000))
    }

    func testActiveEventStoreDropsExpiredEvent() {
        let defaults = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
        var now = Date(timeIntervalSince1970: 0)
        let store = ActiveEventStore(defaults: defaults, now: { now })
        store.save(ActiveEvent(id: 7, type: .photo, startedAt: now, shareUrl: "u"))
        XCTAssertEqual(store.current?.id, 7)
        now = now.addingTimeInterval(ActiveEvent.window)
        XCTAssertNil(store.current)
    }
}
