import Foundation

// Wire format of the SOSlive API - see mock-server/openapi.yaml.

public struct User: Codable, Equatable, Identifiable {
    public var id: Int64
    public var email: String
    public var displayName: String
    public var avatarUrl: String?
    public var providers: [String]
    public var sosContacts: [String]
    public var sosMessage: String

    public init(id: Int64, email: String, displayName: String, avatarUrl: String? = nil,
                providers: [String] = [], sosContacts: [String] = [], sosMessage: String = "") {
        self.id = id
        self.email = email
        self.displayName = displayName
        self.avatarUrl = avatarUrl
        self.providers = providers
        self.sosContacts = sosContacts
        self.sosMessage = sosMessage
    }

    enum CodingKeys: String, CodingKey { case id, email, displayName, avatarUrl, providers, sosContacts, sosMessage }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int64.self, forKey: .id)
        email = try c.decode(String.self, forKey: .email)
        displayName = try c.decode(String.self, forKey: .displayName)
        avatarUrl = try c.decodeIfPresent(String.self, forKey: .avatarUrl)
        providers = try c.decodeIfPresent([String].self, forKey: .providers) ?? []
        sosContacts = try c.decodeIfPresent([String].self, forKey: .sosContacts) ?? []
        sosMessage = try c.decodeIfPresent(String.self, forKey: .sosMessage) ?? ""
    }
}

public struct Session: Codable, Equatable {
    public var accessToken: String
    public var refreshToken: String
    public var user: User

    public init(accessToken: String, refreshToken: String, user: User) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.user = user
    }
}

public enum EventType: String, Codable, CaseIterable {
    case sos = "SOS"
    case live = "LIVE"
    case photo = "PHOTO"
}

public enum EventStatus: String, Codable {
    case live = "LIVE"
    case open = "OPEN"
    case stopped = "STOPPED"
    case unknown

    public init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = EventStatus(rawValue: raw) ?? .unknown
    }
}

public struct GeoPoint: Codable, Equatable {
    public var lat: Double
    public var lng: Double
    public var accuracy: Double?

    public init(lat: Double, lng: Double, accuracy: Double? = nil) {
        self.lat = lat
        self.lng = lng
        self.accuracy = accuracy
    }
}

public struct StreamTarget: Codable, Equatable {
    /// RTMP application URL, e.g. rtmp://host:1935/live
    public var url: String
    public var streamKey: String
    /// url + "/" + streamKey
    public var publishUrl: String
}

public struct Event: Codable, Equatable, Identifiable {
    public var id: Int64
    public var type: EventType
    public var status: EventStatus
    public var createdAt: Date
    public var stoppedAt: Date?
    public var lastLocation: GeoPoint?
    public var shareUrl: String
    public var stream: StreamTarget?
    public var commentCount: Int
    public var photoCount: Int
}

public struct Comment: Codable, Equatable, Identifiable {
    public var id: Int64
    public var eventId: Int64
    public var authorName: String
    public var authorType: String
    public var message: String
    public var createdAt: Date

    public var fromOwner: Bool { authorType == "OWNER" }
}

public struct CommentPage: Codable, Equatable {
    public var total: Int
    public var items: [Comment]
}

public struct Photo: Codable, Equatable, Identifiable {
    public var id: Int64
    public var eventId: Int64
    public var url: String
    public var createdAt: Date
}

struct ItemList<T: Codable>: Codable {
    var items: [T]
}

struct ErrorEnvelope: Codable {
    struct Body: Codable {
        var code: String
        var message: String
    }
    var error: Body
}

/// The incident the user is working on: photos and comments go to it until it expires
/// (legacy behaviour: 2 hours) or the user starts a new one.
public struct ActiveEvent: Codable, Equatable {
    public var id: Int64
    public var type: EventType
    public var startedAt: Date
    public var shareUrl: String

    public init(id: Int64, type: EventType, startedAt: Date, shareUrl: String) {
        self.id = id
        self.type = type
        self.startedAt = startedAt
        self.shareUrl = shareUrl
    }

    public static let window: TimeInterval = 2 * 60 * 60

    public func isExpired(now: Date, window: TimeInterval = ActiveEvent.window) -> Bool {
        now.timeIntervalSince(startedAt) >= window
    }
}
