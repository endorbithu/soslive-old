import SOSliveCore
import SwiftUI

/// Google sign-in with an explanation of the Drive permission (required by the guide).
struct SignInView: View {
    @EnvironmentObject private var app: AppState
    @State private var busy = false
    @State private var error: String?
    @State private var driveDenied = false
    @State private var simulatedSheet = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image("Logo").resizable().scaledToFit().frame(height: 96).padding(.vertical, 24)
                    .accessibilityLabel("SOSlive")

                VStack(alignment: .leading, spacing: 8) {
                    Text("drive.explain_title").font(.headline)
                    Text("drive.explain_body").font(.callout)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))

                if driveDenied {
                    Text("drive.denied").font(.callout).foregroundStyle(.red).multilineTextAlignment(.center)
                }
                if let error {
                    Text(error).font(.callout).foregroundStyle(.red).multilineTextAlignment(.center)
                }

                Button(action: primaryAction) {
                    ZStack {
                        Text(buttonTitle).opacity(busy ? 0 : 1)
                        if busy { ProgressView() }
                    }
                    .frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.borderedProminent)
                .disabled(busy || app.restoring)

                if !AppConfig.googleConfigured {
                    Text("simulated.hint").font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
            }
            .padding(24)
        }
        .sheet(isPresented: $simulatedSheet) {
            SimulatedAccountSheet { email, name in
                simulatedSheet = false
                finish(UserAccount(email: email, name: name.isEmpty ? String(email.split(separator: "@").first ?? "") : name, simulated: true))
            }
        }
    }

    private var buttonTitle: LocalizedStringKey {
        if !AppConfig.googleConfigured { return "action.sign_in_simulated" }
        return driveDenied ? "action.allow_drive" : "action.sign_in_google"
    }

    private func primaryAction() {
        guard AppConfig.googleConfigured else {
            simulatedSheet = true
            return
        }
        busy = true
        error = nil
        Task {
            let outcome = driveDenied ? await app.auth.requestDrive() : await app.auth.signIn()
            switch outcome {
            case let .success(account):
                driveDenied = false
                finish(account)
                return
            case .cancelled:
                break
            case .driveDenied:
                driveDenied = true
            case let .failure(message):
                error = L10n.tr("error.sign_in", message)
            }
            busy = false
        }
    }

    private func finish(_ account: UserAccount) {
        busy = true
        Task {
            do {
                try await app.completeSignIn(account)
            } catch {
                self.error = L10n.error(error)
            }
            busy = false
        }
    }
}

private struct SimulatedAccountSheet: View {
    let onConfirm: (String, String) -> Void
    @State private var email = ""
    @State private var name = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section { Text("simulated.hint").font(.footnote).foregroundStyle(.secondary) }
                Section {
                    TextField("field.email", text: $email)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("field.display_name", text: $name)
                }
            }
            .navigationTitle(Text("action.sign_in_simulated"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("action.cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("action.continue") {
                        onConfirm(email.trimmingCharacters(in: .whitespaces), name.trimmingCharacters(in: .whitespaces))
                    }
                    .disabled(!ContactRules.isValidEmail(email.trimmingCharacters(in: .whitespaces)))
                }
            }
        }
        .presentationDetents([.medium])
    }
}
