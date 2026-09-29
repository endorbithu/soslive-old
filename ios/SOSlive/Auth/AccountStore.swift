import Foundation

/// The signed in Google user (only name + e-mail, no tokens).
struct UserAccount: Codable, Equatable {
    var email: String
    var name: String
    /// Development mode without Google: files are stored locally.
    var simulated: Bool
}

enum EventKind: String, Codable {
    case sos, live, photo
}

/// The incident the user is working on (2 h window for extra photos / messages).
struct ActiveEvent: Codable, Equatable {
    var fileId: String
    var kind: EventKind
    var title: String
    var link: String
    var startedAt: Date

    func isExpired(now: Date = Date()) -> Bool { now.timeIntervalSince(startedAt) >= AppConfig.activeEventWindow }
}

/// Account and open incident in UserDefaults.
final class AccountStore {
    private let defaults: UserDefaults
    private let accountKey = "soslive.account"
    private let activeKey = "soslive.activeEvent"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var account: UserAccount? {
        get { defaults.data(forKey: accountKey).flatMap { try? JSONDecoder().decode(UserAccount.self, from: $0) } }
        set { defaults.set(newValue.flatMap { try? JSONEncoder().encode($0) }, forKey: accountKey) }
    }

    /// The open incident, nil when none or expired.
    var activeEvent: ActiveEvent? {
        get {
            defaults.data(forKey: activeKey)
                .flatMap { try? JSONDecoder().decode(ActiveEvent.self, from: $0) }
                .flatMap { $0.isExpired() ? nil : $0 }
        }
        set { defaults.set(newValue.flatMap { try? JSONEncoder().encode($0) }, forKey: activeKey) }
    }
}
