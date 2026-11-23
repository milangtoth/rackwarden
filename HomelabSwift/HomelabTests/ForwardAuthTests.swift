import WebKit
import XCTest
@testable import Homelab

final class ForwardAuthTests: XCTestCase {
    private let origin = AccessOrigin(string: "https://app.example.test")!

    private func cookie(_ name: String, domain: String, secure: Bool = true) -> HTTPCookie {
        var properties: [HTTPCookiePropertyKey: Any] = [.name: name, .value: "v", .domain: domain, .path: "/"]
        if secure { properties[.secure] = "TRUE" }
        return HTTPCookie(properties: properties)!
    }

    func testOnlyCookiesForTheOriginAreKept() {
        let kept = ForwardAuth.cookies([
            cookie("host", domain: "app.example.test"),
            cookie("parent", domain: ".example.test"),
            cookie("idp", domain: "auth.example.test"),
            cookie("other", domain: ".evil.test"),
            cookie("lookalike", domain: ".ample.test")
        ], for: origin)
        XCTAssertEqual(Set(kept.map(\.name)), ["host", "parent"])

        let plain = AccessOrigin(string: "http://app.example.test")!
        XCTAssertTrue(ForwardAuth.cookies([cookie("secure", domain: "app.example.test")], for: plain).isEmpty)
    }

    func testForgettingOneServiceKeepsSharedCookies() {
        let hosts: Set<String> = ["app.example.test"]
        XCTAssertTrue(ForwardAuth.isSet(cookie("a", domain: "app.example.test"), byAnyOf: hosts))
        XCTAssertTrue(ForwardAuth.isSet(cookie("b", domain: ".app.example.test"), byAnyOf: hosts))
        for other in [".example.test", "auth.example.test", "other.example.test", "xapp.example.test"] {
            XCTAssertFalse(ForwardAuth.isSet(cookie("c", domain: other), byAnyOf: hosts), other)
        }
    }

    @MainActor
    func testSharedSignIn() async {
        let key = ForwardAuthWebData.sharingKey
        let before = UserDefaults.standard.object(forKey: key)
        UserDefaults.standard.removeObject(forKey: key)
        defer { UserDefaults.standard.set(before, forKey: key) }
        XCTAssertTrue(ForwardAuthWebData.isShared)

        let first = UUID(), second = UUID()
        let store = ForwardAuthWebData.store(for: first)
        XCTAssertTrue(store === ForwardAuthWebData.store(for: second))
        XCTAssertEqual(store.identifier, ForwardAuthWebData.sharedId)

        // Session cookies without an expiry, like a browser-only sign-in.
        for (name, domain) in [("one", "one.example.test"), ("two", "two.example.test"), ("idp", "auth.example.test")] {
            await store.httpCookieStore.setCookie(cookie(name, domain: domain))
        }
        func names() async -> Set<String> {
            Set(await ForwardAuthWebData.store(for: first).httpCookieStore.allCookies().map(\.name))
        }
        var found = await names()
        XCTAssertEqual(found, ["one", "two", "idp"])

        await ForwardAuthWebData.remove(first, urls: ["https://one.example.test", nil])
        found = await names()
        XCTAssertEqual(found, ["two", "idp"])

        await ForwardAuthWebData.removeOrphans(keeping: [])
        found = await names()
        XCTAssertEqual(found, ["two", "idp"])

        await ForwardAuthWebData.removeAll()
        found = await names()
        XCTAssertTrue(found.isEmpty)
    }

    @MainActor
    func testTurningSharingOffSeparatesServices() async {
        let key = ForwardAuthWebData.sharingKey
        let before = UserDefaults.standard.object(forKey: key)
        defer { UserDefaults.standard.set(before, forKey: key) }
        UserDefaults.standard.set(true, forKey: key)
        await ForwardAuthWebData.store(for: UUID()).httpCookieStore.setCookie(cookie("idp", domain: "auth.example.test"))

        UserDefaults.standard.set(false, forKey: key)
        await ForwardAuthWebData.removeOrphans(keeping: [])
        let first = UUID(), second = UUID()
        XCTAssertEqual(ForwardAuthWebData.store(for: first).identifier, first)
        XCTAssertEqual(ForwardAuthWebData.store(for: second).identifier, second)

        UserDefaults.standard.set(true, forKey: key)
        let left = await ForwardAuthWebData.store(for: first).httpCookieStore.allCookies()
        XCTAssertTrue(left.isEmpty, "The shared sign-in must not come back")
        await ForwardAuthWebData.removeAll()
    }

