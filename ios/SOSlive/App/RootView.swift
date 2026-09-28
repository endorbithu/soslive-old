import SOSliveCore
import SwiftUI

enum Route: Hashable {
    case events
    case eventDetail(Int64)
    case profile
}

/// The session decides what is shown: login flow or the main screens.
struct RootView: View {
    @EnvironmentObject private var app: AppState

    var body: some View {
        if let user = app.user {
            // Keyed by user: another account gets fresh view models and navigation state.
            MainView(app: app).id(user.id)
        } else {
            AuthFlowView(auth: app.auth)
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
                    case .events: EventsView(events: app.events)
                    case .eventDetail(let id): EventDetailView(id: id, events: app.events)
                    case .profile: ProfileView(app: app)
                    }
                }
        }
    }
}
