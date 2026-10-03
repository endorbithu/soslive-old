import Foundation

/// Build configuration (Config/Base.xcconfig -> Info.plist). There is no SOSlive backend.
enum AppConfig {
    private static func value(_ key: String) -> String {
        ((Bundle.main.object(forInfoDictionaryKey: key) as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static let webappURL = value("SOSliveWebappURL").isEmpty ? "https://soslive.example" : value("SOSliveWebappURL")
    static let googleClientID = value("SOSliveGoogleClientID")

    /// Without an iOS client id the app keeps its "Drive" files locally (development only).
    static var googleConfigured: Bool { !googleClientID.isEmpty }

    static let driveFileScope = "https://www.googleapis.com/auth/drive.file"

    /// A photo incident stays open this long for extra photos / messages.
    static let activeEventWindow: TimeInterval = 2 * 60 * 60
    static let streamMaxRetries = 3
    static let streamRetryDelay: UInt64 = 3_000_000_000
}
