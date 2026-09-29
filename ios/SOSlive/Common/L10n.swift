import Foundation
import SOSliveCore

enum L10n {
    /// Localized string for `key` (Localizable.strings), formatted with `args`.
    static func tr(_ key: String, _ args: CVarArg...) -> String {
        let format = NSLocalizedString(key, comment: "")
        return args.isEmpty ? format : String(format: format, locale: Locale.current, arguments: args)
    }

    /// User facing text for a failure.
    static func error(_ error: Error) -> String {
        switch error as? DriveError {
        case .consentRequired?: return tr("error.drive_permission")
        case .notFound?: return tr("error.drive_missing")
        case .network?: return tr("error.network")
        case let .http(status, message)?: return tr("error.drive", "\(status) \(message)")
        case .parse?, nil: return tr("error.generic")
        }
    }
}

extension Date {
    var shortDateTime: String { formatted(date: .abbreviated, time: .shortened) }
}
