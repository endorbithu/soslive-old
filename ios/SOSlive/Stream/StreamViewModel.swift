import AVFoundation
import Combine
import SOSliveCore
import UIKit

/// A pre-filled composer to show (iOS never sends SMS / e-mail silently).
enum Composer: Identifiable {
    case sms(recipients: [String], body: String)
    case mail(recipients: [String], subject: String, body: String)

    var id: String {
        switch self {
        case .sms: return "sms"
        case .mail: return "mail"
        }
    }
}

struct PhotoRequest: Identifiable {
    let id = UUID()
    let eventFileId: String
}

/// Main screen: SOS / LIVE streaming, photo incidents and messages - all written to the event's
/// JSON file on the user's Google Drive. No SOSlive backend is called.
@MainActor
final class StreamViewModel: ObservableObject {
    enum Phase { case idle, creatingEvent, connecting, live, stopping }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var activeEvent: ActiveEvent?
    /// The owner's own messages in the open incident.
    @Published private(set) var messages: [EventEntry] = []
    @Published var messagesExpanded = false
    @Published var messageDraft = ""
    @Published private(set) var sendingMessage = false
    @Published private(set) var photoBusy = false
    @Published private(set) var torchOn = false
    @Published private(set) var cameraAllowed = true
    @Published var toast: String?
    @Published var composer: Composer?
    @Published var photoRequest: PhotoRequest?

    let controller = StreamController()

    private let app: AppState
    private var drive: SosliveDrive { app.drive }
    private var writer: EventWriter?
    private var session: StreamSession?
    private var retries = 0
    private var pendingComposers: [Composer] = []
    private var locationSubscription: AnyCancellable?
    private var firstFixSubscription: AnyCancellable?

    var isStreamActive: Bool { phase == .connecting || phase == .live }

    init(app: AppState) {
        self.app = app
        controller.onStatus = { [weak self] status in self?.handle(status) }
        activeEvent = app.accounts.activeEvent
    }

    // MARK: - Lifecycle

    func onAppear() async {
        refreshActiveEvent()
        app.location.start()
        let video = await AVCaptureDevice.requestAccess(for: .video)
        let audio = await AVCaptureDevice.requestAccess(for: .audio)
        cameraAllowed = video && audio
        if cameraAllowed { controller.attachDevices() }
    }

    /// iOS suspends the camera in the background, so a running stream ends there.
    func onBackground() {
        if isStreamActive { stopLive() }
    }

    func refreshActiveEvent() {
        let current = app.accounts.activeEvent
        if current?.fileId != activeEvent?.fileId { messages = [] }
        activeEvent = current
    }

    // MARK: - Streaming

    func startLive(_ kind: EventKind) {
        guard phase == .idle, kind != .photo else { return }
        phase = .creatingEvent
        Task {
            do {
                let start = Date()
                // The user's own streaming service from the settings (device only); nil = not set up.
                let stream = try await app.streamProvider.createStream(start: start)
                if stream == nil && kind == .live {
                    phase = .idle
                    show(L10n.tr("stream.not_configured"))
                    return
                }
                let location = app.location.last
                var document = EventDocument(
                    stream: stream?.playbackURL ?? "",
                    streamPage: stream?.pageURL ?? "",
                    recording: stream?.recordingURL ?? ""
                )
                if let location { document.entries.append(.position(start, lat: location.lat, lng: location.lng)) }
                let created = try await drive.createEvent(start: start, document: document)
                if !created.shared { show(L10n.tr("share.failed", created.shareError ?? "")) }
                let eventWriter = openWriter(fileId: created.fileId, document: document)
                setActive(ActiveEvent(fileId: created.fileId, kind: kind, title: eventTitle(fileName: created.name), link: created.link, startedAt: start))

                if let stream {
                    session = stream
                    retries = 0
                    phase = .connecting
                    controller.start(url: stream.rtmpURL, streamKey: stream.streamKey)
                    startLocationUpdates(eventWriter)
                } else {
                    // SOS without streaming: the event, the position and the notification still go out.
                    phase = .idle
                    show(L10n.tr("stream.not_configured_sos"))
                    if location == nil { sendFirstFix(eventWriter) }
                }
                if kind == .sos { await notifyContacts(link: created.link) }
                rotateInBackground()
            } catch {
                phase = .idle
                show(L10n.error(error))
            }
        }
    }

