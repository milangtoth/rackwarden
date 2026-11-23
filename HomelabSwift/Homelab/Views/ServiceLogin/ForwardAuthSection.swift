import SwiftUI

/// What the user entered about the proxy, until the service is saved.
@MainActor
@Observable
final class ForwardAuthForm {
    struct Row: Identifiable {
        let id = UUID()
        var name = ""
        var value = ""
    }

    var isOn = false
    var method: AccessProfile.Method = .browser
    var rows: [Row] = [Row()]
    var allowsInsecure = false
    var skipsNativeLogin = false
    var notice: String?
    private(set) var access: [AccessOrigin: OriginAccess] = [:]

    var headers: [AccessHeader] {
        rows.map { AccessHeader(name: $0.name.trimmingCharacters(in: .whitespaces),
                                value: $0.value.trimmingCharacters(in: .whitespacesAndNewlines)) }
            .filter { !$0.name.isEmpty || !$0.value.isEmpty }
    }

    var hasCredentials: Bool { !access.isEmpty || !headers.isEmpty }

    func load(_ profile: AccessProfile?) {
        guard let profile, profile.isEnabled else { return }
        isOn = true
        method = profile.method
        allowsInsecure = profile.allowsInsecureTransport
        skipsNativeLogin = !profile.usesNativeLogin
        access = Dictionary(profile.origins.map { ($0.origin, $0) }, uniquingKeysWith: { first, _ in first })
        if let saved = profile.origins.first(where: { !$0.headers.isEmpty })?.headers {
            rows = saved.map { Row(name: $0.name, value: $0.value) }
        }
    }

    func connect(_ access: OriginAccess) { self.access[access.origin] = access }

    /// Forgets browser sessions only.
    func signOut() { access = access.filter { $0.value.cookies.isEmpty } }

    func turnOff() {
        isOn = false
        skipsNativeLogin = false
        access = [:]
        rows = [Row()]
    }

    /// Edited headers need a new check before they count as connected.
    func isConnected(_ origin: AccessOrigin?) -> Bool {
        guard let origin, let access = access[origin] else { return false }
        switch method {
        case .browser: return !access.cookies.isEmpty
        case .headers: return !headers.isEmpty && access.headers == headers
        }
    }

    func needsConsent(_ origins: [AccessOrigin], allowSelfSigned: Bool) -> Bool {
        allowSelfSigned || origins.contains { !$0.isEncrypted }
    }

    func firstInvalidHeader(for type: ServiceType) -> AccessHeader? {
        headers.first { !ForwardAuth.canSend($0, to: type, usesNativeLogin: !skipsNativeLogin) }
    }

    /// The profile to use, or `nil` while the main address is not connected.
    func profile(primary: AccessOrigin?, fallback: AccessOrigin?, allowSelfSigned: Bool) -> AccessProfile? {
        guard isConnected(primary) else { return nil }
        let origins = [primary, fallback == primary ? nil : fallback]
            .compactMap { $0 }
            .filter { isConnected($0) }
        return AccessProfile(
            isEnabled: true,
            method: method,
            usesNativeLogin: !skipsNativeLogin,
            allowsInsecureTransport: allowsInsecure && needsConsent(origins, allowSelfSigned: allowSelfSigned),
            origins: origins.compactMap { access[$0] }.map {
                method == .browser
                    ? OriginAccess(origin: $0.origin, cookies: $0.cookies)
                    : OriginAccess(origin: $0.origin, headers: $0.headers)
            }
        )
    }
}

struct ForwardAuthSection: View {
    @Bindable var form: ForwardAuthForm
    let serviceType: ServiceType
    /// Keeps the browser session of this sign-in.
    let instanceId: UUID
    let url: URL?
    let fallbackURL: URL?
    @Binding var allowSelfSigned: Bool
    let onSignOut: () async -> Void

    @Environment(Localizer.self) private var localizer
    @State private var target: ForwardAuthTarget?
    @State private var checking: URL?
    @State private var confirmsTurnOff = false
    @State private var confirmsSignOut = false
    @State private var errorMessage: String?

    private var origin: AccessOrigin? { url.flatMap { AccessOrigin(url: $0) } }
    private var fallbackOrigin: AccessOrigin? {
        let other = fallbackURL.flatMap { AccessOrigin(url: $0) }
        return other == origin ? nil : other
    }
    private var needsConsent: Bool {
        form.needsConsent([origin, fallbackOrigin].compactMap { $0 }, allowSelfSigned: allowSelfSigned)
    }

    private var toggle: Binding<Bool> {
        Binding {
            form.isOn
        } set: { isOn in
            if isOn {
                form.notice = nil
                // Proxy credentials need verified TLS unless the user opts out below.
                if !form.isConnected(origin) { allowSelfSigned = false }
                form.isOn = true
            } else if form.hasCredentials {
                confirmsTurnOff = true
            } else {
                form.turnOff()
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Toggle(isOn: toggle) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(localizer.t.forwardAuthTitle)
                        .font(.body.weight(.medium))
                    Text(localizer.t.forwardAuthDesc)
                        .font(.caption)
                        .foregroundStyle(AppTheme.textSecondary)
                }
            }
            .tint(AppTheme.accent)
            .confirmationDialog(
                localizer.t.forwardAuthTurnOffTitle, isPresented: $confirmsTurnOff, titleVisibility: .visible
            ) {
                Button(localizer.t.forwardAuthTurnOff, role: .destructive) { form.turnOff() }
            } message: {
                Text(localizer.t.forwardAuthTurnOffMessage)
            }

            if let notice = form.notice {
                Text(notice).font(.caption).foregroundStyle(AppTheme.warning)
            }
            if form.isOn { settings }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .glassCard()
        .sheet(item: $target) { target in
            ForwardAuthSheet(target: target) { finish(target.origin, $0) }
        }
    }

