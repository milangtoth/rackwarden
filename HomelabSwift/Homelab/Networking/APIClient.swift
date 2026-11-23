import Foundation

// MARK: - Notifications for 401 interception

extension Notification.Name {
    static let serviceUnauthorized = Notification.Name("serviceUnauthorized")
}

// MARK: - Base networking engine (used via composition, NOT inheritance)
// Swift actors cannot inherit from other actors. Each API client actor
// owns a BaseNetworkEngine instance to share the common request logic.

final class BaseNetworkEngine: Sendable {
    let serviceType: ServiceType
    let instanceId: UUID
    private let allowSelfSigned: Bool
    private let timeoutInterval: TimeInterval = 8
    private let pingTimeout: TimeInterval = 3

    // MARK: - Shared delegates & sessions

    private static let insecureDelegate = InsecureTrustDelegate()
    private static let secureDelegate = SecureTrustDelegate()

    static let insecureDelegateForPortainerAuth: URLSessionDelegate = insecureDelegate

    // Insecure sessions (self-signed certs allowed — default for homelab)
    private static let insecureRequestSession: URLSession = {
        makeSession(delegate: insecureDelegate, timeout: 8)
    }()
    private static let insecurePingSession: URLSession = {
        makeSession(delegate: insecureDelegate, timeout: 3)
    }()
    private static let insecureImageSession: URLSession = {
        makeSession(delegate: insecureDelegate, timeout: 8, cachePolicy: .returnCacheDataElseLoad)
    }()

    // Secure sessions (standard TLS validation — used when allowSelfSigned = false)
    private static let secureRequestSession: URLSession = {
        makeSession(delegate: secureDelegate, timeout: 8)
    }()
    private static let securePingSession: URLSession = {
        makeSession(delegate: secureDelegate, timeout: 3)
    }()
    private static let secureImageSession: URLSession = {
        makeSession(delegate: secureDelegate, timeout: 8, cachePolicy: .returnCacheDataElseLoad)
    }()

    private static func makeSession(
        delegate: URLSessionDelegate,
        timeout: TimeInterval,
        cachePolicy: URLRequest.CachePolicy = .useProtocolCachePolicy
    ) -> URLSession {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.requestCachePolicy = cachePolicy
        return URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    }

    /// Session for calls made outside the engine, such as logins and WebSockets.
    static func authSession(instanceId: UUID, allowSelfSigned: Bool, timeout: TimeInterval) -> AccessSession {
        AccessSession(
            session: makeSession(delegate: allowSelfSigned ? insecureDelegate : secureDelegate, timeout: timeout),
            instanceId: instanceId,
            allowSelfSigned: allowSelfSigned
        )
    }

    init(serviceType: ServiceType, instanceId: UUID, allowSelfSigned: Bool = true) {
        self.serviceType = serviceType
        self.instanceId = instanceId
        self.allowSelfSigned = allowSelfSigned
    }

    // MARK: - Session selection

    private var requestSession: AccessSession {
        access(allowSelfSigned ? Self.insecureRequestSession : Self.secureRequestSession)
    }

    private var pingSession: AccessSession {
        access(allowSelfSigned ? Self.insecurePingSession : Self.securePingSession)
    }

    private func access(_ session: URLSession) -> AccessSession {
        AccessSession(session: session, instanceId: instanceId, allowSelfSigned: allowSelfSigned)
    }

    static func imageData(
        from url: URL,
        headers: [String: String] = [:],
        instanceId: UUID? = nil,
        allowSelfSigned: Bool = true
    ) async throws -> Data {
        var request = URLRequest(url: url)
        request.timeoutInterval = 8
        headers.forEach { key, value in
            request.setValue(value, forHTTPHeaderField: key)
        }
        let session = allowSelfSigned ? Self.insecureImageSession : Self.secureImageSession
        let (data, response): (Data, URLResponse)
        if let instanceId {
            (data, response) = try await AccessSession(
                session: session, instanceId: instanceId, allowSelfSigned: allowSelfSigned
            ).data(for: request)
        } else {
            (data, response) = try await session.data(for: request)
        }
        guard let http = response as? HTTPURLResponse else {
            throw APIError.custom("Invalid image response")
        }
        guard (200...399).contains(http.statusCode) else {
            throw APIError.httpError(statusCode: http.statusCode, body: "")
        }
        return data
    }

    // MARK: - Core Request (primary → fallback)

    func request<T: Decodable>(
        baseURL: String,
        fallbackURL: String,
        path: String,
        method: String = "GET",
        headers: [String: String] = [:],
        body: Data? = nil
    ) async throws -> T {
        guard !baseURL.isEmpty else { throw APIError.notConfigured }

        do {
            return try await performRequest(baseURL: baseURL, path: path, method: method, headers: headers, body: body)
        } catch let primaryError {
            guard !fallbackURL.isEmpty else { throw primaryError }
            do {
                return try await performRequest(baseURL: fallbackURL, path: path, method: method, headers: headers, body: body)
            } catch let fallbackError {
                throw APIError.bothURLsFailed(primaryError: primaryError, fallbackError: fallbackError)
            }
        }
    }

