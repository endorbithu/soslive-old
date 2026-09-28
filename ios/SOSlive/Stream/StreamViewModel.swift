import AVFoundation
import Combine
import SOSliveCore
import UIKit

struct SmsRequest: Identifiable {
    let id = UUID()
    let recipients: [String]
    let body: String
}

struct PhotoRequest: Identifiable {
    let id = UUID()
    let eventId: Int64
}

/// The main screen: SOS / LIVE streaming, photo incidents, location reporting, SOS SMS and
/// comments. Mirrors the Android StreamViewModel.
@MainActor
final class StreamViewModel: ObservableObject {
    enum Phase { case idle, creatingEvent, connecting, live, stopping }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var liveEventId: Int64?
    @Published private(set) var activeEvent: ActiveEvent?
    @Published private(set) var comments: [Comment] = []
    @Published private(set) var unreadComments = 0
    @Published var commentsExpanded = false { didSet { if commentsExpanded { unreadComments = 0 } } }
    @Published var commentDraft = ""
    @Published private(set) var sendingComment = false
    @Published private(set) var photoBusy = false
    @Published private(set) var torchOn = false
    @Published private(set) var cameraAllowed = true
    @Published var toast: String?
    @Published var smsRequest: SmsRequest?
    @Published var photoRequest: PhotoRequest?

    let controller = StreamController()

    private let app: AppState
    private var events: EventService { app.events }
    private var publishTarget: StreamTarget?
    private var retries = 0
    private var locationSubscription: AnyCancellable?
    private var firstFixSubscription: AnyCancellable?
    private var commentsTask: Task<Void, Never>?

    var isStreamActive: Bool { phase == .connecting || phase == .live }

    init(app: AppState) {
        self.app = app
        controller.onStatus = { [weak self] status in self?.handle(status) }
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

    // MARK: - Streaming

    func startLive(_ type: EventType) {
        guard phase == .idle, type != .photo else { return }
        phase = .creatingEvent
        Task {
            do {
                let event = try await events.create(type: type, location: app.location.last)
                refreshActiveEvent()
                guard let target = event.stream else {
                    phase = .idle
                    show(L10n.tr("stream.no_target"))
                    return
                }
                publishTarget = target
                retries = 0
                liveEventId = event.id
                phase = .connecting
                controller.start(url: target.url, streamKey: target.streamKey)
                startLocationUpdates(eventId: event.id)
                // The SMS must not depend on the stream connecting.
                if type == .sos { requestSosSms(shareUrl: event.shareUrl) }
            } catch {
                phase = .idle
                show(L10n.error(error))
            }
        }
    }

    func stopLive() {
        guard isStreamActive, let eventId = liveEventId else { return }
        phase = .stopping
        publishTarget = nil
        locationSubscription = nil
        controller.stop()
        torchOn = false
        Task {
            _ = try? await events.stop(eventId)
            // The incident stays active (comments, extra photos) until it expires or "new incident".
            phase = .idle
            liveEventId = nil
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
        case .failed(let reason):
            connectionLost(reason)
        case .disconnected:
            if phase == .live { connectionLost("disconnected") }
        }
    }

    private func connectionLost(_ reason: String) {
        guard isStreamActive, let target = publishTarget else { return }
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
            guard phase == .connecting, publishTarget == target else { return }
            controller.start(url: target.url, streamKey: target.streamKey)
        }
    }

    private func startLocationUpdates(eventId: Int64) {
        locationSubscription = app.location.$last
            .compactMap { $0 }
            .throttle(for: .seconds(AppConfig.locationSendInterval), scheduler: RunLoop.main, latest: true)
            .sink { [weak self] point in
                guard let self else { return }
                Task { try? await self.events.sendLocation(eventId, point) }
            }
    }

    private func requestSosSms(shareUrl: String) {
        guard let user = app.user else { return }
        guard !user.sosContacts.isEmpty else {
            show(L10n.tr("sos.no_contacts"))
            return
        }
        guard MessageComposer.canSend else {
            show(L10n.tr("sos.sms_unavailable"))
            return
        }
        let body = SosMessage.build(template: user.sosMessage, fallback: L10n.tr("sos.default_message"), shareUrl: shareUrl)
        smsRequest = SmsRequest(recipients: user.sosContacts, body: body)
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
            if let active = app.events.activeEvents.current {
                presentCamera(eventId: active.id)
                return
            }
            do {
                let location = app.location.last
                let event = try await events.create(type: .photo, location: location)
                refreshActiveEvent()
                if location == nil { sendFirstFix(eventId: event.id) }
                presentCamera(eventId: event.id)
            } catch {
                show(L10n.error(error))
            }
        }
    }

    private func presentCamera(eventId: Int64) {
        controller.detachCamera() // the system camera needs the device
        photoRequest = PhotoRequest(eventId: eventId)
    }

    func photoFinished(eventId: Int64, image: UIImage?) {
        photoRequest = nil
        if cameraAllowed { controller.attachDevices() }
        guard let jpeg = image?.jpegData(compressionQuality: 0.85) else { return }
        photoBusy = true
        Task {
            defer { photoBusy = false }
            do {
                _ = try await events.uploadPhoto(eventId, jpeg: jpeg)
                show(L10n.tr("photo.uploaded"))
            } catch {
                show(L10n.error(error))
            }
        }
    }

    /// The legacy app reported a photo incident's location once, as soon as a fix arrived.
    private func sendFirstFix(eventId: Int64) {
        firstFixSubscription = app.location.$last
            .compactMap { $0 }
            .first()
            .timeout(.seconds(60), scheduler: RunLoop.main)
            .sink { [weak self] point in
                guard let self else { return }
                Task { try? await self.events.sendLocation(eventId, point) }
            }
    }

    // MARK: - Incident & comments

    func newIncident() {
        guard phase == .idle else { return }
        app.events.activeEvents.clear()
        refreshActiveEvent()
    }

    func refreshActiveEvent() {
        let current = app.events.activeEvents.current
        guard current?.id != activeEvent?.id else { return }
        activeEvent = current
        comments = []
        unreadComments = 0
        commentDraft = ""
        commentsTask?.cancel()
        if let current { startCommentPolling(eventId: current.id) }
    }

    func sendComment() {
        let text = commentDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let eventId = activeEvent?.id, !text.isEmpty, !sendingComment else { return }
        sendingComment = true
        Task {
            defer { sendingComment = false }
            do {
                _ = try await events.addComment(eventId, message: text)
                commentDraft = ""
                await refreshComments(eventId: eventId)
            } catch {
                show(L10n.error(error))
            }
        }
    }

    private func startCommentPolling(eventId: Int64) {
        commentsTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refreshComments(eventId: eventId)
                try? await Task.sleep(nanoseconds: AppConfig.commentPollInterval)
            }
        }
    }

    private func refreshComments(eventId: Int64) async {
        guard let page = try? await events.comments(eventId, sinceId: comments.last?.id),
              activeEvent?.id == eventId else { return }
        let known = Set(comments.map(\.id))
        let fresh = page.items.filter { !known.contains($0.id) }
        guard !fresh.isEmpty else { return }
        comments += fresh
        if !commentsExpanded { unreadComments += fresh.filter { !$0.fromOwner }.count }
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
