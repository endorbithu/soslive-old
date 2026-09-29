import GoogleSignIn
import SOSliveCore
import UIKit

enum SignInOutcome {
    case success(UserAccount)
    case cancelled
    /// Signed in, but drive.file was not granted.
    case driveDenied
    case failure(String)
}

/// Google Sign-In with the drive.file scope. The token stays in the SDK's keychain storage and
/// is only sent to Google - never to a SOSlive backend.
final class GoogleAuth: AccessTokenProvider {
    private static let scopes = [AppConfig.driveFileScope]

    init() {
        if AppConfig.googleConfigured {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: AppConfig.googleClientID)
        }
    }

    private static func account(_ user: GIDGoogleUser) -> UserAccount {
        let email = user.profile?.email ?? ""
        return UserAccount(email: email, name: user.profile?.name ?? email, simulated: false)
    }

    private static func hasDrive(_ user: GIDGoogleUser) -> Bool {
        user.grantedScopes?.contains(AppConfig.driveFileScope) == true
    }

    /// Account picker + consent for drive.file.
    @MainActor
    func signIn() async -> SignInOutcome {
        guard let presenter = UIApplication.topViewController else { return .failure("No window") }
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter, hint: nil, additionalScopes: Self.scopes)
            return Self.hasDrive(result.user) ? .success(Self.account(result.user)) : .driveDenied
        } catch {
            if (error as? GIDSignInError)?.code == .canceled { return .cancelled }
            return .failure(error.localizedDescription)
        }
    }

    /// Asks again for drive.file after the user refused it.
    @MainActor
    func requestDrive() async -> SignInOutcome {
        guard let user = GIDSignIn.sharedInstance.currentUser else { return await signIn() }
        guard let presenter = UIApplication.topViewController else { return .failure("No window") }
        do {
            let result = try await user.addScopes(Self.scopes, presenting: presenter)
            return Self.hasDrive(result.user) ? .success(Self.account(result.user)) : .driveDenied
        } catch {
            if (error as? GIDSignInError)?.code == .canceled { return .driveDenied }
            return .failure(error.localizedDescription)
        }
    }

    /// Restores the previous Google session at launch (nil when there is none).
    func restore() async -> UserAccount? {
        guard AppConfig.googleConfigured, let user = try? await GIDSignIn.sharedInstance.restorePreviousSignIn() else { return nil }
        return Self.hasDrive(user) ? Self.account(user) : nil
    }

    /// Fresh access token (the SDK refreshes it when it is about to expire).
    func accessToken(forceRefresh: Bool) async throws -> String {
        guard let user = GIDSignIn.sharedInstance.currentUser, Self.hasDrive(user) else { throw DriveError.consentRequired }
        return try await user.refreshTokensIfNeeded().accessToken.tokenString
    }

    func signOut() {
        GIDSignIn.sharedInstance.signOut()
    }
}

extension UIApplication {
    static var topViewController: UIViewController? {
        let scene = shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var controller = scene?.windows.first(where: \.isKeyWindow)?.rootViewController ?? scene?.windows.first?.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
    }
}