    func testProxyPagesAreNotServicePages() {
        XCTAssertTrue(ForwardAuth.isServicePage(URL(string: "https://app.example.test/login"), origin: origin))
        XCTAssertFalse(ForwardAuth.isServicePage(URL(string: "https://app.example.test/outpost.goauthentik.io/start"), origin: origin))
        XCTAssertFalse(ForwardAuth.isServicePage(URL(string: "https://auth.example.test/"), origin: origin))
        XCTAssertFalse(ForwardAuth.isServicePage(nil, origin: origin))
    }

    private func probe(_ server: MockServer, cookie: String) async throws -> ForwardAuth.Outcome {
        let origin = AccessOrigin(string: server.url)!
        return try await ForwardAuth.probe(
            url: URL(string: server.url)!,
            access: OriginAccess(
                origin: origin,
                cookies: [AccessCookie(name: "session", value: cookie, path: "/", expiresAt: nil)]
            ),
            allowsInsecureTransport: true,
            allowSelfSigned: false
        )
    }

    /// Same shape as Authentik: first a redirect on the service's address, then one to another host.
    private func authentikLike() async throws -> MockServer {
        try await MockServer { request in
            if request.route.contains("/outpost.goauthentik.io/start") {
                return (302, "http://localhost:9/login")
            }
            return request.cookie.contains("session=good") ? (200, "{}") : (302, "/outpost.goauthentik.io/start?rd=x")
        }
    }

    func testProbeTellsGoodAndBadSessionsApart() async throws {
        let server = try await authentikLike()
        defer { server.stop() }
        let good = try await probe(server, cookie: "good")
        let bad = try await probe(server, cookie: "bad")
        XCTAssertEqual(good, .passed)
        XCTAssertEqual(bad, .blocked)
    }

    func testProbeRecognisesUnprotectedService() async throws {
        let server = try await MockServer { _ in (200, "{}") }
        defer { server.stop() }
        let outcome = try await probe(server, cookie: "any")
        XCTAssertEqual(outcome, .notNeeded)
    }

    func testUnchangedRefusalIsNotASuccess() async throws {
        let server = try await MockServer { _ in (401, "{}") }
        defer { server.stop() }
        let outcome = try await probe(server, cookie: "any")
        XCTAssertEqual(outcome, .blocked)
    }

