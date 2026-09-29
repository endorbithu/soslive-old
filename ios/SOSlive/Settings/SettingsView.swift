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
        defer { loading = false }
        do {
            if let remote = try await app.drive.readRemoteConfig() { fill(remote) }
        } catch {
            message = L10n.error(error)
        }
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
