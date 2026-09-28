import Foundation
import SOSliveCore

enum L10n {
    /// Localized string for `key` (Localizable.strings), formatted with `args`.
    static func tr(_ key: String, _ args: CVarArg...) -> String {
        let format = NSLocalizedString(key, comment: "")
        return args.isEmpty ? format : String(format: format, locale: Locale.current, arguments: args)
    }

    /// User facing text for a failure: known backend error codes are localized.
    static func error(_ error: Error) -> String {
        guard let apiError = error as? APIError else { return tr("error.generic") }
        switch apiError.code {
        case APIError.networkCode: return tr("error.network")
        case "invalid_credentials": return tr("error.invalid_credentials")
        case "too_many_attempts": return tr("error.too_many_attempts")
        case "email_taken": return tr("error.email_taken")
        case "invalid_sso_token": return tr("error.sso_rejected")
        default: return apiError.message
        }
    }
}

extension Date {
    var shortDateTime: String { formatted(date: .abbreviated, time: .shortened) }
}
