import Foundation

/// An event file that was just created on Drive.
public struct CreatedEvent {
    public var fileId: String
    public var name: String
    public var link: String
    /// False when the "anyone with the link" share failed (e.g. a Workspace policy forbids it).
    public var shared: Bool
    public var shareError: String?
}

public struct EventSummary: Identifiable, Equatable {
    public var id: String { fileId }
    public var fileId: String
    public var title: String
    public var createdTime: Date?
    public var link: String
}

/// Local copy of small bits of state (folder id, last config.json).
public protocol DriveCache: AnyObject {
    var folderId: String? { get set }
    var configData: Data? { get set }
}

public final class UserDefaultsDriveCache: DriveCache {
    private let defaults: UserDefaults
    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public var folderId: String? {
        get { defaults.string(forKey: "soslive.driveFolderId") }
        set { defaults.set(newValue, forKey: "soslive.driveFolderId") }
    }

    public var configData: Data? {
        get { defaults.data(forKey: "soslive.configJson") }
        set { defaults.set(newValue, forKey: "soslive.configJson") }
    }
}

/// The SOSlive folder on the user's Drive: config.json and one JSON file per event.
/// Follows the mobile app spec: the oldest "SOSlive" folder wins, config.json is private,
/// event files are shared "anyone with the link", old events are rotated to the trash.
public final class SosliveDrive {
    private let drive: DriveAPI
    private let cache: DriveCache
    private let webappURL: String

    public init(drive: DriveAPI, cache: DriveCache, webappURL: String) {
        self.drive = drive
        self.cache = cache
        self.webappURL = webappURL.hasSuffix("/") ? String(webappURL.dropLast()) : webappURL
    }

    public var api: DriveAPI { drive }

    public func link(fileId: String) -> String { "\(webappURL)/e/\(fileId)" }

    /// Finds (or creates) the SOSlive folder; searches again when the cached one was deleted.
    public func folderId() async throws -> String {
        if let cached = cache.folderId {
            if let file = try await drive.file(id: cached), !file.trashed { return cached }
            cache.folderId = nil
        }
        var id = try await oldestFolder()
        if id == nil {
            _ = try await drive.createFolder(name: DriveNames.folder, tag: .root)
            // If another client (e.g. the web) created one at the same time, the oldest wins.
            id = try await oldestFolder()
        }
        guard let id else { throw DriveError.parse("Created SOSlive folder is not visible") }
        cache.folderId = id
        return id
    }

    private func oldestFolder() async throws -> String? {
        try await drive.find(tag: .root, parentId: nil, newestFirst: false, folderOnly: true).first?.id
    }

    /// Runs `body` with the folder id; if the folder vanished meanwhile (404), finds it again once.
    private func inFolder<T>(_ body: (String) async throws -> T) async throws -> T {
        do {
            return try await body(try await folderId())
        } catch DriveError.notFound {
            cache.folderId = nil
            return try await body(try await folderId())
        }
    }

    // MARK: - config.json

    /// config.json from Drive; nil when the user has none yet.
    public func readRemoteConfig() async throws -> SosConfig? {
        try await inFolder { folder in
            guard let file = try await drive.find(tag: .config, parentId: folder, newestFirst: false, folderOnly: false).first else {
                return nil
            }
            let data = try await drive.download(id: file.id)
            cache.configData = data
            return try SosConfig(data: data)
        }
    }

    public var cachedConfig: SosConfig? { cache.configData.flatMap { try? SosConfig(data: $0) } }

    /// Writes config.json (create or overwrite the whole file). Only the mobile app writes it.
    public func saveConfig(_ config: SosConfig) async throws {
        let data = config.encoded()
        try await inFolder { folder in
            if let existing = try await drive.find(tag: .config, parentId: folder, newestFirst: false, folderOnly: false).first {
                try await drive.updateContent(id: existing.id, mimeType: DriveNames.jsonMime, content: data)
            } else {
                _ = try await drive.createFile(name: DriveNames.config, mimeType: DriveNames.jsonMime, parentId: folder, tag: .config, content: data)
            }
        }
        cache.configData = data
    }

    // MARK: - Events

    /// Creates the event file, shares it "anyone with the link" and returns the link to send out.
    public func createEvent(start: Date, document: EventDocument) async throws -> CreatedEvent {
        let name = eventFileName(start: start)
        let file = try await inFolder { folder in
            try await drive.createFile(name: name, mimeType: DriveNames.jsonMime, parentId: folder, tag: .event, content: document.encoded())
        }
        var shareError: String?
        do {
            try await drive.shareAnyoneReader(id: file.id)
        } catch {
            shareError = error.localizedDescription
        }
        return CreatedEvent(fileId: file.id, name: name, link: link(fileId: file.id), shared: shareError == nil, shareError: shareError)
    }

    public func readEvent(fileId: String) async throws -> EventDocument {
        try EventDocument(data: try await drive.download(id: fileId))
    }

    public func listEvents() async throws -> [EventSummary] {
        try await inFolder { folder in
            try await drive.find(tag: .event, parentId: folder, newestFirst: true, folderOnly: false).map {
                EventSummary(fileId: $0.id, title: eventTitle(fileName: $0.name), createdTime: $0.createdTime, link: link(fileId: $0.id))
            }
        }
    }

    /// Moves events beyond the newest `maxEvents` to the trash (restorable for 30 days).
    @discardableResult
    public func rotate(maxEvents: Int) async throws -> Int {
        let events = try await inFolder { folder in
            try await drive.find(tag: .event, parentId: folder, newestFirst: true, folderOnly: false)
        }
        let old = events.dropFirst(max(maxEvents, 1))
        for event in old { try await drive.trash(id: event.id) }
        return old.count
    }

    /// Uploads a JPEG next to the events, shares it publicly and returns a URL usable in an <img>.
    public func uploadImage(jpeg: Data, now: Date = Date()) async throws -> String {
        let name = "img " + eventTitle(fileName: eventFileName(start: now)) + ".jpg"
        let file = try await inFolder { folder in
            try await drive.createFile(name: name, mimeType: "image/jpeg", parentId: folder, tag: .image, content: jpeg)
        }
        try await drive.shareAnyoneReader(id: file.id)
        return drive.publicImageURL(id: file.id)
    }

    public func forgetLocalState() {
        cache.folderId = nil
        cache.configData = nil
    }
}
