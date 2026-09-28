import Foundation
#if canImport(Security)
import Security
#endif

/// Persists the logged in session (tokens + user).
public protocol SessionStore: AnyObject {
    func load() -> Session?
    func save(_ session: Session?)
}

public final class InMemorySessionStore: SessionStore {
    private let lock = NSLock()
    private var session: Session?

    public init(_ session: Session? = nil) { self.session = session }

    public func load() -> Session? {
        lock.lock(); defer { lock.unlock() }
        return session
    }

    public func save(_ session: Session?) {
        lock.lock(); defer { lock.unlock() }
        self.session = session
    }
}

#if canImport(Security)
/// Stores the session JSON in the Keychain (this device only).
public final class KeychainSessionStore: SessionStore {
    private let service: String
    private let account = "session"
    private let lock = NSLock()
    private var cache: Session??

    public init(service: String = "info.soslive.stream") {
        self.service = service
    }

    public func load() -> Session? {
        lock.lock(); defer { lock.unlock() }
        if let cached = cache { return cached }
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        let session = (status == errSecSuccess ? result as? Data : nil).flatMap { try? JSONDecoder().decode(Session.self, from: $0) }
        cache = .some(session)
        return session
    }

    public func save(_ session: Session?) {
        lock.lock(); defer { lock.unlock() }
        cache = .some(session)
        SecItemDelete(baseQuery as CFDictionary)
        guard let session, let data = try? JSONEncoder().encode(session) else { return }
        var attributes = baseQuery
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(attributes as CFDictionary, nil)
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
#endif
