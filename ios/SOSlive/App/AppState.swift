import Foundation
import SOSliveCore

/// App-wide dependencies and the logged in user. When the session disappears (logout or a
/// rejected refresh token) `user` becomes nil and RootView shows the login screens.
@MainActor
final class AppState: ObservableObject {
    @Published private(set) var user: User?

    let api: APIClient
    let auth: AuthService
    let events: EventService
    let profile: ProfileService
    let location = LocationTracker()

    init() {
        let store = KeychainSessionStore()
        let activeEvents = ActiveEventStore()
        api = APIClient(baseURL: AppConfig.apiBaseURL, store: store)
        auth = AuthService(api: api, activeEvents: activeEvents)
        events = EventService(api: api, activeEvents: activeEvents)
        profile = ProfileService(api: api)
        user = store.load()?.user
        api.onSessionChange = { [weak self] session in
            Task { @MainActor in self?.user = session?.user }
        }
    }
}