    func stopLive() {
        guard isStreamActive else { return }
        phase = .stopping
        session = nil
        locationSubscription = nil
        controller.stop()
        torchOn = false
        Task {
            // Final full upload; the file and the link stay.
            await writer?.close()
            phase = .idle
            show(L10n.tr("stream.stopped"))
        }
    }

    private func handle(_ status: StreamController.Status) {
        switch status {
        case .connected:
            retries = 0
            if phase == .connecting {
                phase = .live
                show(L10n.tr("stream.started"))
            }
        case let .failed(reason):
            connectionLost(reason)
        case .disconnected:
            if phase == .live { connectionLost("disconnected") }
        }
    }

    private func connectionLost(_ reason: String) {
        guard isStreamActive, let target = session else { return }
        guard retries < AppConfig.streamMaxRetries else {
            show(L10n.tr("stream.failed", reason))
            stopLive()
            return
        }
        retries += 1
        phase = .connecting
        show(L10n.tr("stream.reconnecting", retries, AppConfig.streamMaxRetries))
        controller.stop()
        Task {
            try? await Task.sleep(nanoseconds: AppConfig.streamRetryDelay)
            guard phase == .connecting, session == target else { return }
            controller.start(url: target.rtmpURL, streamKey: target.streamKey)
        }
    }

    /// Position entries; EventWriter keeps them at most every 30 s.
    private func startLocationUpdates(_ eventWriter: EventWriter) {
        locationSubscription = app.location.$last
            .compactMap { $0 }
            .sink { point in Task { await eventWriter.addPosition(lat: point.lat, lng: point.lng) } }
    }

    // MARK: - Notifications

    private func config() async -> SosConfig {
        if let cached = drive.cachedConfig { return cached }
        return (try? await drive.readRemoteConfig()) ?? SosConfig()
    }

    /// The link to the config.json contacts via pre-filled SMS and e-mail composers.
    private func notifyContacts(link: String) async {
        let config = await config()
        let text = L10n.tr("notify.text", link)
        var queue: [Composer] = []
        if !config.notificationPhones.isEmpty {
            if MessageComposer.canSend {
                queue.append(.sms(recipients: config.notificationPhones.map(ContactRules.dialable), body: text))
            } else {
                show(L10n.tr("notify.sms_unavailable"))
            }
        }
        if !config.notificationEmails.isEmpty, MailComposer.canSend {
            queue.append(.mail(recipients: config.notificationEmails, subject: L10n.tr("notify.subject"), body: text))
        }
        if config.notificationPhones.isEmpty && config.notificationEmails.isEmpty {
            show(L10n.tr("notify.no_contacts"))
            return
        }
        pendingComposers = queue
        showNextComposer()
        show(L10n.tr("notify.mute_tip"))
    }

    /// Called when a composer was dismissed: shows the next one (SMS first, then e-mail).
    func composerFinished() {
        composer = nil
        Task {
            try? await Task.sleep(nanoseconds: 600_000_000) // let the sheet finish dismissing
            showNextComposer()
        }
    }

    private func showNextComposer() {
        guard composer == nil, !pendingComposers.isEmpty else { return }
        composer = pendingComposers.removeFirst()
    }

    private func rotateInBackground() {
        Task {
            let config = await config()
            _ = try? await drive.rotate(maxEvents: config.maxEvents)
        }
    }

    // MARK: - Incident file

    private func setActive(_ event: ActiveEvent?) {
        app.accounts.activeEvent = event
        activeEvent = event
    }

    private func openWriter(fileId: String, document: EventDocument) -> EventWriter {
        let eventWriter = EventWriter(drive: drive.api, fileId: fileId, initial: document) { [weak self] error in
            Task { @MainActor in self?.writerStopped(fileId: fileId, error: error) }
        }
        writer = eventWriter
        messages = document.entries.filter { $0.type == "msg" }
        return eventWriter
    }

    private func writerStopped(fileId: String, error: Error) {
        show(L10n.error(error))
        if case .notFound? = error as? DriveError, activeEvent?.fileId == fileId { setActive(nil) }
    }

