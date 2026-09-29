import Foundation
import Security
import SOSliveCore

/// The user's streaming settings in the Keychain, only on this device (not synced, not backed
/// up to another device, never sent to Drive or the web).
final class StreamSettingsStore {
    private let service = "info.soslive.stream.streaming"
    private let accountName = "settings"

    func load() -> StreamSettings {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else {
            return StreamSettings()
        }
        return (try? JSONDecoder().decode(StreamSettings.self, from: data)) ?? StreamSettings()
    }

    @discardableResult
    func save(_ settings: StreamSettings) -> Bool {
        guard let data = try? JSONEncoder().encode(settings) else { return false }
        SecItemDelete(baseQuery as CFDictionary)
        var item = baseQuery
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    func clear() {
        SecItemDelete(baseQuery as CFDictionary)
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountName,
        ]
    }
}
