import Foundation

// MARK: - Auth

public protocol AuthServicing: AnyObject {
    func login(email: String, password: String) async throws -> User
    func register(email: String, password: String, displayName: String) async throws -> User
    func loginWithGoogle(idToken: String) async throws -> User
    func loginWithFacebook(accessToken: String) async throws -> User
    func logout() async
}

public final class AuthService: AuthServicing {
    private let api: APIClient
    private let activeEvents: ActiveEventStore

    public init(api: APIClient, activeEvents: ActiveEventStore) {
        self.api = api
        self.activeEvents = activeEvents
    }

    public func login(email: String, password: String) async throws -> User {
        try await start(api.post("auth/login", body: ["email": email.trimmed, "password": password], authenticated: false))
    }

    public func register(email: String, password: String, displayName: String) async throws -> User {
        try await start(api.post("auth/register",
                                 body: ["email": email.trimmed, "password": password, "displayName": displayName.trimmed],
                                 authenticated: false))
    }

    public func loginWithGoogle(idToken: String) async throws -> User {
        try await start(api.post("auth/google", body: ["idToken": idToken], authenticated: false))
    }

    public func loginWithFacebook(accessToken: String) async throws -> User {
        try await start(api.post("auth/facebook", body: ["accessToken": accessToken], authenticated: false))
    }

    public func logout() async {
        let refreshToken = api.currentSession?.refreshToken
        // Best effort - the local session is cleared even if the backend is unreachable.
        let _: EmptyResponse? = try? await api.post("auth/logout", body: ["refreshToken": refreshToken ?? ""])
        activeEvents.clear()
        api.setSession(nil)
    }

    private func start(_ session: Session) -> User {
        // A different account may log in on this device: drop the previous user's open incident.
        activeEvents.clear()
        api.setSession(session)
        return session.user
    }
}

// MARK: - Profile

public struct ProfileUpdate: Encodable {
    public var displayName: String
    public var sosContacts: [String]
    public var sosMessage: String

    public init(displayName: String, sosContacts: [String], sosMessage: String) {
        self.displayName = displayName
        self.sosContacts = sosContacts
        self.sosMessage = sosMessage
    }
}

public final class ProfileService {
    private let api: APIClient

    public init(api: APIClient) { self.api = api }

    public func refresh() async throws -> User {
        store(try await api.get("me"))
    }

    public func update(_ update: ProfileUpdate) async throws -> User {
        store(try await api.patch("me", body: update))
    }

    private func store(_ user: User) -> User {
        if var session = api.currentSession {
            session.user = user
            api.setSession(session)
        }
        return user
    }
}

// MARK: - Events

private struct CreateEventBody: Encodable {
    var type: EventType
    var lat: Double?
    var lng: Double?
}

public final class EventService {
    private let api: APIClient
    public let activeEvents: ActiveEventStore

    public init(api: APIClient, activeEvents: ActiveEventStore) {
        self.api = api
        self.activeEvents = activeEvents
    }

    /// Creates the event on the backend and makes it the active incident.
    public func create(type: EventType, location: GeoPoint?) async throws -> Event {
        let event: Event = try await api.post("events", body: CreateEventBody(type: type, lat: location?.lat, lng: location?.lng))
        activeEvents.save(ActiveEvent(id: event.id, type: event.type, startedAt: Date(), shareUrl: event.shareUrl))
        return event
    }

    public func myEvents() async throws -> [Event] {
        let list: ItemList<Event> = try await api.get("events")
        return list.items
    }

    public func event(_ id: Int64) async throws -> Event {
        try await api.get("events/\(id)")
    }

    public func sendLocation(_ id: Int64, _ location: GeoPoint) async throws {
        let _: Event = try await api.post("events/\(id)/location", body: location)
    }

    @discardableResult
    public func stop(_ id: Int64) async throws -> Event {
        try await api.post("events/\(id)/stop")
    }

    public func comments(_ id: Int64, sinceId: Int64? = nil) async throws -> CommentPage {
        try await api.get("events/\(id)/comments", query: sinceId.map { [URLQueryItem(name: "sinceId", value: String($0))] } ?? [])
    }

    public func addComment(_ id: Int64, message: String) async throws -> Comment {
        try await api.post("events/\(id)/comments", body: ["message": message.trimmed])
    }

    public func photos(_ id: Int64) async throws -> [Photo] {
        let list: ItemList<Photo> = try await api.get("events/\(id)/photos")
        return list.items
    }

    public func uploadPhoto(_ id: Int64, jpeg: Data) async throws -> Photo {
        try await api.upload("events/\(id)/photos", field: "photo", fileName: "photo.jpg", mimeType: "image/jpeg", data: jpeg)
    }
}

// MARK: - Active incident

public final class ActiveEventStore {
    private let defaults: UserDefaults
    private let key = "soslive.activeEvent"
    private let now: () -> Date

    public init(defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init) {
        self.defaults = defaults
        self.now = now
    }

    /// The open incident, or nil when there is none or it expired.
    public var current: ActiveEvent? {
        guard let data = defaults.data(forKey: key),
              let event = try? JSONDecoder().decode(ActiveEvent.self, from: data),
              !event.isExpired(now: now()) else { return nil }
        return event
    }

    public func save(_ event: ActiveEvent) {
        defaults.set(try? JSONEncoder().encode(event), forKey: key)
    }

    public func clear() {
        defaults.removeObject(forKey: key)
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
