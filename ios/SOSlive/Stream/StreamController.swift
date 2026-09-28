import AVFoundation
import HaishinKit
import SwiftUI

/// Camera/microphone capture + RTMP publishing (HaishinKit). Hardware H.264/AAC encoding.
/// Status callbacks are delivered on the main thread.
final class StreamController: NSObject {
    enum Status {
        case connected
        case failed(String)
        case disconnected
    }

    var onStatus: ((Status) -> Void)?

    private let connection = RTMPConnection()
    let stream: RTMPStream
    private var streamKey: String?
    private(set) var position: AVCaptureDevice.Position = .back
    private(set) var isPublishing = false
    private var devicesAttached = false

    override init() {
        stream = RTMPStream(connection: connection)
        super.init()
        connection.addEventListener(.rtmpStatus, selector: #selector(rtmpStatusHandler(_:)), observer: self)
        connection.addEventListener(.ioError, selector: #selector(rtmpErrorHandler(_:)), observer: self)
    }

    deinit {
        connection.removeEventListener(.rtmpStatus, selector: #selector(rtmpStatusHandler(_:)), observer: self)
        connection.removeEventListener(.ioError, selector: #selector(rtmpErrorHandler(_:)), observer: self)
    }

    /// Starts camera + microphone capture (preview). Call after permissions were granted.
    func attachDevices() {
        guard !devicesAttached else { return }
        devicesAttached = true
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .videoRecording, options: [.defaultToSpeaker, .allowBluetooth])
        try? session.setActive(true)
        stream.attachAudio(AVCaptureDevice.default(for: .audio))
        attachCamera()
    }

    /// Releases the camera (e.g. while the system camera takes a photo).
    func detachCamera() {
        stream.attachCamera(nil)
        devicesAttached = false
    }

    func switchCamera() {
        position = position == .back ? .front : .back
        attachCamera()
    }

    /// Returns false when the current camera has no torch.
    func setTorch(_ on: Bool) -> Bool {
        guard position == .back, AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)?.hasTorch == true else {
            return false
        }
        stream.torch = on
        return true
    }

    /// Connects to `url` (rtmp://host:1935/app) and publishes `streamKey` once connected.
    func start(url: String, streamKey: String) {
        self.streamKey = streamKey
        isPublishing = true
        connection.connect(url)
    }

    func stop() {
        isPublishing = false
        streamKey = nil
        stream.close()
        connection.close()
    }

    private func attachCamera() {
        stream.attachCamera(AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position))
    }

    @objc private func rtmpStatusHandler(_ notification: Notification) {
        let event = HaishinKit.Event.from(notification)
        guard let data = event.data as? ASObject, let code = data["code"] as? String else { return }
        DispatchQueue.main.async { [weak self] in self?.handle(code: code) }
    }

    @objc private func rtmpErrorHandler(_ notification: Notification) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.isPublishing else { return }
            self.onStatus?(.failed("I/O error"))
        }
    }

    private func handle(code: String) {
        switch code {
        case RTMPConnection.Code.connectSuccess.rawValue:
            if let streamKey { stream.publish(streamKey) }
        case RTMPStream.Code.publishStart.rawValue:
            onStatus?(.connected)
        case RTMPConnection.Code.connectFailed.rawValue,
             RTMPConnection.Code.connectRejected.rawValue,
             RTMPStream.Code.publishBadName.rawValue:
            if isPublishing { onStatus?(.failed(code)) }
        case RTMPConnection.Code.connectClosed.rawValue:
            if isPublishing { onStatus?(.disconnected) }
        default:
            break
        }
    }
}

/// Live camera preview rendered by HaishinKit.
struct CameraPreview: UIViewRepresentable {
    let controller: StreamController

    func makeUIView(context: Context) -> MTHKView {
        let view = MTHKView(frame: .zero)
        view.videoGravity = .resizeAspectFill
        view.attachStream(controller.stream)
        return view
    }

    func updateUIView(_ uiView: MTHKView, context: Context) {}
}
