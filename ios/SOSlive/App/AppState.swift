import Foundation
import SOSliveCore

/// App-wide dependencies and the signed in account. When the account is cleared, RootView
/// shows the sign-in screen.
@MainActor
final class AppState: ObservableObject {
    @Published private(set) var account: UserAccount? = nil
    @Published private(set) var restoring = true

    let auth: GoogleAuth
    let accounts = AccountStore()
    let drive: SosliveDrive
    let streamProvider: StreamProvider
    let location = LocationTracker()

    init() {
        let auth = GoogleAuth()
        self.auth = auth
        let api: DriveAPI
        if AppConfig.googleConfigured {
            api = GoogleDriveAPI(tokens: auth)
        } else {
            let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("simulated-drive")
            api = LocalDriveAPI(root: root)
        }
        drive = SosliveDrive(drive: api, cache: UserDefaultsDriveCache(), webappURL: AppConfig.webappURL)
        streamProvider = TemplateStreamProvider(rtmpURL: AppConfig.streamRTMPURL, hlsTemplate: AppConfig.streamHLSTemplate)
        account = accounts.account
    }

    /// At launch: the Google SDK restores its session; a stored account without it signs out.
    func restoreSession() async {
        defer { restoring = false }
        guard let stored = accounts.account, !stored.simulated else { return }
        if await auth.restore() == nil {
            signOutLocally()
        }
    }

    /// Prepares the SOSlive folder, then remembers the account.
    func completeSignIn(_ account: UserAccount) async throws {
        drive.forgetLocalState()
        _ = try await drive.folderId()
        _ = try? await drive.readRemoteConfig()
        accounts.account = account
        self.account = account
    }

    func signOut() {
        auth.signOut()
        signOutLocally()
    }

    private func signOutLocally() {
        accounts.account = nil
        accounts.activeEvent = nil
        drive.forgetLocalState()
        account = nil
    }
}
