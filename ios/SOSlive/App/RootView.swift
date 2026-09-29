import SOSliveCore
import SwiftUI

enum Route: Hashable {
    case events
    case eventDetail(fileId: String, title: String)
    case settings
}

/// The stored account decides what is shown: Google sign-in or the main screens.
struct RootView: View {
    @EnvironmentObject private var app: AppState

    var body: some View {
        if let account = app.account {
            // Keyed by user: another account gets fresh view models and navigation state.
            MainView(app: app).id(account.email)
        } else {
            SignInView()
        }
    }
}

struct MainView: View {
    @StateObject private var streamModel: StreamViewModel
    @State private var path: [Route] = []
    private let app: AppState

    init(app: AppState) {
        self.app = app
        _streamModel = StateObject(wrappedValue: StreamViewModel(app: app))
    }

    var body: some View {
        NavigationStack(path: $path) {
            StreamView(model: streamModel, path: $path)
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .events: EventsView(drive: app.drive)
                    case let .eventDetail(fileId, title): EventDetailView(fileId: fileId, title: title, drive: app.drive)
                    case .settings: SettingsView(app: app)
                    }
                }
        }
    }
}
