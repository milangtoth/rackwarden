import XCTest
@testable import Homelab

final class OAuthTests: XCTestCase {
    private func code(_ url: String, state: String = "s1") throws -> String {
        try OAuthCallback.code(from: URL(string: url)!, state: state)
    }

    func testTheCodeIsTakenFromAMatchingReply() throws {
        XCTAssertEqual(try code("rackwarden://oauth?code=abc&state=s1"), "abc")
        XCTAssertEqual(try code("rackwarden://oauth/?state=s1&code=a%2Fb"), "a/b")
    }

    func testOtherRepliesAreRejected() {
        for url in [
            "rackwarden://oauth?code=abc&state=other",
            "rackwarden://oauth?code=abc",
            "rackwarden://oauth?state=s1",
            "rackwarden://oauth?code=&state=s1",
            "rackwarden://oauth?code=a&code=b&state=s1",
            "rackwarden://oauth?code=a&state=s1&state=s1",
            "rackwarden://elsewhere?code=abc&state=s1",
            "https://oauth?code=abc&state=s1",
            "rackwarden://oauth?error=access_denied&state=s1"
        ] {
            XCTAssertThrowsError(try code(url), url)
        }
        XCTAssertThrowsError(try code("rackwarden://oauth?code=abc&state=", state: ""))
    }

    private func methods(_ json: String) throws -> BeszelAuthMethods {
        try JSONDecoder().decode(BeszelAuthMethods.self, from: Data(json.utf8))
    }

    private let provider = #"{"name":"oidc","displayName":"Example","state":"s1","authURL":"https://auth.example.test/authorize?client_id=c&state=s1&redirect_uri=","codeVerifier":"v1","codeChallenge":"x"}"#