    /// Request that returns raw String (for logs)
    func requestString(
        baseURL: String,
        fallbackURL: String,
        path: String,
        method: String = "GET",
        headers: [String: String] = [:],
        body: Data? = nil
    ) async throws -> String {
        guard !baseURL.isEmpty else { throw APIError.notConfigured }

        do {
            return try await performStringRequest(baseURL: baseURL, path: path, method: method, headers: headers, body: body)
        } catch let primaryError {
            guard !fallbackURL.isEmpty else { throw primaryError }
            do {
                return try await performStringRequest(baseURL: fallbackURL, path: path, method: method, headers: headers, body: body)
            } catch let fallbackError {
                throw APIError.bothURLsFailed(primaryError: primaryError, fallbackError: fallbackError)
            }
        }
    }

    /// Request that ignores response body (for actions like start/stop)
    func requestVoid(
        baseURL: String,
        fallbackURL: String,
        path: String,
        method: String = "POST",
        headers: [String: String] = [:],
        body: Data? = nil
    ) async throws {
        guard !baseURL.isEmpty else { throw APIError.notConfigured }

        do {
            try await performVoidRequest(baseURL: baseURL, path: path, method: method, headers: headers, body: body)
        } catch let primaryError {
            guard !fallbackURL.isEmpty else { throw primaryError }
            do {
                try await performVoidRequest(baseURL: fallbackURL, path: path, method: method, headers: headers, body: body)
            } catch let fallbackError {
                throw APIError.bothURLsFailed(primaryError: primaryError, fallbackError: fallbackError)
            }
        }
    }

    /// Request that returns raw Data (for PiHole dynamic JSON parsing)
    func requestData(
        baseURL: String,
        fallbackURL: String,
        path: String,
        method: String = "GET",
        headers: [String: String] = [:],
        body: Data? = nil
    ) async throws -> Data {
        guard !baseURL.isEmpty else { throw APIError.notConfigured }

        do {
            return try await performDataRequest(baseURL: baseURL, path: path, method: method, headers: headers, body: body)
        } catch let primaryError {
            guard !fallbackURL.isEmpty else { throw primaryError }
            do {
                return try await performDataRequest(baseURL: fallbackURL, path: path, method: method, headers: headers, body: body)
            } catch let fallbackError {
                throw APIError.bothURLsFailed(primaryError: primaryError, fallbackError: fallbackError)
            }
        }
    }

    // MARK: - Ping Helper

    func pingURL(_ urlString: String, extraHeaders: [String: String] = [:]) async -> Bool {
        guard let url = URL(string: urlString) else { return false }
        var request = URLRequest(url: url)
        request.timeoutInterval = pingTimeout
        for (key, value) in extraHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }
        do {
            let (_, response) = try await pingSession.data(for: request)
            guard let http = response as? HTTPURLResponse else { return false }
            return (200...399).contains(http.statusCode)
        } catch {
            return false
        }
    }

    // MARK: - Private

    private func performRequest<T: Decodable>(
        baseURL: String,
        path: String,
        method: String,
        headers: [String: String],
        body: Data?
    ) async throws -> T {
        let urlString = baseURL + path
        guard let url = URL(string: urlString) else { throw APIError.invalidURL }

        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = timeoutInterval
        for (key, value) in headers {
            req.setValue(value, forHTTPHeaderField: key)
        }
        req.httpBody = body

        logRequest(req)
        let (data, response) = try await requestSession.data(for: req)
        logResponse(response, data: data)
        try interceptResponse(response, data: data)

        let decoder = JSONDecoder()
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError.decodingError(error)
        }
    }

    private func performStringRequest(
        baseURL: String,
        path: String,
        method: String,
        headers: [String: String],
        body: Data?
    ) async throws -> String {
        let urlString = baseURL + path
        guard let url = URL(string: urlString) else { throw APIError.invalidURL }

        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = timeoutInterval
        for (key, value) in headers {
            req.setValue(value, forHTTPHeaderField: key)
        }
        req.httpBody = body

        logRequest(req)
        let (data, response) = try await requestSession.data(for: req)
        logResponse(response, data: data)
        try interceptResponse(response, data: data)

        return String(data: data, encoding: .utf8) ?? ""
    }

    private func performVoidRequest(
        baseURL: String,
        path: String,
        method: String,
        headers: [String: String],
        body: Data?
    ) async throws {
        let urlString = baseURL + path
        guard let url = URL(string: urlString) else { throw APIError.invalidURL }

        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = timeoutInterval
        for (key, value) in headers {
            req.setValue(value, forHTTPHeaderField: key)
        }
        req.httpBody = body

        logRequest(req)
        let (data, response) = try await requestSession.data(for: req)
        logResponse(response, data: data)
        try interceptResponse(response, data: data)
    }

    private func performDataRequest(
        baseURL: String,
        path: String,
        method: String,
        headers: [String: String],
        body: Data?
    ) async throws -> Data {
        let urlString = baseURL + path
        guard let url = URL(string: urlString) else { throw APIError.invalidURL }

        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = timeoutInterval
        for (key, value) in headers {
            req.setValue(value, forHTTPHeaderField: key)
        }
        req.httpBody = body

        logRequest(req)
        let (data, response) = try await requestSession.data(for: req)
        logResponse(response, data: data)
        try interceptResponse(response, data: data)
        return data
    }

    private func logRequest(_ request: URLRequest) {
        let url = request.url?.absoluteString ?? "unknown"
        let method = request.httpMethod ?? "GET"
        AppLogger.shared.network("--> \(method) \(url)", source: serviceType.displayName)
    }

    private func logResponse(_ response: URLResponse, data: Data?) {
        guard let http = response as? HTTPURLResponse else { return }
        let url = response.url?.absoluteString ?? "unknown"
        let status = http.statusCode
        let size = data?.count ?? 0
        let msg = "<-- \(status) \(url) (\(size) bytes)"
        if status >= 400 {
            AppLogger.shared.warn(msg, source: serviceType.displayName)
        } else {
            AppLogger.shared.network(msg, source: serviceType.displayName)
        }
    }

    private func interceptResponse(_ response: URLResponse, data: Data? = nil) throws {
        guard let http = response as? HTTPURLResponse else { return }

        // Detection of HTML when expecting JSON (likely a redirect to a login page)
        if let contentType = http.value(forHTTPHeaderField: "Content-Type"),
           contentType.contains("text/html") {
            // Check if this is a known HTML error or a potential login page
            let bodySnippet = data.flatMap { String(data: $0.prefix(500), encoding: .utf8) } ?? ""
            if bodySnippet.lowercased().contains("<html") {
                 throw APIError.custom("Received an HTML response instead of JSON. This often happens when the service is behind a login page or proxy (OAuth/SSO). Please check your configuration.")
            }
        }

        if http.statusCode == 401 {
            let type = serviceType
            let instanceId = instanceId
            Task { @MainActor in
                NotificationCenter.default.post(
                    name: .serviceUnauthorized,
                    object: nil,
                    userInfo: [
                        "serviceType": type,
                        "instanceId": instanceId
                    ]
                )
            }
            throw APIError.unauthorized
        }

        if http.statusCode >= 400 {
            let body = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            throw APIError.httpError(statusCode: http.statusCode, body: body)
        }
    }
}

