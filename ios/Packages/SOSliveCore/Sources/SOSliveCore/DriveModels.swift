import Foundation

// File formats shared with the web app (endorbithu/soslive-webapp docs/EVENT_FORMAT.md).

/// appProperties {"soslive": <tag>} values.
public enum DriveTag: String {
    /// `events` = SOSlive/events: events + images, the only folder shared with people.
    case root, config, events, event, image
}

public enum DriveNames {
    public static let folder = "SOSlive"
    public static let eventsFolder = "events"
    public static let config = "config.json"
    public static let jsonMime = "application/json"
    public static let folderMime = "application/vnd.google-apps.folder"
}

/// JSON object that keeps unknown fields (JSONSerialization based).
public typealias JSONObject = [String: Any]

enum JSON {
    static func object(from data: Data) throws -> JSONObject {
        guard let object = try JSONSerialization.jsonObject(with: data) as? JSONObject else {
            throw DriveError.parse("Expected a JSON object")
        }
        return object
    }

    static func data(_ object: JSONObject) -> Data {
        (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])) ?? Data("{}".utf8)
    }
}

private let eventNameFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = TimeZone(identifier: "UTC")
    formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
    return formatter
}()

/// Event file name: the start time in UTC, e.g. "2026-09-29 14:03:22.json".
public func eventFileName(start: Date) -> String {
    eventNameFormatter.string(from: start) + ".json"
}

/// The web shows the file name without ".json" as the event title.
public func eventTitle(fileName: String) -> String {
    fileName.hasSuffix(".json") ? String(fileName.dropLast(5)) : fileName
}

/// ISO 8601 UTC with second precision, e.g. "2026-09-29T14:03:22Z".
public func isoUTC(_ date: Date) -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime]
    formatter.timeZone = TimeZone(identifier: "UTC")
    return formatter.string(from: date)
}

public func parseISO(_ value: String) -> Date? {
    let formatter = ISO8601DateFormatter()
    if let date = formatter.date(from: value) { return date }
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter.date(from: value)
}

/// config.json - written only by the mobile app; unknown fields are kept and written back.
public struct SosConfig {
    public static let defaultMaxEvents = 100

    public var notificationEmails: [String]
    public var notificationPhones: [String]
    public var maxEvents: Int
    public var raw: JSONObject

    public init(notificationEmails: [String] = [], notificationPhones: [String] = [],
                maxEvents: Int = SosConfig.defaultMaxEvents, raw: JSONObject = [:]) {
        self.notificationEmails = notificationEmails
        self.notificationPhones = notificationPhones
        self.maxEvents = maxEvents
        self.raw = raw
    }

    public init(data: Data) throws {
        let object = try JSON.object(from: data)
        let max = object["max_events"] as? Int ?? 0
        self.init(
            notificationEmails: object["notification_emails"] as? [String] ?? [],
            notificationPhones: object["notification_phones"] as? [String] ?? [],
            maxEvents: max >= 1 ? max : SosConfig.defaultMaxEvents,
            raw: object
        )
    }

    public func encoded() -> Data {
        var object = raw
        object["v"] = 1
        object["notification_emails"] = notificationEmails
        object["notification_phones"] = notificationPhones
        object["max_events"] = maxEvents
        return JSON.data(object)
    }
}

/// One entry of an event file. Unknown entry types / fields are preserved as-is.
public struct EventEntry {
    public var json: JSONObject

    public init(json: JSONObject) { self.json = json }

    public var type: String? { json["type"] as? String }
    public var time: String? { json["t"] as? String }
    public func string(_ key: String) -> String? { json[key] as? String }
    public func double(_ key: String) -> Double? { (json[key] as? NSNumber)?.doubleValue }

    public static func position(_ time: Date, lat: Double, lng: Double) -> EventEntry {
        EventEntry(json: ["t": isoUTC(time), "type": "pos", "lat": lat, "lng": lng])
    }

    /// `name` is the displayed sender - never a phone number or e-mail address.
    public static func message(_ time: Date, name: String, text: String) -> EventEntry {
        EventEntry(json: ["t": isoUTC(time), "type": "msg", "name": name, "text": text])
    }

    /// `url` must be a publicly readable http(s) image URL.
    public static func image(_ time: Date, url: String) -> EventEntry {
        EventEntry(json: ["t": isoUTC(time), "type": "img", "url": url])
    }
}

/// Event file: {"v": 1, "stream": "...", "stream_page": "...", "recording": "...", "entries": [...]}.
/// "stream" is a directly playable URL (HLS / MP4), "stream_page" the viewer page of the user's
/// streaming service, "recording" where the recording can be downloaded / watched later.
/// The optional fields are only written when set.
public struct EventDocument {
    public var stream: String
    public var streamPage: String
    public var recording: String
    public var entries: [EventEntry]
    public var raw: JSONObject

    public init(stream: String = "", streamPage: String = "", recording: String = "", entries: [EventEntry] = [], raw: JSONObject = [:]) {
        self.stream = stream
        self.streamPage = streamPage
        self.recording = recording
        self.entries = entries
        self.raw = raw
    }

    public init(data: Data) throws {
        let object = try JSON.object(from: data)
        self.init(
            stream: object["stream"] as? String ?? "",
            streamPage: object["stream_page"] as? String ?? "",
            recording: object["recording"] as? String ?? "",
            entries: (object["entries"] as? [JSONObject] ?? []).map(EventEntry.init(json:)),
            raw: object
        )
    }

    public func encoded() -> Data {
        var object = raw
        object["v"] = 1
        object["stream"] = stream
        if !streamPage.isEmpty { object["stream_page"] = streamPage }
        if !recording.isEmpty { object["recording"] = recording }
        object["entries"] = entries.map(\.json)
        return JSON.data(object)
    }
}

/// Validation rules of config.json (see the mobile app spec).
public enum ContactRules {
    public static func isValidEmail(_ value: String) -> Bool {
        value.count <= 128 && value.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) != nil
    }

    /// International format: "+" then digits, spaces, "(", ")", "-"; 7-15 digits.
    public static func isValidPhone(_ value: String) -> Bool {
        guard value.range(of: #"^\+[0-9 ()\-]+$"#, options: .regularExpression) != nil else { return false }
        return (7...15).contains(value.filter(\.isNumber).count)
    }

    /// "+" and digits only, for the SMS composer.
    public static func dialable(_ phone: String) -> String {
        "+" + phone.filter(\.isNumber)
    }

    /// Splits user input (comma / semicolon / newline separated), trims, drops duplicates.
    public static func split(_ input: String) -> [String] {
        var seen = Set<String>()
        return input
            .components(separatedBy: CharacterSet(charactersIn: ",;\n"))
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }
}

public struct GeoPoint: Equatable {
    public var lat: Double
    public var lng: Double
    public var accuracy: Double?

    public init(lat: Double, lng: Double, accuracy: Double? = nil) {
        self.lat = lat
        self.lng = lng
        self.accuracy = accuracy
    }
}