    func testSignInMethodsAreRead() throws {
        let found = try methods(#"{"password":{"enabled":false},"oauth2":{"enabled":true,"providers":[\#(provider)]}}"#)
        XCTAssertFalse(found.allowsPassword)
        XCTAssertEqual(found.providers.map(\.title), ["Example"])
        XCTAssertEqual(
            try found.providers[0].signInURL().absoluteString,
            "https://auth.example.test/authorize?client_id=c&state=s1&redirect_uri=rackwarden%3A%2F%2Foauth"
        )

        XCTAssertTrue(try methods(#"{"password":{"enabled":true},"oauth2":{"enabled":false,"providers":[]}}"#).providers.isEmpty)
        XCTAssertTrue(try methods("{}").allowsPassword)
    }

    func testOnlyWebPagesAreOpened() throws {
        let odd = try methods(#"{"oauth2":{"enabled":true,"providers":[{"name":"x","state":"s","authURL":"tel:1?redirect_uri="}]}}"#)
        XCTAssertThrowsError(try odd.providers[0].signInURL())
    }

    private func oidc() throws -> BeszelAuthMethods.Provider {
        try methods(#"{"oauth2":{"enabled":true,"providers":[\#(provider)]}}"#).providers[0]
    }

    func testTheCodeIsExchangedWithItsVerifier() async throws {
        let server = try await MockServer { request in
            let body = (try? JSONSerialization.jsonObject(with: Data(request.body.utf8))) as? [String: String]
            let expected = ["provider": "oidc", "code": "abc", "codeVerifier": "v1", "redirectURL": "rackwarden://oauth"]
            return body == expected
                ? (200, #"{"token":"t1","record":{"id":"1","email":"me@example.test"}}"#)
                : (400, #"{"message":"unexpected body"}"#)
        }
        defer { server.stop() }
        let session = try await BeszelAPIClient(instanceId: UUID())
            .completeOAuth(oidc(), code: "abc", baseURL: server.url, email: nil)
        XCTAssertEqual(session.token, "t1")
        XCTAssertEqual(session.record?.email, "me@example.test")
    }

    func testARejectedExchangeGivesTheServersReason() async throws {
        let server = try await MockServer { _ in (400, #"{"message":"Failed to fetch OAuth2 token.","status":400}"#) }
        defer { server.stop() }
        do {
            _ = try await BeszelAPIClient(instanceId: UUID())
                .completeOAuth(oidc(), code: "abc", baseURL: server.url, email: nil)
            XCTFail("Expected a rejection")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("Failed to fetch OAuth2 token."))
        }
    }

    func testAnEmailedCodeCanFollow() async throws {
        let server = try await MockServer { _ in (401, #"{"mfaId":"m1"}"#) }
        defer { server.stop() }
        let client = BeszelAPIClient(instanceId: UUID())
        do {
            _ = try await client.completeOAuth(oidc(), code: "abc", baseURL: server.url, email: "me@example.test")
            XCTFail("Expected a code to be needed")
        } catch APIError.twoFactorRequired(let challenge) {
            XCTAssertEqual(challenge.kind, .email(address: "me@example.test", mfaId: "m1", otpId: nil))
        }
        do {
            _ = try await client.completeOAuth(oidc(), code: "abc", baseURL: server.url, email: nil)
            XCTFail("Expected the email to be asked for")
        } catch APIError.custom {}
    }

    private func signedIn(_ server: MockServer) async -> BeszelAPIClient {
        let client = BeszelAPIClient(instanceId: UUID())
        // A password from before must not survive the switch to OAuth.
        await client.configure(url: server.url, token: "old", email: "me@example.test", password: "secret")
        await client.configure(url: server.url, token: "t1", email: "me@example.test")
        return client
    }

    func testARevokedSessionAsksForSignInOnce() async throws {
        let server = try await MockServer { _ in (401, #"{"status":401,"message":"The request requires valid record authorization token."}"#) }
        defer { server.stop() }
        let client = await signedIn(server)
        for _ in 0..<3 {
            let valid = await client.checkSession()
            XCTAssertFalse(valid)
        }
        let flagged = await client.isSignInRequired
        XCTAssertTrue(flagged)
        XCTAssertEqual(server.count("POST /api/collections/users/auth-refresh"), 1)
        XCTAssertEqual(server.count("POST /api/collections/users/auth-with-password"), 0)
    }

    func testAProxysRefusalIsNotARevokedSession() async throws {
        let server = try await MockServer { _ in (401, "denied") }
        defer { server.stop() }
        let client = await signedIn(server)
        _ = await client.checkSession()
        let flagged = await client.isSignInRequired
        XCTAssertFalse(flagged)
    }

    func testAnEmptyServerKeepsItsSession() async throws {
        let server = try await MockServer { request in
            request.route.contains("auth-refresh")
                ? (200, #"{"token":"t2"}"#)
                : (200, #"{"items":[],"page":1,"perPage":50,"totalItems":0,"totalPages":0}"#)
        }
        defer { server.stop() }
        let client = await signedIn(server)
        for _ in 0..<3 { _ = try? await client.getSystems() }
        let flagged = await client.isSignInRequired
        XCTAssertFalse(flagged)
        XCTAssertEqual(server.count("POST /api/collections/users/auth-refresh"), 1)
    }

    func testTheExchangeCarriesTheProxySession() async throws {
        let server = try await MockServer { request in
            request.cookie.contains("session=good") ? (200, #"{"token":"t1"}"#) : (403, "{}")
        }
        defer { server.stop() }
        let id = UUID()
        var profile = AccessProfile()
        profile.isEnabled = true
        profile.allowsInsecureTransport = true
        profile.origins = [OriginAccess(
            origin: AccessOrigin(string: server.url)!,
            cookies: [AccessCookie(name: "session", value: "good", path: "/", expiresAt: nil)]
        )]
        AccessProfileStore.shared.setDraft(profile, for: id)
        defer { AccessProfileStore.shared.discardDraft(id) }
        let session = try await BeszelAPIClient(instanceId: id)
            .completeOAuth(oidc(), code: "abc", baseURL: server.url, email: nil)
        XCTAssertEqual(session.token, "t1")
    }
}
