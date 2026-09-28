import SOSliveCore
import SwiftUI

@MainActor
final class ProfileViewModel: ObservableObject {
    @Published var displayName = ""
    @Published var contacts = ""
    @Published var sosMessage = ""
    @Published private(set) var contactsError: String?
    @Published private(set) var saving = false
    @Published var message: String?

    private let app: AppState

    var user: User? { app.user }

    init(app: AppState) {
        self.app = app
        if let user = app.user { fill(user) }
    }

    func refresh() async {
        // Pick up changes made elsewhere - failures are ignored.
        if let user = try? await app.profile.refresh() { fill(user) }
    }

    private func fill(_ user: User) {
        displayName = user.displayName
        contacts = SosContacts.format(user.sosContacts)
        sosMessage = user.sosMessage
    }

    func save() {
        guard !saving else { return }
        let numbers: [String]
        switch SosContacts.parse(contacts) {
        case .valid(let parsed):
            numbers = parsed
        case .invalid(let invalid):
            contactsError = L10n.tr("error.phone_invalid", invalid.joined(separator: ", "))
            return
        case .tooMany:
            contactsError = L10n.tr("error.phone_too_many", SosContacts.maxContacts)
            return
        }
        contactsError = nil
        guard !displayName.trimmingCharacters(in: .whitespaces).isEmpty else {
            message = L10n.tr("error.name_required")
            return
        }
        saving = true
        let update = ProfileUpdate(displayName: displayName.trimmingCharacters(in: .whitespaces),
                                   sosContacts: numbers,
                                   sosMessage: String(sosMessage.trimmingCharacters(in: .whitespacesAndNewlines).prefix(300)))
        Task {
            defer { saving = false }
            do {
                fill(try await app.profile.update(update))
                message = L10n.tr("profile.saved")
            } catch {
                message = L10n.error(error)
            }
        }
    }

    func logout() {
        Task { await app.auth.logout() }
    }
}

struct ProfileView: View {
    @StateObject private var model: ProfileViewModel
    @State private var confirmLogout = false

    init(app: AppState) {
        _model = StateObject(wrappedValue: ProfileViewModel(app: app))
    }

    var body: some View {
        Form {
            if let user = model.user {
                Section {
                    Text(user.email).font(.headline)
                    Text(L10n.tr("profile.providers", user.providers.joined(separator: ", ")))
                        .font(.footnote).foregroundStyle(.secondary)
                    TextField("field.display_name", text: $model.displayName)
                }
            }

            Section {
                TextField("field.sos_contacts", text: $model.contacts, axis: .vertical)
                    .keyboardType(.phonePad)
                    .lineLimit(2...6)
                if let error = model.contactsError {
                    Text(error).font(.caption).foregroundStyle(.red)
                }
                TextField("sos.default_message", text: $model.sosMessage, axis: .vertical)
                    .lineLimit(2...5)
            } header: {
                Text("profile.sos_section")
            } footer: {
                Text("profile.sos_footer")
            }

            Section {
                Button(action: model.save) {
                    HStack {
                        Text("action.save")
                        if model.saving { Spacer(); ProgressView() }
                    }
                }
                .disabled(model.saving)
            }

            Section {
                Button("action.logout", role: .destructive) { confirmLogout = true }
            }
        }
        .navigationTitle(Text("menu.profile"))
        .task { await model.refresh() }
        .confirmationDialog("logout.confirm", isPresented: $confirmLogout, titleVisibility: .visible) {
            Button("action.logout", role: .destructive, action: model.logout)
        }
        .alert(model.message ?? "", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }
}