    func testSessionReachesLoginAndLaterRequests() async throws {
        let server = try await MockServer { request in
            guard request.cookie.contains("session=good") else { return (302, "http://localhost:9/login") }
            switch request.route {
            case "POST /api/tokens": return (200, #"{"token":"t1"}"#)
            case "GET /api/reports/hosts": return (200, #"{"proxy":1,"redirection":0,"stream":0,"dead":0}"#)
            default: return (404, "{}")
            }
        }
        defer { server.stop() }
        let id = UUID()
        let access = OriginAccess(
            origin: AccessOrigin(string: server.url)!,
            cookies: [AccessCookie(name: "session", value: "good", path: "/", expiresAt: nil)]
        )
        AccessProfileStore.shared.setDraft(
            AccessProfile(isEnabled: true, allowsInsecureTransport: true, origins: [access]), for: id
        )
        defer { AccessProfileStore.shared.discardDraft(id) }

        let client = NginxProxyManagerAPIClient(instanceId: id)
        let token = try await client.authenticate(url: server.url, email: "a@b.test", password: "pw")
        XCTAssertEqual(token, "t1")
        await client.configure(url: server.url, token: token, email: "a@b.test", password: "pw", allowSelfSigned: false)
        let report = try await client.getHostReport()
        XCTAssertEqual(report.proxy, 1)
    }

    func testHeadersAreProbedLikeASession() async throws {
        let server = try await MockServer { request in
            request.headers.contains { $0.lowercased() == "x-gate: good" } ? (404, "{}") : (403, "{}")
        }
        defer { server.stop() }
        func outcome(_ value: String) async throws -> ForwardAuth.Outcome {
            try await ForwardAuth.probe(
                url: URL(string: server.url)!,
                access: OriginAccess(
                    origin: AccessOrigin(string: server.url)!, headers: [AccessHeader(name: "X-Gate", value: value)]
                ),
                allowsInsecureTransport: true, allowSelfSigned: false
            )
        }
        let good = try await outcome("good")
        let bad = try await outcome("bad")
        XCTAssertEqual(good, .passed)
        XCTAssertEqual(bad, .blocked)
    }

    func testProtectedAddressIsRecognisedWithoutCredentials() async throws {
        let guarded = try await authentikLike()
        defer { guarded.stop() }
        let open = try await MockServer { _ in (401, "{}") }
        defer { open.stop() }
        let guardedIsProtected = await ForwardAuth.isProtected(URL(string: guarded.url)!, allowSelfSigned: false)
        let openIsProtected = await ForwardAuth.isProtected(URL(string: open.url)!, allowSelfSigned: false)
        XCTAssertTrue(guardedIsProtected)
        XCTAssertFalse(openIsProtected)
    }

    func testLoginPageDetection() {
        let requested = URL(string: "https://app.example.test/api")!
        func isLogin(_ url: String, _ type: String) -> Bool {
            ForwardAuth.isLoginPage(
                URLResponse(url: URL(string: url)!, mimeType: type, expectedContentLength: 0, textEncodingName: nil),
                requested: requested
            )
        }
        XCTAssertTrue(isLogin("https://auth.example.test/if/flow", "text/html"))
        XCTAssertTrue(isLogin("https://app.example.test/outpost.goauthentik.io/start", "text/html"))
        XCTAssertFalse(isLogin("https://app.example.test/login", "text/html"))
        XCTAssertFalse(isLogin("https://cdn.example.test/poster", "image/png"))
        XCTAssertFalse(isLogin("https://app.example.test/oauth2/token", "application/json"))
    }

    func testHttpsUpgradeIsNotASignIn() {
        let guardian = RedirectGuard(origin: AccessOrigin(string: "http://app.example.test")!)
        XCTAssertFalse(guardian.allows(URL(string: "https://app.example.test/")))
        XCTAssertTrue(guardian.needsHTTPS)
        XCTAssertFalse(guardian.wasBlocked)
        XCTAssertFalse(guardian.allows(URL(string: "https://other.example.test/")))
        XCTAssertTrue(guardian.wasBlocked)
    }

    func testAuthorizationHeaderIsBlockedWhereTheServiceNeedsIt() {
        let header = AccessHeader(name: "authorization", value: "Basic x")
        XCTAssertFalse(ForwardAuth.canSend(header, to: .beszel, usesNativeLogin: true))
        XCTAssertFalse(ForwardAuth.canSend(header, to: .nginxProxyManager, usesNativeLogin: true))
        XCTAssertTrue(ForwardAuth.canSend(header, to: .sonarr, usesNativeLogin: true))
        XCTAssertTrue(ForwardAuth.canSend(header, to: .adguardHome, usesNativeLogin: false))
        XCTAssertFalse(ForwardAuth.canSend(AccessHeader(name: "Cookie", value: "x"), to: .sonarr, usesNativeLogin: true))
    }

    func testStoredSessionCanBeSignedOutAndRenewed() {
        let store = AccessProfileStore(load: { [:] }, save: { _ in })
        let id = UUID()
        let cookie = AccessCookie(name: "s", value: "1", path: "/", expiresAt: nil)
        XCTAssertFalse(store.update(OriginAccess(origin: origin, cookies: [cookie]), for: id))
        store.set(AccessProfile(isEnabled: true, origins: [OriginAccess(origin: origin, cookies: [cookie])]), for: id)
        store.signOut(id)
        XCTAssertEqual(store.profile(for: id)?.origins.first?.cookies, [])
        XCTAssertEqual(store.profile(for: id)?.isEnabled, true)
        XCTAssertTrue(store.update(OriginAccess(origin: origin, cookies: [cookie]), for: id))
        XCTAssertEqual(store.profile(for: id)?.origins, [OriginAccess(origin: origin, cookies: [cookie])])
    }

    func testQbittorrentWorksWithoutItsOwnLogin() async throws {
        let server = try await MockServer { request in
            request.cookie.isEmpty && request.route == "GET /api/v2/app/version" ? (200, "v5") : (403, "")
        }
        defer { server.stop() }
        let client = QbittorrentAPIClient(instanceId: UUID())
        await client.configure(url: server.url, sid: "", allowSelfSigned: false)
        let works = await client.ping()
        XCTAssertTrue(works)
        XCTAssertEqual(server.count("POST /api/v2/auth/login"), 0)
    }
}

@MainActor
final class ForwardAuthFormTests: XCTestCase {
    private let primary = AccessOrigin(string: "https://app.example.test")!
    private let fallback = AccessOrigin(string: "http://10.0.0.2")!
    private let cookie = AccessCookie(name: "s", value: "1", path: "/", expiresAt: nil)

    func testEditedHeadersNeedANewCheck() {
        let form = ForwardAuthForm()
        form.method = .headers
        form.rows = [.init(name: "X-Gate", value: "a"), .init()]
        form.connect(OriginAccess(origin: primary, headers: form.headers))
        XCTAssertTrue(form.isConnected(primary))
        form.rows[0].value = "b"
        XCTAssertFalse(form.isConnected(primary))
        XCTAssertNil(form.profile(primary: primary, fallback: nil, allowSelfSigned: false))
    }

    func testFallbackIsOptionalAndSeparate() {
        let form = ForwardAuthForm()
        form.connect(OriginAccess(origin: primary, cookies: [cookie]))
        form.allowsInsecure = true
        var profile = form.profile(primary: primary, fallback: fallback, allowSelfSigned: false)
        XCTAssertEqual(profile?.origins.map(\.origin), [primary])
        XCTAssertEqual(profile?.allowsInsecureTransport, false)
        form.connect(OriginAccess(origin: fallback, cookies: [cookie]))
        profile = form.profile(primary: primary, fallback: fallback, allowSelfSigned: false)
        XCTAssertEqual(profile?.origins.map(\.origin), [primary, fallback])
        XCTAssertEqual(profile?.allowsInsecureTransport, true)
    }

    func testOnlyTheChosenMethodIsSaved() {
        let form = ForwardAuthForm()
        form.rows = [.init(name: "X-Gate", value: "a")]
        form.connect(OriginAccess(origin: primary, headers: form.headers, cookies: [cookie]))
        let profile = form.profile(primary: primary, fallback: nil, allowSelfSigned: false)
        XCTAssertEqual(profile?.origins.first?.headers, [])
        XCTAssertEqual(profile?.origins.first?.cookies, [cookie])
    }

    func testTurningOffForgetsEverything() {
        let form = ForwardAuthForm()
        form.load(AccessProfile(
            isEnabled: true, method: .headers, usesNativeLogin: false,
            origins: [OriginAccess(origin: primary, headers: [AccessHeader(name: "X-Gate", value: "a")])]
        ))
        XCTAssertTrue(form.isOn && form.skipsNativeLogin && form.isConnected(primary))
        form.turnOff()
        XCTAssertFalse(form.isOn || form.skipsNativeLogin || form.hasCredentials)
    }

    func testExpiredSessionAsksForSignInOnce() async throws {
        let server = try await MockServer { request in
            request.cookie.contains("s=good") ? (200, "{}") : (302, "http://localhost:9/login")
        }
        defer { server.stop() }
        let store = ServicesStore()
        let instance = ServiceInstance(
            id: UUID(), type: .nginxProxyManager, label: "x", url: server.url, token: "t", allowSelfSigned: false
        )
        let origin = AccessOrigin(string: server.url)!
        func access(_ value: String) -> OriginAccess {
            OriginAccess(origin: origin, cookies: [AccessCookie(name: "s", value: value, path: "/", expiresAt: nil)])
        }
        AccessProfileStore.shared.set(
            AccessProfile(isEnabled: true, allowsInsecureTransport: true, origins: [access("old")]), for: instance.id
        )
        await store.saveInstance(instance, triggerReachabilityCheck: false)
        defer { store.deleteInstance(id: instance.id) }

        await store.checkReachability(for: instance.id)
        for _ in 0..<50 where store.expiredProxyURL(instance.id) == nil {
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertEqual(store.expiredProxyURL(instance.id), URL(string: server.url))
        XCTAssertTrue(store.needsSignIn(instance.id))

        await store.renewProxySession(instance.id, access: access("good"))
        XCTAssertFalse(store.needsSignIn(instance.id))
        XCTAssertEqual(store.reachability(for: instance.id), true)
    }
}
