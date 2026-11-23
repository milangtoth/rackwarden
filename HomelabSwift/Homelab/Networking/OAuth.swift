import Foundation

/// The fixed address sign-in providers return to. It has to be registered with the provider.
enum OAuthCallback {
    static let scheme = "rackwarden"
    static let redirectURL = "rackwarden://oauth"

    /// Returns the authorization code, or throws when the reply is not the one that was asked for.
    static func code(from url: URL, state: String) throws -> String {
        let invalid = APIError.custom(Translations.current().oauthInvalidReply)
        guard url.scheme?.lowercased() == scheme, url.host()?.lowercased() == "oauth",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
        else { throw invalid }
        func value(_ name: String) -> String? {
            let found = items.filter { $0.name == name }
            return found.count == 1 ? found[0].value : nil
        }
        guard !state.isEmpty, value("state") == state else { throw invalid }
        if items.contains(where: { $0.name == "error" }) {
            throw APIError.custom(Translations.current().oauthDenied)
        }
        guard let code = value("code"), !code.isEmpty else { throw invalid }
        return code
    }
}

/// How a Beszel server lets people sign in.
struct BeszelAuthMethods: Decodable, Sendable {
    struct Provider: Decodable, Sendable, Identifiable, Hashable {
        let name: String
        let displayName: String?
        let state: String
        let authURL: String
        let codeVerifier: String?

        var id: String { name }
        var title: String { displayName.flatMap { $0.isEmpty ? nil : $0 } ?? name }

        /// The provider's sign-in page. PocketBase leaves the redirect address for us to add.
        func signInURL() throws -> URL {
            guard authURL.hasSuffix("redirect_uri="),
                  let redirect = OAuthCallback.redirectURL.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
                  let url = URL(string: authURL + redirect),
                  ["https", "http"].contains(url.scheme?.lowercased() ?? "")
            else { throw APIError.invalidURL }
            return url
        }
    }

    private struct OAuth2: Decodable { let enabled: Bool?; let providers: [Provider]? }
    private struct Password: Decodable { let enabled: Bool? }

    /// Where the list came from; the code has to be exchanged at the same address.
    var baseURL = ""
    let providers: [Provider]
    let allowsPassword: Bool

    private enum CodingKeys: String, CodingKey { case oauth2, password }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        let oauth = try values.decodeIfPresent(OAuth2.self, forKey: .oauth2)
        providers = oauth?.enabled == true ? oauth?.providers ?? [] : []
        allowsPassword = try values.decodeIfPresent(Password.self, forKey: .password)?.enabled ?? true
    }
}

/// A finished OAuth sign-in. The password is never involved.
struct BeszelOAuthSession: Decodable, Sendable {
    struct Record: Decodable, Sendable { let email: String? }
    let token: String
    let record: Record?
}
