import AVFoundation
import HaishinKit
import RTMPHaishinKit
import SwiftUI

/// Camera/microphone capture + RTMP(S) publishing (HaishinKit 2). Hardware H.264/AAC encoding.
/// Lives on the main actor; status callbacks are delivered there too.
@MainActor
final class StreamController {
    enum Status {
        case connected
        case failed(String)
        case disconnected
    }

    var onStatus: ((Status) -> Void)?

    private let mixer = MediaMixer()
    private let connection = RTMPConnection()
    private let stream: RTMPStream
    private(set) var position: AVCaptureDevice.Position = .back
    private(set) var isPublishing = false
    private var devicesAttached = false
    private var mixerRunning = false
    /// Publishing succeeded for the current attempt (a later close means the connection dropped).
    private var live = false
    /// Incremented on every start/stop, so results of an earlier attempt are ignored.
    private var attempt = 0
    /// Mixer / connection operations run one after the other, in the order they were requested.
    private var queue: Task<Void, Never>?
    private var statusTask: Task<Void, Never>?

    init() {
        stream = RTMPStream(connection: connection)
        enqueue { [mixer, stream] in await mixer.addOutput(stream) }
        statusTask = Task { [weak self, connection] in
            for await status in await connection.status {
                self?.handle(code: status.code)
            }
        }
    }

    /// Starts camera + microphone capture (preview). Call after permissions were granted.
    func attachDevices() {
        guard !devicesAttached else { return }
        devicesAttached = true
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .videoRecording, options: [.defaultToSpeaker, .allowBluetooth])
        try? session.setActive(true)
        let camera = Self.camera(position)
        enqueue { [weak self, mixer] in
            try? await mixer.attachAudio(AVCaptureDevice.default(for: .audio))
            try? await mixer.attachVideo(camera)
            guard let self, !self.mixerRunning else { return }
            self.mixerRunning = true
            await mixer.startRunning()
        }
    }

    /// Releases the camera (e.g. while the system camera takes a photo).
    func detachCamera() {
        devicesAttached = false
        enqueue { [mixer] in try? await mixer.attachVideo(nil) }
    }

    func switchCamera() {
        position = position == .back ? .front : .back
        let camera = Self.camera(position)
        enqueue { [mixer] in try? await mixer.attachVideo(camera) }
    }

    /// Returns false when the current camera has no torch.
    func setTorch(_ on: Bool) -> Bool {
        guard position == .back, Self.camera(.back)?.hasTorch == true else { return false }
        enqueue { [mixer] in await mixer.setTorchEnabled(on) }
        return true
    }

    /// Connects to `url` (rtmp(s)://host/app) and publishes `streamKey`.
    func start(url: String, streamKey: String) {
        isPublishing = true
        live = false
        attempt += 1
        let current = attempt
        enqueue { [weak self, connection, stream] in
            do {
                _ = try await connection.connect(url)
                _ = try await stream.publish(streamKey)
                guard let self, self.isPublishing, self.attempt == current else { return }
                self.live = true
                self.onStatus?(.connected)
            } catch {
                guard let self, self.isPublishing, self.attempt == current else { return }
                self.onStatus?(.failed(String(describing: error)))
            }
        }
    }

    func stop() {
        isPublishing = false
        live = false
        attempt += 1
        enqueue { [connection, stream] in
            _ = try? await stream.close()
            try? await connection.close()
        }
    }

    /// The preview view receives the camera image from the mixer.
    func addPreview(_ view: MTHKView) {
        enqueue { [mixer] in await mixer.addOutput(view) }
    }

    func removePreview(_ view: MTHKView) {
        enqueue { [mixer] in await mixer.removeOutput(view) }
    }

    private func enqueue(_ operation: @escaping @MainActor () async -> Void) {
        let previous = queue
        queue = Task {
            await previous?.value
            await operation()
        }
    }

    private func handle(code: String) {
        switch code {
        case RTMPConnection.Code.connectClosed.rawValue,
             RTMPConnection.Code.connectIdleTimeOut.rawValue,
             RTMPConnection.Code.connectAppshutdown.rawValue:
            guard isPublishing, live else { return }
            live = false
            onStatus?(.disconnected)
        default:
            break
        }
    }

    private static func camera(_ position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
    }
}

/// Live camera preview rendered by HaishinKit.
struct CameraPreview: UIViewRepresentable {
    let controller: StreamController

    func makeCoordinator() -> StreamController { controller }

    func makeUIView(context: Context) -> MTHKView {
        let view = MTHKView(frame: .zero)
        view.videoGravity = .resizeAspectFill
        controller.addPreview(view)
        return view
    }

    func updateUIView(_ uiView: MTHKView, context: Context) {}

    static func dismantleUIView(_ uiView: MTHKView, coordinator: StreamController) {
        coordinator.removePreview(uiView)
    }
}
