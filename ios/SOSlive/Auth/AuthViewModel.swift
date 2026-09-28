import Foundation
import SOSliveCore

/// Login / register. Success only stores the session - RootView switches screens by itself.
@MainActor
final class AuthViewModel: ObservableObject {
    enum Field { case email, password, name }

    static let minPassword = 8

    @Published var email = ""
    @Published var password = ""
    @Published var displayName = ""
    @Published private(set) var loading = false
    @Published var error: String?
    @Published private(set) var fieldErrors: [Field: String] = [:]

    private let auth: AuthServicing

    init(auth: AuthServicing) {
        self.auth = auth
    }

    func login() {
        var errors: [Field: String] = [:]
        if email.trimmingCharacters(in: .whitespaces).isEmpty { errors[.email] = L10n.tr("error.email_required") }
        if password.isEmpty { errors[.password] = L10n.tr("error.password_required") }
        fieldErrors = errors
        guard errors.isEmpty else { return }
        let (email, password) = (email, password)
        submit { try await $0.login(email: email, password: password) }
    }

    func register() {
        var errors: [Field: String] = [:]
        if displayName.trimmingCharacters(in: .whitespaces).isEmpty { errors[.name] = L10n.tr("error.name_required") }
        if !Email.isValid(email) { errors[.email] = L10n.tr("error.email_invalid") }
        if password.count < Self.minPassword { errors[.password] = L10n.tr("error.password_short", Self.minPassword) }
        fieldErrors = errors
        guard errors.isEmpty else { return }
        let (email, password, name) = (email, password, displayName)
        submit { try await $0.register(email: email, password: password, displayName: name) }
    }

    func handle(_ provider: SsoProvider, _ result: SsoResult) {
        switch result {
        case .success(let token):
            submit { auth in
                switch provider {
                case .google: return try await auth.loginWithGoogle(idToken: token)
                case .facebook: return try await auth.loginWithFacebook(accessToken: token)
                }
            }
        case .failure(let message):
            error = L10n.tr("error.sso_failed", message)
        case .cancelled:
            break
        }
    }

    private func submit(_ operation: @escaping (AuthServicing) async throws -> User) {
        guard !loading else { return }
        loading = true
        error = nil
        Task {
            do {
                _ = try await operation(auth)
                password = ""
            } catch {
                self.error = L10n.error(error)
            }
            loading = false
        }
    }
}
