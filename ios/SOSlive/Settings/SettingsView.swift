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

    private let app: AppState
    /// Last loaded config - keeps unknown fields when saving.
    private var base = SosConfig()

    var account: UserAccount? { app.account }

    init(app: AppState) {
        self.app = app
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
}
