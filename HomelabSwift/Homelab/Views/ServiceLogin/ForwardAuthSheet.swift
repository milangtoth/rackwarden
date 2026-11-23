import SwiftUI
import WebKit

/// Address to sign in to, and the instance that keeps the browser session.
struct ForwardAuthTarget: Identifiable {
    let instanceId: UUID
    let url: URL
    let origin: AccessOrigin
    let allowsInsecureTransport: Bool
    let allowSelfSigned: Bool

    var id: String { "\(instanceId) \(url)" }

    init?(instanceId: UUID, url: URL, allowsInsecureTransport: Bool, allowSelfSigned: Bool) {
        guard let origin = AccessOrigin(url: url) else { return nil }
        self.instanceId = instanceId
        self.url = url
        self.origin = origin
        self.allowsInsecureTransport = allowsInsecureTransport
        self.allowSelfSigned = allowSelfSigned
    }

    /// Only for saved browser sign-ins; header credentials are changed in the editor.
    init?(renewing url: URL, instance: ServiceInstance) {
        guard let profile = AccessProfileStore.shared.profile(for: instance.id),
              profile.isEnabled, profile.method == .browser else { return nil }
        self.init(
            instanceId: instance.id, url: url,
            allowsInsecureTransport: profile.allowsInsecureTransport, allowSelfSigned: instance.allowSelfSigned
        )
    }
}

/// Browser data of proxy sign-ins, kept per instance so renewing can skip the password.
@MainActor
enum ForwardAuthWebData {
    static func store(for id: UUID) -> WKWebsiteDataStore { WKWebsiteDataStore(forIdentifier: id) }

    private static func identifiers() async -> [UUID] {
        // WebKit crashes when listing stores before it has been initialised.
        _ = WKWebsiteDataStore.default()
        return await WKWebsiteDataStore.allDataStoreIdentifiers
    }

    static func remove(_ id: UUID) async {
        guard await identifiers().contains(id) else { return }
        await store(for: id).removeData(
            ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast
        )
        // Fails while a web view still uses it; its data is already gone and the next launch retries.
        try? await WKWebsiteDataStore.remove(forIdentifier: id)
    }

    /// Cleans up after a setup that was interrupted.
    static func removeOrphans(keeping known: Set<UUID>) async {
        for id in await identifiers() where !known.contains(id) {
            await remove(id)
        }
    }
}

/// Lets the user sign in to the proxy and hands back the resulting session.
struct ForwardAuthSheet: View {
    let target: ForwardAuthTarget
    let onFinish: (OriginAccess?) -> Void

    @Environment(Localizer.self) private var localizer
    @Environment(\.dismiss) private var dismiss
    @State private var currentHost = ""
    @State private var isSecurePage = false
    @State private var isChecking = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let errorMessage {
                    Text(errorMessage)
                        .font(.footnote)
                        .foregroundStyle(AppTheme.danger)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                }
                ForwardAuthWebView(
                    target: target,
                    onNavigate: { page in
                        currentHost = page?.host ?? ""
                        isSecurePage = page?.scheme == "https"
                    },
                    onPage: check,
                    onError: { errorMessage = $0 }
                )
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(localizer.t.cancel) { dismiss() }
                }
                ToolbarItem(placement: .principal) {
                    Label(currentHost, systemImage: isSecurePage ? "lock.fill" : "lock.open.fill")
                        .labelStyle(.titleAndIcon)
                        .font(.footnote.weight(.semibold))
                        .lineLimit(1)
                        .truncationMode(.head)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isChecking { ProgressView() }
                }
            }
        }
        .interactiveDismissDisabled()
    }

    private func check(_ page: URL?, _ cookies: [HTTPCookie]) {
        guard !isChecking, ForwardAuth.isServicePage(page, origin: target.origin) else { return }
        isChecking = true
        errorMessage = nil
        let access = OriginAccess(origin: target.origin, cookies: ForwardAuth.cookies(cookies, for: target.origin))
        Task {
            do {
                switch try await ForwardAuth.probe(
                    url: target.url,
                    access: access,
                    allowsInsecureTransport: target.allowsInsecureTransport,
                    allowSelfSigned: target.allowSelfSigned
                ) {
                case .passed:
                    onFinish(access)
                    dismiss()
                case .notNeeded:
                    onFinish(nil)
                    dismiss()
                case .blocked:
                    errorMessage = localizer.t.forwardAuthBlocked
                }
            } catch {
                errorMessage = error.localizedDescription
            }
            isChecking = false
        }
    }
}

private struct ForwardAuthWebView: UIViewRepresentable {
    let target: ForwardAuthTarget
    let onNavigate: (URL?) -> Void
    let onPage: (URL?, [HTTPCookie]) -> Void
    let onError: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = ForwardAuthWebData.store(for: target.instanceId)
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.load(URLRequest(url: target.url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.parent = self
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        var parent: ForwardAuthWebView

        init(_ parent: ForwardAuthWebView) {
            self.parent = parent
        }

        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            parent.onNavigate(webView.url)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            let page = webView.url
            parent.onNavigate(page)
            Task {
                let cookies = await webView.configuration.websiteDataStore.httpCookieStore.allCookies()
                parent.onPage(page, cookies)
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            report(error)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            report(error)
        }

        /// Sign-in pages that open a new window continue in this one.
        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            webView.load(navigationAction.request)
            return nil
        }

        /// Accepts an unverified certificate only for the service itself, and only with consent.
        func webView(
            _ webView: WKWebView,
            respondTo challenge: URLAuthenticationChallenge
        ) async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
            let target = parent.target
            let space = challenge.protectionSpace
            guard target.allowSelfSigned, target.allowsInsecureTransport,
                  space.host.lowercased() == target.origin.host, space.port == target.origin.port,
                  let trust = space.serverTrust else { return (.performDefaultHandling, nil) }
            return (.useCredential, URLCredential(trust: trust))
        }

        private func report(_ error: Error) {
            // Cancelled loads are normal while the proxy redirects.
            guard (error as? URLError)?.code != .cancelled else { return }
            parent.onError(error.localizedDescription)
        }
    }
}