    @ViewBuilder
    private var settings: some View {
        Picker("", selection: $form.method) {
            Text(localizer.t.forwardAuthMethodBrowser).tag(AccessProfile.Method.browser)
            Text(localizer.t.forwardAuthMethodHeaders).tag(AccessProfile.Method.headers)
        }
        .pickerStyle(.segmented)
        .onChange(of: form.method) { errorMessage = nil }

        if form.method == .headers { headerEditor }

        if needsConsent {
            Toggle(isOn: $form.allowsInsecure) {
                Text(localizer.t.forwardAuthInsecure)
                    .font(.caption)
                    .foregroundStyle(AppTheme.warning)
            }
            .tint(AppTheme.warning)
        }

        connection(url, origin)
        if let fallbackOrigin, form.isConnected(origin) {
            Divider()
            Text(String(format: localizer.t.forwardAuthFallback, fallbackOrigin.host))
                .font(.caption)
                .foregroundStyle(AppTheme.textSecondary)
            connection(fallbackURL, fallbackOrigin)
        }

        if let errorMessage {
            Text(errorMessage).font(.caption).foregroundStyle(AppTheme.danger)
        }

        if form.method == .browser, form.isConnected(origin) {
            Button(localizer.t.forwardAuthSignOut, role: .destructive) { confirmsSignOut = true }
                .font(.subheadline)
                .confirmationDialog(
                    localizer.t.forwardAuthSignOut, isPresented: $confirmsSignOut, titleVisibility: .visible
                ) {
                    Button(localizer.t.forwardAuthSignOut, role: .destructive) {
                        form.signOut()
                        Task { await onSignOut() }
                    }
                } message: {
                    Text(localizer.t.forwardAuthSignOutMessage)
                }
        }

        if ForwardAuth.canSkipNativeLogin(serviceType) {
            Divider()
            Toggle(isOn: $form.skipsNativeLogin) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(localizer.t.forwardAuthSkipLogin).font(.subheadline.weight(.medium))
                    Text(localizer.t.forwardAuthSkipLoginDesc)
                        .font(.caption)
                        .foregroundStyle(AppTheme.textSecondary)
                }
            }
            .tint(AppTheme.accent)
        }
    }

    private var headerEditor: some View {
        VStack(spacing: 8) {
            ForEach($form.rows) { $row in
                HStack(spacing: 8) {
                    TextField(localizer.t.forwardAuthHeaderName, text: $row.name)
                        .keyboardType(.asciiCapable)
                    SecureField(localizer.t.forwardAuthHeaderValue, text: $row.value)
                        .textContentType(.oneTimeCode)
                    Button {
                        form.rows.removeAll { $0.id == row.id }
                    } label: {
                        Image(systemName: "minus.circle.fill").foregroundStyle(AppTheme.danger)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(localizer.t.delete)
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)
            }
            Button {
                form.rows.append(.init())
            } label: {
                Label(localizer.t.forwardAuthAddHeader, systemImage: "plus")
            }
            .font(.subheadline)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private func connection(_ url: URL?, _ origin: AccessOrigin?) -> some View {
        let isConnected = form.isConnected(origin)
        if isConnected {
            Label(localizer.t.forwardAuthConnected, systemImage: "checkmark.shield.fill")
                .font(.subheadline)
                .foregroundStyle(AppTheme.running)
        }
        HStack {
            Button(buttonTitle(isConnected)) { connect(url) }
                .buttonStyle(.bordered)
                .disabled(
                    origin == nil || checking != nil || (needsConsent && !form.allowsInsecure)
                        || (form.method == .headers && form.headers.isEmpty)
                )
            if let url, checking == url { ProgressView() }
        }
    }

    private func buttonTitle(_ isConnected: Bool) -> String {
        switch form.method {
        case .headers: localizer.t.forwardAuthTestHeaders
        case .browser: isConnected ? localizer.t.forwardAuthSignInAgain : localizer.t.forwardAuthSignIn
        }
    }

    private func connect(_ url: URL?) {
        guard let url, let origin = AccessOrigin(url: url) else { return }
        endEditing()
        errorMessage = nil
        guard form.method == .headers else {
            target = ForwardAuthTarget(
                instanceId: instanceId, url: url,
                allowsInsecureTransport: form.allowsInsecure, allowSelfSigned: allowSelfSigned
            )
            return
        }
        if let invalid = form.firstInvalidHeader(for: serviceType) {
            errorMessage = String(format: localizer.t.forwardAuthHeaderInvalid, invalid.name)
            return
        }
        let access = OriginAccess(origin: origin, headers: form.headers)
        checking = url
        Task {
            do {
                switch try await ForwardAuth.probe(
                    url: url, access: access,
                    allowsInsecureTransport: form.allowsInsecure, allowSelfSigned: allowSelfSigned
                ) {
                case .passed: finish(origin, access)
                case .notNeeded: finish(origin, nil)
                case .blocked: errorMessage = localizer.t.forwardAuthHeadersBlocked
                }
            } catch {
                errorMessage = error.localizedDescription
            }
            checking = nil
        }
    }

    /// `nil` means the address answered without any proxy credentials.
    private func finish(_ target: AccessOrigin, _ access: OriginAccess?) {
        errorMessage = nil
        if let access {
            form.connect(access)
        } else if target == origin {
            form.turnOff()
            form.notice = localizer.t.forwardAuthNotNeeded
        } else {
            errorMessage = localizer.t.forwardAuthFallbackNotNeeded
        }
    }

    private func endEditing() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}
