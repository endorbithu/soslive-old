import SOSliveCore
import SwiftUI

/// Edits config.json on Drive - the web only shows it ("can only be changed in the mobile app").
@MainActor
final class SettingsViewModel: ObservableObject {
    @Published var emails = ""
    @Published var phones = ""
    @Published var maxEvents = String(SosConfig.defaultMaxEvents)
    @Published private(set) var emailsError: String?
    @Published private(set) var phonesError: String?
    @Published private(set) var maxEventsError: String?
    @Published private(set) var loading = true
    @Published private(set) var saving = false
    @Published var message: String?
    /// The user's own streaming service - stored only on this phone (Keychain).
    @Published var stream: StreamSettings
    @Published private(set) var streamErrors: Set<StreamSettings.Field> = []
    /// People the events folder is shared with (read-only).
    @Published private(set) var viewers: [DrivePermission] = []
    @Published private(set) var viewersLoading = true
    @Published var viewerEmail = ""
    @Published private(set) var viewerError: String?
    @Published private(set) var viewerBusy = false

    private let app: AppState
    /// Last loaded config - keeps unknown fields when saving.
    private var base = SosConfig()

    var account: UserAccount? { app.account }

    init(app: AppState) {
        self.app = app
        stream = app.streamSettings.load()
        if let cached = app.drive.cachedConfig { fill(cached) }
    }

    func load() async {
        do {
            if let remote = try await app.drive.readRemoteConfig() { fill(remote) }
        } catch {
            message = L10n.error(error)
        }
        loading = false
        await loadViewers()
    }

    private func fill(_ config: SosConfig) {
        base = config
        emails = config.notificationEmails.joined(separator: "\n")
        phones = config.notificationPhones.joined(separator: "\n")
        maxEvents = String(config.maxEvents)
    }

    func save() {
        guard !saving else { return }
        let emailList = ContactRules.split(emails)
        let phoneList = ContactRules.split(phones)
        let badEmails = emailList.filter { !ContactRules.isValidEmail($0) }
        let badPhones = phoneList.filter { !ContactRules.isValidPhone($0) }
        let max = Int(maxEvents.trimmingCharacters(in: .whitespaces))
        emailsError = badEmails.isEmpty ? nil : L10n.tr("error.emails_invalid", badEmails.joined(separator: ", "))
        phonesError = badPhones.isEmpty ? nil : L10n.tr("error.phones_invalid", badPhones.joined(separator: ", "))
        maxEventsError = (max ?? 0) >= 1 ? nil : L10n.tr("error.max_events")
        guard badEmails.isEmpty, badPhones.isEmpty, let max, max >= 1 else { return }

        var config = base
        config.notificationEmails = emailList
        config.notificationPhones = phoneList
        config.maxEvents = max
        saving = true
        Task {
            defer { saving = false }
            do {
                try await app.drive.saveConfig(config)
                fill(config)
                message = L10n.tr("settings.saved")
            } catch {
                message = L10n.error(error)
            }
        }
    }

    // MARK: - Viewers

    private func loadViewers() async {
        defer { viewersLoading = false }
        do {
            viewers = try await app.drive.viewers()
        } catch {
            message = L10n.error(error)
        }
    }

    /// Shares the events folder read-only; Google e-mails the person a link.
    func addViewer() {
        guard !viewerBusy else { return }
        let email = viewerEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        if !ContactRules.isValidEmail(email) {
            viewerError = L10n.tr("error.emails_invalid", email)
        } else if email.caseInsensitiveCompare(account?.email ?? "") == .orderedSame {
            viewerError = L10n.tr("error.viewer_self")
        } else if viewers.contains(where: { $0.email.caseInsensitiveCompare(email) == .orderedSame }) {
            viewerError = L10n.tr("error.viewer_exists")
        } else {
            viewerError = nil
        }
        guard viewerError == nil else { return }
        viewerBusy = true
        Task {
            defer { viewerBusy = false }
            do {
                viewers.append(try await app.drive.addViewer(email: email))
                viewerEmail = ""
                message = L10n.tr("viewer.added", email)
            } catch {
                viewerError = L10n.error(error)
            }
        }
    }

    func removeViewer(_ viewer: DrivePermission) {
        guard !viewerBusy else { return }
        viewerBusy = true
        Task {
            defer { viewerBusy = false }
            do {
                try await app.drive.removeViewer(permissionId: viewer.id)
                viewers.removeAll { $0.id == viewer.id }
                message = L10n.tr("viewer.removed", viewer.email)
            } catch {
                message = L10n.error(error)
            }
        }
    }

    /// Saved in the Keychain on this device only - never to Drive.
    func saveStream() {
        streamErrors = stream.validate()
        guard streamErrors.isEmpty else { return }
        message = app.streamSettings.save(stream) ? L10n.tr("stream.settings_saved") : L10n.tr("error.generic")
    }

    func signOut() { app.signOut() }
}