    /// Writer for the open incident; after an app restart the document is read back from Drive.
    private func writerFor(_ active: ActiveEvent) async -> EventWriter? {
        if let writer, writer.fileId == active.fileId, await !writer.stopped { return writer }
        do {
            return openWriter(fileId: active.fileId, document: try await drive.readEvent(fileId: active.fileId))
        } catch {
            if case .notFound? = error as? DriveError { setActive(nil) }
            show(L10n.error(error))
            return nil
        }
    }

    // MARK: - Camera controls

    func switchCamera() {
        controller.switchCamera()
        torchOn = false
    }

    func toggleTorch() {
        if controller.setTorch(!torchOn) {
            torchOn.toggle()
        } else {
            show(L10n.tr("flash.unavailable"))
        }
    }

    // MARK: - Photos

    /// Adds a photo to the open incident, or opens a new PHOTO incident first.
    func takePhoto() {
        guard phase == .idle, !photoBusy else { return }
        photoBusy = true
        Task {
            defer { photoBusy = false }
            if let active = activeEvent, await writerFor(active) != nil {
                presentCamera(eventFileId: active.fileId)
                return
            }
            do {
                let start = Date()
                let location = app.location.last
                var document = EventDocument()
                if let location { document.entries.append(.position(start, lat: location.lat, lng: location.lng)) }
                let created = try await drive.createEvent(start: start, document: document)
                if !created.shared { show(L10n.tr("share.failed", created.shareError ?? "")) }
                let eventWriter = openWriter(fileId: created.fileId, document: document)
                setActive(ActiveEvent(fileId: created.fileId, kind: .photo, title: eventTitle(fileName: created.name), link: created.link, startedAt: start))
                if location == nil { sendFirstFix(eventWriter) }
                rotateInBackground()
                presentCamera(eventFileId: created.fileId)
            } catch {
                show(L10n.error(error))
            }
        }
    }

    private func presentCamera(eventFileId: String) {
        controller.detachCamera() // the system camera needs the device
        photoRequest = PhotoRequest(eventFileId: eventFileId)
    }

    func photoFinished(eventFileId: String, image: UIImage?) {
        photoRequest = nil
        if cameraAllowed { controller.attachDevices() }
        guard let jpeg = image?.jpegData(compressionQuality: 0.85) else { return }
        photoBusy = true
        Task {
            defer { photoBusy = false }
            guard let active = activeEvent, active.fileId == eventFileId, let eventWriter = await writerFor(active) else {
                show(L10n.tr("error.drive_missing"))
                return
            }
            do {
                let url = try await drive.uploadImage(jpeg: jpeg)
                await eventWriter.addImage(url: url)
                show(L10n.tr("photo.uploaded"))
            } catch {
                show(L10n.error(error))
            }
        }
    }

    /// A photo incident without a fix yet gets its first position as soon as one arrives.
    private func sendFirstFix(_ eventWriter: EventWriter) {
        firstFixSubscription = app.location.$last
            .compactMap { $0 }
            .first()
            .timeout(.seconds(60), scheduler: RunLoop.main)
            .sink { point in Task { await eventWriter.addPosition(lat: point.lat, lng: point.lng, force: true) } }
    }

    // MARK: - Messages

    /// The owner's own message on the event page ("name" is never an e-mail / phone number).
    func sendMessage() {
        let text = messageDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let active = activeEvent, !text.isEmpty, !sendingMessage else { return }
        sendingMessage = true
        Task {
            defer { sendingMessage = false }
            guard let eventWriter = await writerFor(active) else { return }
            let fullName = app.account?.name ?? ""
            let name = fullName.contains("@") || fullName.isEmpty ? "SOSlive" : String(fullName.split(separator: " ").first ?? "SOSlive")
            await eventWriter.addMessage(name: name, text: text)
            messageDraft = ""
            messages = await eventWriter.current.entries.filter { $0.type == "msg" }
        }
    }

    /// "New incident": closes the open one so the next photo starts a new event file.
    func newIncident() {
        guard phase == .idle else { return }
        let closing = writer
        writer = nil
        setActive(nil)
        messages = []
        Task { await closing?.close() }
    }

    // MARK: - Toast

    private func show(_ message: String) {
        toast = message
        Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if toast == message { toast = nil }
        }
    }
}
