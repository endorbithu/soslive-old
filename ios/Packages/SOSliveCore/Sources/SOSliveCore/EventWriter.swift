import Foundation

/// Single writer of one event file. Keeps the whole document in memory, appends entries and
/// uploads the complete latest state (entries created close together go up in one upload).
/// Network errors, 429 and 5xx are retried with a growing delay; a 404 (the user deleted the
/// file) stops writing and is reported through `onStopped`.
public actor EventWriter {
    public nonisolated let fileId: String
    private let drive: DriveAPI
    private var document: EventDocument
    private var version = 0
    private var uploadedVersion = 0
    private var task: Task<Void, Never>?
    private var lastPositionAt: Date?
    private let now: @Sendable () -> Date
    private let debounce: TimeInterval
    private let initialBackoff: TimeInterval
    private let maxBackoff: TimeInterval
    private let positionInterval: TimeInterval
    private let onStopped: @Sendable (Error) -> Void

    public private(set) var stopped = false

    public init(drive: DriveAPI, fileId: String, initial: EventDocument,
                now: @escaping @Sendable () -> Date = Date.init,
                debounce: TimeInterval = 1.5, initialBackoff: TimeInterval = 2, maxBackoff: TimeInterval = 60,
                positionInterval: TimeInterval = 30,
                onStopped: @escaping @Sendable (Error) -> Void = { _ in }) {
        self.drive = drive
        self.fileId = fileId
        self.document = initial
        self.now = now
        self.debounce = debounce
        self.initialBackoff = initialBackoff
        self.maxBackoff = maxBackoff
        self.positionInterval = positionInterval
        self.onStopped = onStopped
        self.lastPositionAt = initial.entries.last(where: { $0.type == "pos" })?.time.flatMap(parseISO)
    }

    public var current: EventDocument { document }
    public var hasPendingChanges: Bool { uploadedVersion != version }

    public func setStream(_ url: String) { update { $0.stream = url } }

    /// Adds a position unless the previous one is younger than 30 s. Returns whether it was added.
    @discardableResult
    public func addPosition(lat: Double, lng: Double, force: Bool = false) -> Bool {
        let time = now()
        if !force, let last = lastPositionAt, time.timeIntervalSince(last) < positionInterval { return false }
        lastPositionAt = time
        update { $0.entries.append(.position(time, lat: lat, lng: lng)) }
        return true
    }

    public func addMessage(name: String, text: String) {
        let time = now()
        update { $0.entries.append(.message(time, name: name, text: text)) }
    }

    public func addImage(url: String) {
        let time = now()
        update { $0.entries.append(.image(time, url: url)) }
    }

    private func update(_ change: (inout EventDocument) -> Void) {
        guard !stopped else { return }
        change(&document)
        version += 1
        if task == nil {
            task = Task { [debounce] in
                try? await Task.sleep(nanoseconds: UInt64(debounce * 1_000_000_000))
                await self.uploadLoop()
            }
        }
    }

    /// Final upload when the event is closed: waits for pending changes to reach Drive.
    public func close() async {
        if let task { await task.value }
        if !stopped, hasPendingChanges { await uploadLoop() }
    }

    private func uploadLoop() async {
        var backoff = initialBackoff
        while !stopped {
            // No suspension between this check and clearing `task`, so update() never leaves a change behind.
            if uploadedVersion == version {
                task = nil
                return
            }
            let snapshot = document.encoded()
            let snapshotVersion = version
            do {
                try await drive.updateContent(id: fileId, mimeType: DriveNames.jsonMime, content: snapshot)
                uploadedVersion = max(uploadedVersion, snapshotVersion)
                backoff = initialBackoff
            } catch let error as DriveError where error.retryable {
                try? await Task.sleep(nanoseconds: UInt64(backoff * 1_000_000_000))
                backoff = min(backoff * 2, maxBackoff)
            } catch {
                stopped = true
                onStopped(error)
            }
        }
        task = nil
    }
}
