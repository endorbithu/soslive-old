import SOSliveCore
import SwiftUI

struct AuthFlowView: View {
    let auth: AuthServicing

    var body: some View {
        NavigationStack {
            LoginView(auth: auth)
        }
    }
}

struct LoginView: View {
    @StateObject private var model: AuthViewModel
    private let auth: AuthServicing

    init(auth: AuthServicing) {
        self.auth = auth
        _model = StateObject(wrappedValue: AuthViewModel(auth: auth))
    }

    var body: some View {
        AuthScaffold(error: model.error) {
            LabeledField("field.email", text: $model.email, error: model.fieldErrors[.email], kind: .email)
            LabeledField("field.password", text: $model.password, error: model.fieldErrors[.password], kind: .password)
                .onSubmit(model.login)
            PrimaryButton("action.login", loading: model.loading, action: model.login)
            NavigationLink("action.go_register") { RegisterView(auth: auth) }
                .font(.subheadline)
            SsoButtons(enabled: !model.loading, onResult: model.handle)
        }
    }
}

struct RegisterView: View {
    @StateObject private var model: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    init(auth: AuthServicing) {
        _model = StateObject(wrappedValue: AuthViewModel(auth: auth))
    }

    var body: some View {
        AuthScaffold(error: model.error) {
            LabeledField("field.display_name", text: $model.displayName, error: model.fieldErrors[.name], kind: .name)
            LabeledField("field.email", text: $model.email, error: model.fieldErrors[.email], kind: .email)
            LabeledField("field.password", text: $model.password,
                         error: model.fieldErrors[.password] ?? L10n.tr("error.password_short", AuthViewModel.minPassword),
                         isHint: model.fieldErrors[.password] == nil, kind: .password)
                .onSubmit(model.register)
            PrimaryButton("action.register", loading: model.loading, action: model.register)
            Button("action.go_login") { dismiss() }
                .font(.subheadline)
            SsoButtons(enabled: !model.loading, onResult: model.handle)
        }
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Building blocks

private struct AuthScaffold<Content: View>: View {
    let error: String?
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                Image("Logo")
                    .resizable()
                    .scaledToFit()
                    .frame(height: 96)
                    .padding(.vertical, 24)
                    .accessibilityLabel("SOSlive")
                if let error {
                    Text(error).foregroundStyle(.red).font(.callout).multilineTextAlignment(.center)
                }
                content
            }
            .padding(24)
        }
        .scrollDismissesKeyboard(.interactively)
    }
}

struct LabeledField: View {
    enum Kind { case email, password, name, plain }

    private let title: LocalizedStringKey
    @Binding private var text: String
    private let error: String?
    private let isHint: Bool
    private let kind: Kind

    init(_ title: LocalizedStringKey, text: Binding<String>, error: String?, isHint: Bool = false, kind: Kind = .plain) {
        self.title = title
        _text = text
        self.error = error
        self.isHint = isHint
        self.kind = kind
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Group {
                if kind == .password {
                    SecureField(title, text: $text).textContentType(.password)
                } else {
                    TextField(title, text: $text)
                        .keyboardType(kind == .email ? .emailAddress : .default)
                        .textContentType(kind == .email ? .emailAddress : kind == .name ? .name : nil)
                        .textInputAutocapitalization(kind == .name ? .words : .never)
                        .autocorrectionDisabled(kind != .plain)
                }
            }
            .textFieldStyle(.roundedBorder)
            if let error {
                Text(error).font(.caption).foregroundStyle(isHint ? Color.secondary : Color.red)
            }
        }
    }
}

struct PrimaryButton: View {
    private let title: LocalizedStringKey
    private let loading: Bool
    private let action: () -> Void

    init(_ title: LocalizedStringKey, loading: Bool, action: @escaping () -> Void) {
        self.title = title
        self.loading = loading
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            ZStack {
                Text(title).opacity(loading ? 0 : 1)
                if loading { ProgressView() }
            }
            .frame(maxWidth: .infinity, minHeight: 32)
        }
        .buttonStyle(.borderedProminent)
        .disabled(loading)
    }
}

private struct SsoButtons: View {
    let enabled: Bool
    let onResult: (SsoProvider, SsoResult) -> Void
    @State private var simulated: SsoProvider?

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                VStack { Divider() }
                Text("auth.or_continue_with").font(.caption).foregroundStyle(.secondary)
                VStack { Divider() }
            }
            .padding(.vertical, 8)
            button(.google)
            button(.facebook)
        }
        .sheet(item: $simulated) { provider in
            SimulatedSsoSheet(provider: provider) { email, name in
                simulated = nil
                onResult(provider, .success(SimulatedSso.token(email: email, displayName: name)))
            }
        }
    }

    private func button(_ provider: SsoProvider) -> some View {
        Button {
            if provider.isSimulated {
                simulated = provider
            } else {
                Task {
                    let result = provider == .google ? await SSO.googleIdToken() : await SSO.facebookAccessToken()
                    onResult(provider, result)
                }
            }
        } label: {
            Text(provider.isSimulated ? "\(provider.title) \(L10n.tr("sso.simulated_suffix"))" : provider.title)
                .frame(maxWidth: .infinity, minHeight: 32)
        }
        .buttonStyle(.bordered)
        .disabled(!enabled)
    }
}

/// Stand-in for the provider's account picker when no client id is configured.
private struct SimulatedSsoSheet: View {
    let provider: SsoProvider
    let onConfirm: (String, String) -> Void
    @State private var email = ""
    @State private var name = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("sso.simulated_body").font(.footnote).foregroundStyle(.secondary)
                }
                Section {
                    TextField("field.email", text: $email)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("field.display_name", text: $name)
                }
            }
            .navigationTitle(L10n.tr("sso.simulated_title", provider.title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("action.cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("action.login") { onConfirm(email, name) }.disabled(!Email.isValid(email))
                }
            }
        }
        .presentationDetents([.medium])
    }
}
