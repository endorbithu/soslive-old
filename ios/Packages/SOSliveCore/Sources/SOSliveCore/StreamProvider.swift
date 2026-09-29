import Foundation

/// Where the app publishes (RTMP) and where viewers play the stream (the event's "stream" field).
public struct StreamSession: Equatable {
    /// RTMP application URL, e.g. rtmp://host:1935/live
    public var rtmpURL: String
    public var streamKey: String
    /// HLS (.m3u8) playback URL; empty when the provider does not know it yet.
    public var playbackURL: String

    public var publishURL: String { "\(rtmpURL)/\(streamKey)" }
}

/// Source of stream targets. Replaceable: a hosted provider (Mux, Cloudflare Stream, ...) can
/// implement this later - its API key must then live on a server, not in the app.
public protocol StreamProvider {
    func createStream() async throws -> StreamSession
}

/// Stream targets from build configuration: a random, unguessable stream key per event,
/// `<rtmpURL>/<key>` to publish and `hlsTemplate` with `{key}` replaced to play.
public struct TemplateStreamProvider: StreamProvider {
    public var rtmpURL: String
    public var hlsTemplate: String

    public init(rtmpURL: String, hlsTemplate: String) {
        self.rtmpURL = rtmpURL.hasSuffix("/") ? String(rtmpURL.dropLast()) : rtmpURL
        self.hlsTemplate = hlsTemplate
    }

    public func createStream() async throws -> StreamSession {
        let key = Self.newKey()
        return StreamSession(rtmpURL: rtmpURL, streamKey: key, playbackURL: hlsTemplate.replacingOccurrences(of: "{key}", with: key))
    }

    /// 128 bit, lowercase hex (SystemRandomNumberGenerator is cryptographically secure).
    public static func newKey() -> String {
        var generator = SystemRandomNumberGenerator()
        return (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255, using: &generator)) }.joined()
    }
}
