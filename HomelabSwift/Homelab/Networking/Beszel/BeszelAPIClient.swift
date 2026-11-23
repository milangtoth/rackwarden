import Foundation

actor BeszelAPIClient {
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
        self.engine = BaseNetworkEngine(serviceType: .beszel, instanceId: instanceId)
    }

    // MARK: - Configuration

    func configure(url: String, token: String, fallbackUrl: String? = nil, email: String? = nil, password: String? = nil, allowSelfSigned: Bool? = nil) {
        self.baseURL = Self.cleanURL(url)
        self.fallbackURL = Self.cleanURL(fallbackUrl ?? "")
        if token != self.token { needsSignIn = false }
        self.token = token
        if let email, !email.isEmpty { self.email = email }
        if let password, !password.isEmpty { self.storedPassword = password }
    
        if let allowSelfSigned {
            storedAllowSelfSigned = allowSelfSigned
        }
        engine = BaseNetworkEngine(serviceType: .beszel, instanceId: self.instanceId, allowSelfSigned: self.storedAllowSelfSigned)
    }

    /// Set a callback invoked after successful token refresh so the store can persist it
    func setTokenRefreshCallback(_ callback: @escaping @Sendable (String) -> Void) {
        self.onTokenRefreshed = callback
    }

    func setSignInRequiredCallback(_ callback: @escaping @Sendable (String) -> Void) {
        self.onSignInRequired = callback
    }

    /// Extends a valid session in the background so 2FA is not asked for needlessly.
    private func renewIfNeeded() {
        guard TokenLifetime.shouldRenew(token, assumedLifetime: 604_800) else { return }
        if let lastRenewal, Date().timeIntervalSince(lastRenewal) < 600 { return }
        lastRenewal = Date()
        Task { await renew() }
    }

    private func renew() async {
        let current = token
        let session = BaseNetworkEngine.authSession(instanceId: instanceId, allowSelfSigned: storedAllowSelfSigned, timeout: 8)
        for base in [baseURL, fallbackURL] where !base.isEmpty {
            guard let url = URL(string: "\(base)/api/collections/users/auth-refresh") else { continue }
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            authHeaders().forEach { req.setValue($1, forHTTPHeaderField: $0) }
            guard let (data, resp) = try? await session.data(for: req),
                  let http = resp as? HTTPURLResponse, http.statusCode == 200,
                  let renewed = (try? JSONDecoder().decode(BeszelAuthResponse.self, from: data))?.token,
                  !renewed.isEmpty, token == current else { continue }
            token = renewed
            onTokenRefreshed?(renewed)
            return
        }
    }

    private func authHeaders() -> [String: String] {
        ["Content-Type": "application/json", "Authorization": "Bearer \(token)"]
    }

    /// Attempts to refresh the token using stored credentials
    /// - Parameter expectsExpiry: false when the token may still be fine and only looks rejected.
    private func refreshToken(expectsExpiry: Bool = true) async -> Bool {
        guard !email.isEmpty, !storedPassword.isEmpty, !isRefreshing, !needsSignIn else { return false }
        isRefreshing = true
        defer { isRefreshing = false }

        do {
            let newToken = try await authenticate(
                url: baseURL.isEmpty ? fallbackURL : baseURL,
                email: email,
                password: storedPassword,
                fallbackUrl: fallbackURL
            )
            token = newToken
            onTokenRefreshed?(newToken)
            return true
        } catch {
            if error.needsUserSignIn, expectsExpiry || TokenLifetime.isExpired(token) {
                needsSignIn = true
                onSignInRequired?(token)
            }
            return false
        }
    }

    /// Wrapper that retries once after token refresh on auth failure (400/401)
    private func authenticatedRequest<T: Decodable>(path: String, method: String = "GET", headers: [String: String]? = nil, body: Data? = nil) async throws -> T {
        renewIfNeeded()
        let h = headers ?? authHeaders()
        do {
            return try await engine.request(baseURL: baseURL, fallbackURL: fallbackURL, path: path, method: method, headers: h, body: body)
        } catch {
            if isAuthError(error), await refreshToken() {
                return try await engine.request(baseURL: baseURL, fallbackURL: fallbackURL, path: path, method: method, headers: authHeaders(), body: body)
            }
            if needsSignIn, isAuthError(error) { throw APIError.signInRequired }
            throw error
        }
    }

    private func isAuthError(_ error: Error) -> Bool {
        guard let apiError = error as? APIError else { return false }
        switch apiError {
        case .httpError(let code, _): return code == 400 || code == 401 || code == 403
        case .unauthorized: return true
        case .bothURLsFailed(let primary, let fallback):
            return isAuthError(primary) || isAuthError(fallback)
        default: return false
        }
    }

    // MARK: - Ping

    func ping() async -> Bool {
        if baseURL.isEmpty { return false }
        if await engine.pingURL("\(baseURL)/api/health", extraHeaders: authHeaders()) { return true }
        if !fallbackURL.isEmpty {
            return await engine.pingURL("\(fallbackURL)/api/health", extraHeaders: authHeaders())
        }
        return false
    }

    // MARK: - Authentication (PocketBase)

    func authenticate(url: String, email: String, password: String, fallbackUrl: String? = nil) async throws -> String {
        let cleanURL = Self.cleanURL(url)
        do {
            return try await authenticateAgainst(url: cleanURL, email: email, password: password)
        } catch {
            if error.needsUserSignIn { throw error }
            let cleanFallback = Self.cleanURL(fallbackUrl ?? "")
            guard !cleanFallback.isEmpty, cleanFallback != cleanURL else { throw error }
            return try await authenticateAgainst(url: cleanFallback, email: email, password: password)
        }
    }

    private func authenticateAgainst(url: String, email: String, password: String) async throws -> String {
        guard let authURL = URL(string: "\(url)/api/collections/users/auth-with-password") else {
            throw APIError.invalidURL
        }

        let body = try JSONEncoder().encode(["identity": email, "password": password])
        var req = URLRequest(url: authURL)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = body
        req.timeoutInterval = 8

        let session = BaseNetworkEngine.authSession(instanceId: instanceId, allowSelfSigned: storedAllowSelfSigned, timeout: 8)
        let (data, resp) = try await session.data(for: req)
        if let http = resp as? HTTPURLResponse,
           let mfaId = BeszelLogin.mfaId(statusCode: http.statusCode, data: data) {
            // The email is only requested once the user is there to read it.
            throw APIError.twoFactorRequired(
                TwoFactorChallenge(baseURL: url, kind: .email(address: email, mfaId: mfaId, otpId: nil))
            )
        }
        guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else {
            throw APIError.custom("Authentication failed. Check your credentials and URL.")
        }

        let decoded = try JSONDecoder().decode(BeszelAuthResponse.self, from: data)
        return decoded.token
    }

    // MARK: - Two-factor (PocketBase email OTP)

    /// Emails a code and returns the challenge to complete with it.
    func requestTwoFactorCode(_ challenge: TwoFactorChallenge) async throws -> TwoFactorChallenge {
        guard case .email(let address, let mfaId, _) = challenge.kind else { throw APIError.invalidURL }
        struct Response: Decodable { let otpId: String }
        let data = try await postTwoFactor(challenge, path: "request-otp", body: ["email": address])
        let otpId = try JSONDecoder().decode(Response.self, from: data).otpId
        return TwoFactorChallenge(
            baseURL: challenge.baseURL,
            kind: .email(address: address, mfaId: mfaId, otpId: otpId)
        )
    }

    func completeTwoFactor(_ challenge: TwoFactorChallenge, code: String) async throws -> String {
        guard case .email(_, let mfaId, let otpId?) = challenge.kind else { throw APIError.invalidURL }
        let data = try await postTwoFactor(
            challenge,
            path: "auth-with-otp",
            body: ["otpId": otpId, "password": code, "mfaId": mfaId],
            rejectsCode: true
        )
        return try JSONDecoder().decode(BeszelAuthResponse.self, from: data).token
    }

    private func postTwoFactor(
        _ challenge: TwoFactorChallenge,
        path: String,
        body: [String: String],
        rejectsCode: Bool = false
    ) async throws -> Data {
        guard let url = URL(string: "\(challenge.baseURL)/api/collections/users/\(path)") else {
            throw APIError.invalidURL
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(body)

        let session = BaseNetworkEngine.authSession(instanceId: instanceId, allowSelfSigned: storedAllowSelfSigned, timeout: 15)
        let (data, resp) = try await session.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if rejectsCode, [400, 401].contains(status) { throw APIError.twoFactorInvalid }
        guard status == 200 else {
            throw APIError.httpError(statusCode: status, body: String(decoding: data, as: UTF8.self))
        }
        return data
    }

    // MARK: - Systems

    func getSystems() async throws -> BeszelSystemsResponse {
        let response: BeszelSystemsResponse = try await authenticatedRequest(path: "/api/collections/systems/records?sort=-updated&perPage=50")
        if response.items.isEmpty, await refreshToken(expectsExpiry: false) {
            return try await authenticatedRequest(path: "/api/collections/systems/records?sort=-updated&perPage=50")
        }
        // PocketBase answers an expired session with an empty list, not an error.
        if response.items.isEmpty, needsSignIn { throw APIError.signInRequired }
        return response
    }

    func getSystem(id: String) async throws -> BeszelSystem {
        return try await authenticatedRequest(path: "/api/collections/systems/records/\(id)")
    }

    func getSystemRecords(systemId: String, limit: Int = 60) async throws -> BeszelRecordsResponse {
        let filter = "system='\(systemId)'".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        return try await authenticatedRequest(path: "/api/collections/system_stats/records?filter=(\(filter))&sort=-created&perPage=\(limit)")
    }

    // MARK: - System Details

    func getSystemDetails(systemId: String) async throws -> BeszelSystemDetails? {
        let filter = "system='\(systemId)'".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let response: BeszelSystemDetailsResponse = try await authenticatedRequest(path: "/api/collections/system_details/records?filter=(\(filter))&perPage=1")
        return response.items.first
    }

    // MARK: - S.M.A.R.T. Devices

    func getSmartDevices(systemId: String) async throws -> [BeszelSmartDevice] {
        let filter = "system='\(systemId)'".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let response: BeszelSmartDevicesResponse = try await authenticatedRequest(path: "/api/collections/smart_devices/records?filter=\(filter)&perPage=50")
        return response.items
    }

    // MARK: - Containers

    func getContainers(systemId: String) async throws -> [BeszelContainerRecord] {
        let filter = "system='\(systemId)'".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let response: BeszelContainersResponse = try await authenticatedRequest(path: "/api/collections/containers/records?filter=(\(filter))&perPage=200")
        return response.items
    }

    func getContainerStats(systemId: String, limit: Int = 60) async throws -> [BeszelContainerStatsRecord] {
        let filter = "system='\(systemId)'".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let response: BeszelContainerStatsResponse = try await authenticatedRequest(path: "/api/collections/container_stats/records?filter=(\(filter))&sort=-created&perPage=\(limit)")
        return response.items
    }

    func getContainerLogs(systemId: String, containerId: String) async throws -> String {
        let system = systemId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? systemId
        let container = containerId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? containerId
        let path = "/api/beszel/containers/logs?system=\(system)&container=\(container)"
        do {
            return try await engine.requestString(
                baseURL: baseURL, fallbackURL: fallbackURL,
                path: path,
                headers: authHeaders()
            )
        } catch {
            if isAuthError(error), await refreshToken() {
                return try await engine.requestString(
                    baseURL: baseURL, fallbackURL: fallbackURL,
                    path: path,
                    headers: authHeaders()
                )
            }
            throw error
        }
    }

    func getContainerInfo(systemId: String, containerId: String) async throws -> String {
        let system = systemId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? systemId
        let container = containerId.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? containerId
        let path = "/api/beszel/containers/info?system=\(system)&container=\(container)"
        let data: Data
        do {
            data = try await engine.requestData(
                baseURL: baseURL, fallbackURL: fallbackURL,
                path: path,
                headers: authHeaders()
            )
        } catch {
            if isAuthError(error), await refreshToken() {
                let retryData = try await engine.requestData(
                    baseURL: baseURL, fallbackURL: fallbackURL,
                    path: path,
                    headers: authHeaders()
                )
                if let json = try? JSONSerialization.jsonObject(with: retryData),
                   let pretty = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys]),
                   let prettyString = String(data: pretty, encoding: .utf8) {
                    return prettyString
                }
                return String(data: retryData, encoding: .utf8) ?? ""
            }
            throw error
        }

        if let json = try? JSONSerialization.jsonObject(with: data),
           let pretty = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted, .sortedKeys]),
           let prettyString = String(data: pretty, encoding: .utf8) {
            return prettyString
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    // MARK: - Helpers

    private static func cleanURL(_ url: String) -> String {
        url.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "/+$", with: "", options: .regularExpression)
    }
}
