import Foundation

/// The user's own streaming service (YouTube Live, Facebook Live, Twitch, Cloudflare Stream,
/// a self-hosted MediaMTX, ...). Entered in the app's settings and kept only on the phone
/// (the stream key in the Keychain).
public struct StreamSettings: Equatable, Codable, Sendable {
    /// RTMP(S) ingest URL, e.g. rtmp://a.rtmp.youtube.com/live2
    public var rtmpURL: String
    /// Stream key (secret). Empty when the URL already contains it as its last path component.
    public var streamKey: String
    /// Directly playable URL (HLS .m3u8 / .mp4) -> event "stream" field. Optional.
    public var playbackURL: String
    /// Viewer page (e.g. the YouTube / Twitch channel page) -> event "stream_page". Optional.
    public var pageURL: String
    /// Where the recording can be downloaded / watched later -> event "recording". Optional.
    public var recordingURL: String

    /// Optional placeholder in `recordingURL`: the event start time (ISO 8601 UTC).
    public static let startPlaceholder = "{start}"

    public enum Field: Hashable, CaseIterable, Sendable { case rtmpURL, playbackURL, pageURL, recordingURL }

    public init(rtmpURL: String = "", streamKey: String = "", playbackURL: String = "", pageURL: String = "", recordingURL: String = "") {
        self.rtmpURL = rtmpURL
        self.streamKey = streamKey
        self.playbackURL = playbackURL
        self.pageURL = pageURL
        self.recordingURL = recordingURL
    }

    public var isConfigured: Bool { !rtmpURL.trimmed.isEmpty }

    public func validate() -> Set<Field> {
        var errors = Set<Field>()
        let rtmp = rtmpURL.trimmed
        if rtmp.isEmpty {
            if ![streamKey, playbackURL, pageURL, recordingURL].allSatisfy({ $0.trimmed.isEmpty }) { errors.insert(.rtmpURL) }
        } else if !Self.matches(rtmp, #"^rtmps?://\S+$"#) {
            errors.insert(.rtmpURL)
        }
        if !Self.isHTTPOrEmpty(playbackURL) { errors.insert(.playbackURL) }
        if !Self.isHTTPOrEmpty(pageURL) { errors.insert(.pageURL) }
        if !Self.isHTTPOrEmpty(recordingURL.replacingOccurrences(of: Self.startPlaceholder, with: "x")) { errors.insert(.recordingURL) }
        return errors
    }

    private static func isHTTPOrEmpty(_ value: String) -> Bool {
        value.trimmed.isEmpty || matches(value.trimmed, #"^https?://\S+$"#)
    }

    private static func matches(_ value: String, _ pattern: String) -> Bool {
        value.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil
    }
}

/// Where the app publishes and which URLs go into the event file.
public struct StreamSession: Equatable, Sendable {
    /// RTMP application URL (connect), e.g. rtmp://a.rtmp.youtube.com/live2
    public var rtmpURL: String
    /// Stream name to publish (the stream key).
    public var streamKey: String
    /// -> "stream" (directly playable), may be empty
    public var playbackURL: String
    /// -> "stream_page", may be empty
    public var pageURL: String
    /// -> "recording", may be empty
    public var recordingURL: String

    public init(rtmpURL: String, streamKey: String, playbackURL: String = "", pageURL: String = "", recordingURL: String = "") {
        self.rtmpURL = rtmpURL
        self.streamKey = streamKey
        self.playbackURL = playbackURL
        self.pageURL = pageURL
        self.recordingURL = recordingURL
    }

    public var publishURL: String { streamKey.isEmpty ? rtmpURL : "\(rtmpURL)/\(streamKey)" }
}

/// Source of the stream target; nil when the user has not set up streaming.
public protocol StreamProvider {
    func createStream(start: Date) async throws -> StreamSession?
}

/// Uses the user's own streaming settings as they are - no SOSlive server in between.
public struct UserStreamProvider: StreamProvider {
    private let settings: () -> StreamSettings

    public init(settings: @escaping () -> StreamSettings) {
        self.settings = settings
    }

    public func createStream(start: Date) async throws -> StreamSession? {
        let s = settings()
        guard s.isConfigured else { return nil }
        var url = s.rtmpURL.trimmed
        while url.hasSuffix("/") { url.removeLast() }
        var key = s.streamKey.trimmed
        if key.isEmpty, let slash = url.lastIndex(of: "/"), url[..<slash].filter({ $0 == "/" }).count >= 3 {
            // rtmp://host/app/key -> connect to rtmp://host/app, publish "key"
            key = String(url[url.index(after: slash)...])
            url = String(url[..<slash])
        }
        return StreamSession(
            rtmpURL: url,
            streamKey: key,
            playbackURL: s.playbackURL.trimmed,
            pageURL: s.pageURL.trimmed,
            recordingURL: s.recordingURL.trimmed.replacingOccurrences(of: StreamSettings.startPlaceholder, with: isoUTC(start))
        )
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
