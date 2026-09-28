import Foundation

/// Build configuration (Config/Base.xcconfig -> Info.plist).
enum AppConfig {
    private static func value(_ key: String) -> String {
        ((Bundle.main.object(forInfoDictionaryKey: key) as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static let apiBaseURL: URL = {
        var raw = value("SOSliveAPIBaseURL")
        if raw.isEmpty { raw = "http://localhost:3000/" }
        if !raw.hasSuffix("/") { raw += "/" }
        return URL(string: raw) ?? URL(string: "http://localhost:3000/")!
    }()

    static let googleClientID = value("SOSliveGoogleClientID")
    static let googleServerClientID = value("SOSliveGoogleServerClientID")
    static let facebookAppID = value("SOSliveFacebookAppID")
    static let facebookClientToken = value("SOSliveFacebookClientToken")

    /// Without an iOS client id, Google sign-in is simulated (mock token to the backend).
    static var googleConfigured: Bool { !googleClientID.isEmpty }
    /// Without app id + client token, Facebook sign-in is simulated.
    static var facebookConfigured: Bool { !facebookAppID.isEmpty && !facebookClientToken.isEmpty }

    static let commentPollInterval: UInt64 = 10_000_000_000
    static let locationSendInterval: TimeInterval = 30
    static let streamMaxRetries = 3
    static let streamRetryDelay: UInt64 = 3_000_000_000
}
