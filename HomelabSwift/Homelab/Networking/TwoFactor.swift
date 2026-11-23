import Foundation

/// A login that accepted the password and still needs a one-time code.
/// Held in memory only; codes and challenges are never stored.
public struct TwoFactorChallenge: Sendable, Equatable {
    public enum Kind: Sendable, Equatable {
        /// Code from an authenticator app, or a backup code.
        case authenticator(token: String)
        /// Code sent by email. `otpId` is set once the user asked for the email.
        case email(address: String, mfaId: String, otpId: String?)
    }

    let baseURL: String
    let kind: Kind
}

extension Error {
    /// True when signing in again needs the user, so silent retries must stop.
    var needsUserSignIn: Bool {
        guard let error = self as? APIError else { return false }
        switch error {
        case .twoFactorRequired, .signInRequired:
            return true
        case .bothURLsFailed(let primary, let fallback):
            return primary.needsUserSignIn || fallback.needsUserSignIn
        default:
            return false
        }
    }
}

enum NpmLogin {
    private struct Body: Decodable {
        struct Inner: Decodable { let token: String? }
        let token: String?
        let result: Inner?
        let requires_2fa: Bool?
        let challenge_token: String?
    }

    /// Returns the session token, or throws `twoFactorRequired`.
    static func token(from data: Data, setCookie: String?, baseURL: String) throws -> String {
        let body = try JSONDecoder().decode(Body.self, from: data)
        if body.requires_2fa == true {
            guard let challenge = body.challenge_token, !challenge.isEmpty else {
                throw APIError.custom("Authentication failed. Empty token received.")
            }
            throw APIError.twoFactorRequired(
                TwoFactorChallenge(baseURL: baseURL, kind: .authenticator(token: challenge))
            )
        }
        if let token = body.result?.token ?? body.token, !token.isEmpty { return token }

        // NPMplus returns the token as a cookie.
        for part in (setCookie ?? "").components(separatedBy: CharacterSet(charactersIn: ",;")) {
            let trimmed = part.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("token="), trimmed.count > "token=".count {
                return String(trimmed.dropFirst("token=".count))
            }
        }
        throw APIError.custom("Authentication failed. Empty token received.")
    }
}

enum BeszelLogin {
    private struct MFA: Decodable { let mfaId: String? }

    /// The MFA identifier PocketBase sends with a 401 when a second factor is needed.
    static func mfaId(statusCode: Int, data: Data) -> String? {
        guard statusCode == 401,
              let id = (try? JSONDecoder().decode(MFA.self, from: data))?.mfaId, !id.isEmpty
        else { return nil }
        return id
    }
}

enum TokenLifetime {
    private struct Claims: Decodable {
        let iat: Double?
        let exp: Double?
    }

    static func isExpired(_ token: String, now: Date = Date()) -> Bool {
        guard let exp = claims(token)?.exp else { return false }
        return exp <= now.timeIntervalSince1970
    }

    /// True in the second half of a still-valid JWT's life.
    /// `assumedLifetime` covers tokens without an issue time.
    static func shouldRenew(_ token: String, assumedLifetime: TimeInterval, now: Date = Date()) -> Bool {
        guard let claims = claims(token), let exp = claims.exp else { return false }
        let remaining = exp - now.timeIntervalSince1970
        let lifetime = claims.iat.map { exp - $0 } ?? assumedLifetime
        return remaining > 0 && remaining < lifetime / 2
    }

    private static func claims(_ token: String) -> Claims? {
        let parts = token.split(separator: ".")
        guard parts.count == 3 else { return nil }
        var payload = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        payload += String(repeating: "=", count: (4 - payload.count % 4) % 4)
        guard let data = Data(base64Encoded: payload) else { return nil }
        return try? JSONDecoder().decode(Claims.self, from: data)
    }
}
