import Foundation

enum ForwardAuth {
    enum Outcome: Equatable, Sendable {
        /// The credentials get past the proxy.
        case passed
        /// The proxy still asks for a sign-in.
        case blocked
        /// The service answers without any proxy credentials.
        case notNeeded
    }

    private enum Reply: Equatable {
        case blocked
        case status(Int)
    }

    /// True for a page of the service itself, not of the proxy living on the same address.
    static func isServicePage(_ url: URL?, origin: AccessOrigin) -> Bool {
        guard let url, AccessOrigin(url: url) == origin else { return false }
        return !isProxyPath(url.path)
    }

    static func isProxyPath(_ path: String) -> Bool {
        path == "/outpost.goauthentik.io" || path.hasPrefix("/outpost.goauthentik.io/")
            || path.hasPrefix("/cdn-cgi/access/")
            || ["/oauth2/start", "/oauth2/sign_in", "/oauth2/callback"].contains(path)
    }

    /// True when a request without proxy credentials ended up on a sign-in page.
    static func isLoginPage(_ response: URLResponse, requested: URL?) -> Bool {
        guard let final = response.url else { return false }
        if isProxyPath(final.path) { return true }
        return final.host?.lowercased() != requested?.host?.lowercased()
            && response.mimeType?.lowercased() == "text/html"
    }

    /// True when the address asks for a proxy sign-in. Sends no credentials.
    static func isProtected(_ url: URL, allowSelfSigned: Bool) async -> Bool {
        guard let origin = AccessOrigin(url: url) else { return false }
        return (try? await reply(
            url: url, access: OriginAccess(origin: origin),
            allowsInsecureTransport: true, allowSelfSigned: allowSelfSigned
        )) == .blocked
    }

    /// Services that use `Authorization` themselves, so the proxy cannot use it too.
    static func canSend(_ header: AccessHeader, to type: ServiceType, usesNativeLogin: Bool) -> Bool {
        guard header.isValid else { return false }
        return header.name.lowercased() != "authorization" || !usesNativeLogin
            || [.pihole, .technitium, .dockhand, .komodo, .maltrail, .qbittorrent, .radarr, .sonarr, .lidarr, .plex,
                .healthchecks, .jellystat, .unifiNetwork, .truenas, .scrutiny, .dockge].contains(type)
    }

    /// Services that can work with the proxy as their only sign-in.
    static func canSkipNativeLogin(_ type: ServiceType) -> Bool {
        [.gluetun, .flaresolverr, .maltrail, .uptimeKuma, .adguardHome, .qbittorrent, .scrutiny, .dockge].contains(type)
    }

    private static func reply(
        url: URL,
        access: OriginAccess,
        allowsInsecureTransport: Bool,
        allowSelfSigned: Bool
    ) async throws -> Reply {
        // Throwaway profile, so nothing is stored by probing.
        let id = UUID()
        AccessProfileStore.shared.setDraft(
            AccessProfile(isEnabled: true, allowsInsecureTransport: allowsInsecureTransport, origins: [access]),
            for: id
        )
        defer { AccessProfileStore.shared.discardDraft(id) }
        let session = BaseNetworkEngine.authSession(instanceId: id, allowSelfSigned: allowSelfSigned, timeout: 10)
        defer { session.session.finishTasksAndInvalidate() }
        do {
            let (_, response) = try await session.data(for: URLRequest(url: url))
            return .status((response as? HTTPURLResponse)?.statusCode ?? 0)
        } catch APIError.forwardAuthRequired {
            return .blocked
        }
    }

    /// Cookies a browser would send to `origin`, including those set for a parent domain.
    static func cookies(_ cookies: [HTTPCookie], for origin: AccessOrigin) -> [AccessCookie] {
        cookies.filter { cookie in
            let domain = cookie.domain.lowercased()
            let matches = domain.hasPrefix(".")
                ? origin.host == domain.dropFirst() || origin.host.hasSuffix(domain)
                : origin.host == domain
            return matches && (!cookie.isSecure || origin.isEncrypted)
        }
        .map { AccessCookie(name: $0.name, value: $0.value, path: $0.path, expiresAt: $0.expiresDate) }
    }

    /// Compares the service's answer with and without the proxy credentials.
    static func probe(
        url: URL,
        access: OriginAccess,
        allowsInsecureTransport: Bool,
        allowSelfSigned: Bool
    ) async throws -> Outcome {
        func reply(with access: OriginAccess) async throws -> Reply {
            try await Self.reply(
                url: url, access: access,
                allowsInsecureTransport: allowsInsecureTransport, allowSelfSigned: allowSelfSigned
            )
        }

        let anonymous = try await reply(with: OriginAccess(origin: access.origin))
        if case .status(let code) = anonymous, (200..<400).contains(code) { return .notNeeded }
        let authenticated = try await reply(with: access)
        guard case .status(let code) = authenticated else { return .blocked }
        if (200..<400).contains(code) { return .passed }
        guard code < 500, code != 407 else { return .blocked }
        switch anonymous {
        case .blocked: return .passed
        // The proxy's refusal gave way to the service's own answer.
        case .status(let before): return [401, 403].contains(before) && ![401, 403].contains(code) ? .passed : .blocked
        }
    }
}

extension Error {
    /// True when the service itself asked for a login.
    var isLoginRefusal: Bool {
        guard let error = self as? APIError else { return false }
        switch error {
        case .unauthorized: return true
        case .httpError(let code, _): return code == 401 || code == 403
        case .bothURLsFailed(let primary, let fallback): return primary.isLoginRefusal || fallback.isLoginRefusal
        default: return false
        }
    }
}
