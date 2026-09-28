import FacebookLogin
import GoogleSignIn
import UIKit

enum SsoProvider: String, Identifiable {
    case google, facebook

    var id: String { rawValue }
    var title: String { self == .google ? "Google" : "Facebook" }

    /// No client id configured -> the provider's account picker is replaced by a simple dialog.
    var isSimulated: Bool {
        switch self {
        case .google: return !AppConfig.googleConfigured
        case .facebook: return !AppConfig.facebookConfigured
        }
    }
}

enum SsoResult {
    /// Google ID token / Facebook access token / "mock:..." when simulated.
    case success(String)
    case cancelled
    case failure(String)
}

@MainActor
enum SSO {
    private static let facebookManager = LoginManager()

    static func googleIdToken() async -> SsoResult {
        guard let presenter = UIApplication.topViewController else { return .failure("No window") }
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
            guard let token = result.user.idToken?.tokenString else { return .failure("Google returned no ID token") }
            return .success(token)
        } catch {
            if (error as? GIDSignInError)?.code == .canceled { return .cancelled }
            return .failure(error.localizedDescription)
        }
    }

    static func facebookAccessToken() async -> SsoResult {
        guard let presenter = UIApplication.topViewController else { return .failure("No window") }
        return await withCheckedContinuation { continuation in
            facebookManager.logIn(permissions: ["public_profile", "email"], from: presenter) { result, error in
                if let error {
                    continuation.resume(returning: .failure(error.localizedDescription))
                } else if let result, !result.isCancelled, let token = result.token?.tokenString {
                    continuation.resume(returning: .success(token))
                } else {
                    continuation.resume(returning: .cancelled)
                }
            }
        }
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
