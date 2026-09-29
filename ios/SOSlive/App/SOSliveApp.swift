import GoogleSignIn
import SwiftUI

@main
struct SOSliveApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
                .tint(.sosRed)
                // Google Sign-In redirect back into the app.
                .onOpenURL { url in _ = GIDSignIn.sharedInstance.handle(url) }
                .task { await appState.restoreSession() }
        }
    }
}

extension Color {
    static let sosRed = Color(red: 0.83, green: 0.18, blue: 0.18)
}
