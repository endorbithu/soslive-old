import FacebookCore
import GoogleSignIn
import SwiftUI

@main
struct SOSliveApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
                .tint(.sosRed)
                .onOpenURL { url in
                    // OAuth redirects back into the app (Google / Facebook).
                    if AppConfig.googleConfigured, GIDSignIn.sharedInstance.handle(url) { return }
                    if AppConfig.facebookConfigured {
                        _ = ApplicationDelegate.shared.application(
                            UIApplication.shared, open: url, sourceApplication: nil,
                            annotation: [UIApplication.OpenURLOptionsKey.annotation]
                        )
                    }
                }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // The SDKs are only touched when configured - otherwise sign-in is simulated.
        if AppConfig.facebookConfigured {
            Settings.shared.appID = AppConfig.facebookAppID
            Settings.shared.clientToken = AppConfig.facebookClientToken
            Settings.shared.displayName = "SOSlive"
            ApplicationDelegate.shared.application(application, didFinishLaunchingWithOptions: launchOptions)
        }
        if AppConfig.googleConfigured {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(
                clientID: AppConfig.googleClientID,
                serverClientID: AppConfig.googleServerClientID.isEmpty ? nil : AppConfig.googleServerClientID
            )
        }
        return true
    }
}

extension Color {
    static let sosRed = Color(red: 0.83, green: 0.18, blue: 0.18)
}
