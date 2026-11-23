import Foundation

actor NginxProxyManagerAPIClient {
    private let instanceId: UUID
    private var engine: BaseNetworkEngine
    private var storedAllowSelfSigned = true
    private var baseURL: String = ""
    private var fallbackURL: String = ""
    private var token: String = ""
    private var email: String = ""
    private var storedPassword: String = ""
    private var isRefreshing = false
    private var onTokenRefreshed: (@Sendable (String) -> Void)?
    private var onSignInRequired: (@Sendable (String) -> Void)?
    /// Set once a silent login was asked for a code, so it is not repeated.
    private var needsSignIn = false
    private var lastRenewal: Date?

    init(instanceId: UUID) {
        self.instanceId = instanceId
        self.engine = BaseNetworkEngine(serviceType: .nginxProxyManager, instanceId: instanceId)
    }

    // MARK: - Configuration

    func configure(url: String, token: String, fallbackUrl: String? = nil, email: String? = nil, password: String? = nil, allowSelfSigned: Bool? = nil) {
        self.baseURL = Self.cleanURL(url)
        self.fallbackURL = Self.cleanURL(fallbackUrl ?? "")
        if token != self.token { needsSignIn = false }
        self.token = token
        if let email, !email.isEmpty {
            self.email = email
        }
        if let password, !password.isEmpty {
            self.storedPassword = password
        }
    
        if let allowSelfSigned {
            storedAllowSelfSigned = allowSelfSigned
        }
        engine = BaseNetworkEngine(serviceType: .nginxProxyManager, instanceId: self.instanceId, allowSelfSigned: self.storedAllowSelfSigned)
    }

    func setTokenRefreshCallback(_ callback: @escaping @Sendable (String) -> Void) {
        self.onTokenRefreshed = callback
    }

    func setSignInRequiredCallback(_ callback: @escaping @Sendable (String) -> Void) {
        self.onSignInRequired = callback
    }

    /// Extends a valid session in the background so 2FA is not asked for needlessly.
    private func renewIfNeeded() {
        guard TokenLifetime.shouldRenew(token, assumedLifetime: 86_400) else { return }
        if let lastRenewal, Date().timeIntervalSince(lastRenewal) < 600 { return }
        lastRenewal = Date()
        Task { await renew() }
    }

    private func renew() async {
        let current = token
        let session = BaseNetworkEngine.authSession(instanceId: instanceId, allowSelfSigned: storedAllowSelfSigned, timeout: 8)
        for base in [baseURL, fallbackURL] where !base.isEmpty {
            guard let url = URL(string: "\(base)/api/tokens") else { continue }
            var req = URLRequest(url: url)
            req.httpMethod = "GET"
            authHeaders().forEach { req.setValue($1, forHTTPHeaderField: $0) }
            guard let (data, resp) = try? await session.data(for: req),
                  let http = resp as? HTTPURLResponse, http.statusCode == 200,
                  let renewed = try? NpmLogin.token(from: data, setCookie: http.value(forHTTPHeaderField: "Set-Cookie"), baseURL: base),
                  !renewed.isEmpty, token == current else { continue }
            token = renewed
            onTokenRefreshed?(renewed)
            return
        }
    }

    private func authHeaders() -> [String: String] {
        [
            "Content-Type": "application/json",
            "Authorization": "Bearer \(token)",
            // NPMplus uses cookie-based auth instead of Bearer
            "Cookie": "token=\(token)"
        ]
    }

    // MARK: - Ping

    func ping() async -> Bool {
        if baseURL.isEmpty { return false }
        if await engine.pingURL("\(baseURL)/api/", extraHeaders: [:]) { return true }
        if !fallbackURL.isEmpty {
            return await engine.pingURL("\(fallbackURL)/api/", extraHeaders: [:])
        }
        return false
    }

    // MARK: - Authentication

    func authenticate(url: String, email: String, password: String, fallbackUrl: String? = nil) async throws -> String {
        let cleanURL = Self.cleanURL(url)
        do {
            return try await authenticateAgainst(url: cleanURL, email: email, password: password)
        } catch {
            if error.needsUserSignIn { throw error }
            let cleanFallback = Self.cleanURL(fallbackUrl ?? "")
            guard !cleanFallback.isEmpty, cleanFallback != cleanURL else {
                throw error
            }
            return try await authenticateAgainst(url: cleanFallback, email: email, password: password)
        }
    }

    private func authenticateAgainst(url: String, email: String, password: String) async throws -> String {
        guard let authURL = URL(string: "\(url)/api/tokens") else {
            throw APIError.invalidURL
        }

        let body = try JSONEncoder().encode(["identity": email, "secret": password])
        var req = URLRequest(url: authURL)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = body
        req.timeoutInterval = 8

        let session = BaseNetworkEngine.authSession(instanceId: instanceId, allowSelfSigned: storedAllowSelfSigned, timeout: 8)
        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw APIError.custom("Authentication failed. Check your email and password.")
        }

        return try NpmLogin.token(from: data, setCookie: http.value(forHTTPHeaderField: "Set-Cookie"), baseURL: url)
    }

    func completeTwoFactor(_ challenge: TwoFactorChallenge, code: String) async throws -> String {
        guard case .authenticator(let challengeToken) = challenge.kind,
              let url = URL(string: "\(challenge.baseURL)/api/tokens/2fa") else { throw APIError.invalidURL }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(["challenge_token": challengeToken, "code": code])

        let session = BaseNetworkEngine.authSession(instanceId: instanceId, allowSelfSigned: storedAllowSelfSigned, timeout: 8)
        let (data, resp) = try await session.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if [400, 401, 403].contains(status) { throw APIError.twoFactorInvalid }
        guard status == 200 else {
            throw APIError.httpError(statusCode: status, body: String(decoding: data, as: UTF8.self))
        }
        let cookie = (resp as? HTTPURLResponse)?.value(forHTTPHeaderField: "Set-Cookie")
        return try NpmLogin.token(from: data, setCookie: cookie, baseURL: challenge.baseURL)
    }

    private func refreshToken() async -> Bool {
        guard !email.isEmpty, !storedPassword.isEmpty, !isRefreshing, !needsSignIn else { return false }
        isRefreshing = true
        defer { isRefreshing = false }

        do {
            let authURL = baseURL.isEmpty ? fallbackURL : baseURL
            let newToken = try await authenticate(url: authURL, email: email, password: storedPassword, fallbackUrl: fallbackURL)
            token = newToken
            onTokenRefreshed?(newToken)
            return true
        } catch {
            if error.needsUserSignIn {
                needsSignIn = true
                onSignInRequired?(token)
            }
            return false
        }
    }

    private func withAuthRetry<T>(_ operation: () async throws -> T) async throws -> T {
        renewIfNeeded()
        do {
            return try await operation()
        } catch {
            if isAuthError(error), await refreshToken() {
                return try await operation()
            }
            if needsSignIn, isAuthError(error) { throw APIError.signInRequired }
            throw error
        }
    }

    private func isAuthError(_ error: Error) -> Bool {
        guard let apiError = error as? APIError else { return false }
        switch apiError {
        case .unauthorized:
            return true
        case .httpError(let statusCode, let body):
            if statusCode == 401 {
                return true
            }
            if statusCode == 400 {
                let lowered = body.lowercased()
                return lowered.contains("tokenexpirederror") ||
                    lowered.contains("token has expired") ||
                    lowered.contains("jwt expired")
            }
            return false
        case .bothURLsFailed(let primaryError, let fallbackError):
            return isAuthError(primaryError) || isAuthError(fallbackError)
        default:
            return false
        }
    }

    private func authenticatedRequest<T: Decodable>(path: String, method: String = "GET", body: Data? = nil) async throws -> T {
        return try await withAuthRetry {
            try await engine.request(
                baseURL: baseURL,
                fallbackURL: fallbackURL,
                path: path,
                method: method,
                headers: authHeaders(),
                body: body
            )
        }
    }

    private func authenticatedVoidRequest(path: String, method: String = "POST", body: Data? = nil) async throws {
        try await withAuthRetry {
            try await engine.requestVoid(
                baseURL: baseURL,
                fallbackURL: fallbackURL,
                path: path,
                method: method,
                headers: authHeaders(),
                body: body
            )
        }
    }

    // MARK: - Host Report

    func getHostReport() async throws -> NpmHostReport {
        return try await authenticatedRequest(path: "/api/reports/hosts")
    }

    // MARK: - Proxy Hosts

    func getProxyHosts() async throws -> [NpmProxyHost] {
        return try await authenticatedRequest(path: "/api/nginx/proxy-hosts?expand=certificate")
    }

    func createProxyHost(_ request: NpmProxyHostRequest) async throws -> NpmProxyHost {
        return try await authenticatedRequest(
            path: "/api/nginx/proxy-hosts",
            method: "POST",
            body: try request.toJSONData()
        )
    }

    func updateProxyHost(id: Int, _ request: NpmProxyHostRequest) async throws -> NpmProxyHost {
        return try await authenticatedRequest(
            path: "/api/nginx/proxy-hosts/\(id)",
            method: "PUT",
            body: try request.toJSONData()
        )
    }

    func deleteProxyHost(id: Int) async throws {
        try await authenticatedVoidRequest(path: "/api/nginx/proxy-hosts/\(id)", method: "DELETE")
    }

    func enableProxyHost(id: Int) async throws {
        try await authenticatedVoidRequest(path: "/api/nginx/proxy-hosts/\(id)/enable", method: "POST")
    }

    func disableProxyHost(id: Int) async throws {
        try await authenticatedVoidRequest(path: "/api/nginx/proxy-hosts/\(id)/disable", method: "POST")
    }

    // MARK: - Redirection Hosts

    func getRedirectionHosts() async throws -> [NpmRedirectionHost] {
        return try await authenticatedRequest(path: "/api/nginx/redirection-hosts?expand=certificate")
    }

    func createRedirectionHost(_ request: NpmRedirectionHostRequest) async throws -> NpmRedirectionHost {
        return try await authenticatedRequest(
            path: "/api/nginx/redirection-hosts",
            method: "POST",
            body: try request.toJSONData()
        )
    }

    func updateRedirectionHost(id: Int, _ request: NpmRedirectionHostRequest) async throws -> NpmRedirectionHost {
        return try await authenticatedRequest(
            path: "/api/nginx/redirection-hosts/\(id)",
            method: "PUT",
            body: try request.toJSONData()
        )
    }

    func deleteRedirectionHost(id: Int) async throws {
        try await authenticatedVoidRequest(path: "/api/nginx/redirection-hosts/\(id)", method: "DELETE")
    }

    // MARK: - Streams

    func getStreams() async throws -> [NpmStream] {
        return try await authenticatedRequest(path: "/api/nginx/streams")
    }

    func createStream(_ request: NpmStreamRequest) async throws -> NpmStream {
        return try await authenticatedRequest(
            path: "/api/nginx/streams",
            method: "POST",
            body: try request.toJSONData()
        )
    }

    func updateStream(id: Int, _ request: NpmStreamRequest) async throws -> NpmStream {
        return try await authenticatedRequest(
            path: "/api/nginx/streams/\(id)",
            method: "PUT",
            body: try request.toJSONData()
        )
    }

    func deleteStream(id: Int) async throws {
        try await authenticatedVoidRequest(path: "/api/nginx/streams/\(id)", method: "DELETE")
    }

    // MARK: - Dead Hosts

    func getDeadHosts() async throws -> [NpmDeadHost] {
        return try await authenticatedRequest(path: "/api/nginx/dead-hosts")
    }

    func createDeadHost(_ request: NpmDeadHostRequest) async throws -> NpmDeadHost {
        return try await authenticatedRequest(
            path: "/api/nginx/dead-hosts",
            method: "POST",
            body: try request.toJSONData()
        )
    }

    func updateDeadHost(id: Int, _ request: NpmDeadHostRequest) async throws -> NpmDeadHost {
        return try await authenticatedRequest(
            path: "/api/nginx/dead-hosts/\(id)",
            method: "PUT",
            body: try request.toJSONData()
        )
    }

    func deleteDeadHost(id: Int) async throws {
        try await authenticatedVoidRequest(path: "/api/nginx/dead-hosts/\(id)", method: "DELETE")
    }

    // MARK: - Certificates

    func getCertificates() async throws -> [NpmCertificate] {
        return try await authenticatedRequest(path: "/api/nginx/certificates")
    }

    func createCertificate(_ request: NpmCertificateRequest) async throws -> NpmCertificate {
        return try await authenticatedRequest(
            path: "/api/nginx/certificates",
            method: "POST",
            body: try request.toJSONData()
        )
    }

    func deleteCertificate(id: Int) async throws {
        try await authenticatedVoidRequest(path: "/api/nginx/certificates/\(id)", method: "DELETE")
    }

    func renewCertificate(id: Int) async throws -> NpmCertificate {
        return try await authenticatedRequest(path: "/api/nginx/certificates/\(id)/renew", method: "POST")
    }

    // MARK: - Access Lists

    func getAccessLists() async throws -> [NpmAccessList] {
        return try await authenticatedRequest(path: "/api/nginx/access-lists?expand=items,clients")
    }

    func createAccessList(_ request: NpmAccessListRequest) async throws -> NpmAccessList {
        return try await authenticatedRequest(
            path: "/api/nginx/access-lists",
            method: "POST",
            body: try request.toJSONData()
        )
    }

    func updateAccessList(id: Int, _ request: NpmAccessListRequest) async throws -> NpmAccessList {
        return try await authenticatedRequest(
            path: "/api/nginx/access-lists/\(id)",
            method: "PUT",
            body: try request.toJSONData()
        )
    }

    func deleteAccessList(id: Int) async throws {
        try await authenticatedVoidRequest(path: "/api/nginx/access-lists/\(id)", method: "DELETE")
    }

    // MARK: - Users / Audit Logs / Settings

    func getUsers() async throws -> [NpmUser] {
        return try await authenticatedRequest(path: "/api/users")
    }

    func createUser(_ request: NpmUserRequest) async throws -> NpmUser {
        return try await authenticatedRequest(
            path: "/api/users",
            method: "POST",
            body: try request.toJSONData()
        )
    }

    func updateUser(id: Int, _ request: NpmUserRequest) async throws -> NpmUser {
        return try await authenticatedRequest(
            path: "/api/users/\(id)",
            method: "PUT",
            body: try request.toJSONData()
        )
    }

    func deleteUser(id: Int) async throws {
        try await authenticatedVoidRequest(path: "/api/users/\(id)", method: "DELETE")
    }

    func getAuditLogs() async throws -> [NpmAuditLog] {
        return try await authenticatedRequest(path: "/api/audit-log")
    }

    func getSettings() async throws -> [NpmSetting] {
        return try await authenticatedRequest(path: "/api/settings")
    }

    // MARK: - Helpers

    private static func cleanURL(_ url: String) -> String {
        url.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "/+$", with: "", options: .regularExpression)
    }
}
