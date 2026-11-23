import Foundation

/// Scheme + host + port. `ws`/`wss` are treated as `http`/`https`.
struct AccessOrigin: Codable, Hashable, Sendable {
    let scheme: String
    let host: String
    let port: Int

    init?(url: URL) {
        guard let rawScheme = url.scheme?.lowercased(),
              let host = url.host?.lowercased(), !host.isEmpty else { return nil }
        switch rawScheme {
        case "http", "ws": scheme = "http"
        case "https", "wss": scheme = "https"
        default: return nil
        }
        self.host = host
        port = url.port ?? (scheme == "https" ? 443 : 80)
    }

    init?(string: String?) {
        guard let string, let url = URL(string: string) else { return nil }
        self.init(url: url)
    }

    var isEncrypted: Bool { scheme == "https" }
}

struct AccessHeader: Codable, Hashable, Sendable {
    var name: String
    var value: String

    /// Headers the HTTP stack or the cookie merger owns.
    private static let reserved: Set<String> = [
        "host", "cookie", "content-length", "content-type", "connection",
        "transfer-encoding", "upgrade", "origin", "te", "trailer"
    ]
    private static let tokenCharacters = CharacterSet(
        charactersIn: "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    )

    var isValid: Bool {
        !name.isEmpty
            && name.unicodeScalars.allSatisfy(Self.tokenCharacters.contains)
            && !Self.reserved.contains(name.lowercased())
            && !value.isEmpty
            && !value.unicodeScalars.contains { $0.value < 32 || $0.value == 127 }
    }
}

struct AccessCookie: Codable, Hashable, Sendable {
    let name: String
    let value: String
    let path: String
    let expiresAt: Date?

    func applies(to requestPath: String, now: Date) -> Bool {
        if let expiresAt, expiresAt <= now { return false }
        let current = requestPath.isEmpty ? "/" : requestPath
        guard current.hasPrefix(path) else { return false }
        return path.hasSuffix("/") || current.count == path.count
            || current.dropFirst(path.count).first == "/"
    }
}

/// Credentials for the proxy in front of a single origin.
struct OriginAccess: Codable, Hashable, Sendable {
    let origin: AccessOrigin
    var headers: [AccessHeader] = []
    var cookies: [AccessCookie] = []
}

/// How to get past an authenticating proxy before talking to a service.
struct AccessProfile: Codable, Hashable, Sendable {
    enum Method: String, Codable, Sendable { case browser, headers }

    var isEnabled = false
    var method: Method = .browser
    var usesNativeLogin = true
    /// Explicit consent to send proxy credentials without verified TLS.
    var allowsInsecureTransport = false
    var origins: [OriginAccess] = []

    func access(for url: URL?) -> OriginAccess? {
        guard isEnabled, let url, let origin = AccessOrigin(url: url) else { return nil }
        return origins.first { $0.origin == origin }
    }

    /// Drops credentials for origins the instance no longer uses.
    func limited(to urls: [String?]) -> AccessProfile {
        let allowed = Set(urls.compactMap { AccessOrigin(string: $0) })
        var copy = self
        copy.origins = origins.filter { allowed.contains($0.origin) }
        return copy
    }

    /// Safe to export: never contains session cookies.
    var withoutCookies: AccessProfile {
        var copy = self
        copy.origins = origins.map { OriginAccess(origin: $0.origin, headers: $0.headers) }
        return copy
    }

    /// Returns the request with proxy credentials, or `nil` if none apply to its origin.
    func prepared(_ request: URLRequest, allowSelfSigned: Bool, now: Date = Date()) throws -> URLRequest? {
        guard let access = access(for: request.url) else { return nil }
        guard allowsInsecureTransport || (access.origin.isEncrypted && !allowSelfSigned) else {
            throw APIError.forwardAuthInsecure
        }

        var result = request
        // The service's own headers win over the proxy's.
        for header in access.headers
        where header.isValid && request.value(forHTTPHeaderField: header.name) == nil {
            result.setValue(header.value, forHTTPHeaderField: header.name)
        }

        let path = request.url?.path(percentEncoded: true) ?? "/"
        let active = access.cookies
            .filter { $0.applies(to: path, now: now) }
            .sorted { $0.path.count > $1.path.count }
        if let merged = Self.mergeCookies(request.value(forHTTPHeaderField: "Cookie"), with: active) {
            result.setValue(merged, forHTTPHeaderField: "Cookie")
        }
        // Never keep proxied responses in the shared disk cache.
        result.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        return result
    }

    /// The service's own cookies win on name conflicts.
    static func mergeCookies(_ existing: String?, with cookies: [AccessCookie]) -> String? {
        var pairs = (existing ?? "")
            .split(separator: ";")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        var names = Set(pairs.map { String($0.prefix { $0 != "=" }) })
        for cookie in cookies where names.insert(cookie.name).inserted {
            pairs.append("\(cookie.name)=\(cookie.value)")
        }
        return pairs.isEmpty ? nil : pairs.joined(separator: "; ")
    }
}