/// Adds an instance's proxy credentials to requests and keeps them on their origin.
struct AccessSession: Sendable {
    let session: URLSession
    let instanceId: UUID
    let allowSelfSigned: Bool

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        guard let (prepared, guardian) = try prepare(request) else {
            return try await session.data(for: request)
        }
        let result = try await session.data(for: prepared, delegate: guardian)
        if guardian.wasBlocked { throw APIError.forwardAuthRequired }
        return result
    }

    func webSocketTask(with url: URL) throws -> URLSessionWebSocketTask {
        guard let (prepared, guardian) = try prepare(URLRequest(url: url)) else {
            return session.webSocketTask(with: url)
        }
        let task = session.webSocketTask(with: prepared)
        task.delegate = guardian
        return task
    }

    private func prepare(_ request: URLRequest) throws -> (URLRequest, RedirectGuard)? {
        guard let profile = AccessProfileStore.shared.profile(for: instanceId),
              let prepared = try profile.prepared(request, allowSelfSigned: allowSelfSigned),
              let url = prepared.url, let origin = AccessOrigin(url: url) else { return nil }
        return (prepared, RedirectGuard(origin: origin))
    }
}

/// Stops redirects that would carry proxy credentials to another origin.
final class RedirectGuard: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let origin: AccessOrigin
    private let lock = NSLock()
    private var blocked = false

    init(origin: AccessOrigin) {
        self.origin = origin
    }

    var wasBlocked: Bool { lock.withLock { blocked } }

    func allows(_ url: URL?) -> Bool {
        if let url, AccessOrigin(url: url) == origin { return true }
        lock.withLock { blocked = true }
        return false
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(allows(request.url) ? request : nil)
    }
}

final class InsecureTrustDelegate: NSObject, URLSessionDelegate {
    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        if let trust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }
}

/// Standard TLS validation — rejects self-signed / expired / mismatched certificates.
/// Used when `allowSelfSigned = false` on the service instance.
final class SecureTrustDelegate: NSObject, URLSessionDelegate {
    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let protectionSpace = challenge.protectionSpace
        guard let serverTrust = protectionSpace.serverTrust else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        // Evaluate the server trust using the default system SecTrust evaluation
        var error: CFError?
        let trustResult = SecTrustEvaluateWithError(serverTrust, &error)

        if trustResult {
            completionHandler(.useCredential, URLCredential(trust: serverTrust))
        } else {
            // Certificate is invalid (self-signed, expired, wrong host, etc.)
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}

// MARK: - Encoding helpers

extension Encodable {
    func toJSONData() throws -> Data {
        return try JSONEncoder().encode(self)
    }

    func toJSONBody() -> Data? {
        return try? JSONEncoder().encode(self)
    }
}