struct SettingsView: View {
    @StateObject private var model: SettingsViewModel
    @State private var confirmSignOut = false
    @State private var confirmRemove: DrivePermission?

    init(app: AppState) {
        _model = StateObject(wrappedValue: SettingsViewModel(app: app))
    }

    var body: some View {
        Form {
            if let account = model.account {
                Section {
                    Text(account.name).font(.headline)
                    Text(account.email).font(.footnote).foregroundStyle(.secondary)
                    if account.simulated {
                        Text("simulated.hint").font(.footnote).foregroundStyle(.red)
                    }
                }
            }

            Section {
                TextField("field.notification_phones", text: $model.phones, axis: .vertical)
                    .keyboardType(.phonePad)
                    .lineLimit(2...6)
                if let error = model.phonesError { Text(error).font(.caption).foregroundStyle(.red) }
                TextField("field.notification_emails", text: $model.emails, axis: .vertical)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .lineLimit(2...6)
                if let error = model.emailsError { Text(error).font(.caption).foregroundStyle(.red) }
            } header: {
                Text("settings.notify_section")
            } footer: {
                Text("settings.notify_footer")
            }

            Section {
                TextField("field.max_events", text: $model.maxEvents).keyboardType(.numberPad)
                if let error = model.maxEventsError { Text(error).font(.caption).foregroundStyle(.red) }
            } header: {
                Text("field.max_events")
            } footer: {
                Text("field.max_events_hint")
            }

            Section {
                Button(action: model.save) {
                    HStack {
                        Text("action.save")
                        if model.saving || model.loading { Spacer(); ProgressView() }
                    }
                }
                .disabled(model.saving || model.loading)
            } footer: {
                Text("settings.web_note")
            }

            Section {
                if model.viewersLoading {
                    ProgressView()
                } else if model.viewers.isEmpty {
                    Text("viewers.empty").foregroundStyle(.secondary)
                } else {
                    ForEach(model.viewers) { viewer in
                        HStack {
                            VStack(alignment: .leading) {
                                if !viewer.displayName.isEmpty { Text(viewer.displayName) }
                                Text(viewer.email).font(.footnote).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button(role: .destructive) { confirmRemove = viewer } label: {
                                Image(systemName: "xmark.circle")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(Text("action.remove"))
                            .disabled(model.viewerBusy)
                        }
                    }
                }
                TextField("field.viewer_email", text: $model.viewerEmail)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if let error = model.viewerError { Text(error).font(.caption).foregroundStyle(.red) }
                Button(action: model.addViewer) {
                    HStack {
                        Text("action.add_viewer")
                        if model.viewerBusy { Spacer(); ProgressView() }
                    }
                }
                .disabled(model.viewerBusy || model.viewersLoading || model.viewerEmail.trimmingCharacters(in: .whitespaces).isEmpty)
            } header: {
                Text("settings.viewers_section")
            } footer: {
                Text("settings.viewers_note")
            }

            Section {
                urlField("field.stream_rtmp_url", prompt: "rtmp://a.rtmp.youtube.com/live2", text: $model.stream.rtmpURL, field: .rtmpURL)
                SecureField("field.stream_key", text: $model.stream.streamKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                urlField("field.stream_playback_url", prompt: "https://…/index.m3u8", text: $model.stream.playbackURL, field: .playbackURL)
                urlField("field.stream_page_url", prompt: "https://www.youtube.com/@…/live", text: $model.stream.pageURL, field: .pageURL)
                urlField("field.stream_recording_url", prompt: "https://…", text: $model.stream.recordingURL, field: .recordingURL)
                Button("action.save_stream", action: model.saveStream)
            } header: {
                Text("settings.stream_section")
            } footer: {
                Text("settings.stream_note")
            }

            Section {
                Button("action.logout", role: .destructive) { confirmSignOut = true }
            } footer: {
                Text("logout.note")
            }
        }
        .navigationTitle(Text("menu.settings"))
        .task { await model.load() }
        .confirmationDialog("logout.confirm", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("action.logout", role: .destructive, action: model.signOut)
        }
        .confirmationDialog(
            Text(L10n.tr("viewer.remove_confirm", confirmRemove?.email ?? "")),
            isPresented: Binding(get: { confirmRemove != nil }, set: { if !$0 { confirmRemove = nil } }),
            titleVisibility: .visible,
            presenting: confirmRemove
        ) { viewer in
            Button("action.remove", role: .destructive) { model.removeViewer(viewer) }
        } message: { _ in
            Text("viewer.remove_note")
        }
        .alert(model.message ?? "", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    @ViewBuilder
    private func urlField(_ title: LocalizedStringKey, prompt: String, text: Binding<String>, field: StreamSettings.Field) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            TextField(prompt, text: text)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            if model.streamErrors.contains(field) {
                Text("error.stream_url").font(.caption).foregroundStyle(.red)
            }
        }
    }
}
