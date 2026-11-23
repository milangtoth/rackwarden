import XCTest
@testable import Homelab

final class AccessProfileTests: XCTestCase {
    private let origin = AccessOrigin(string: "https://app.example.test")!

    private func profile(
        headers: [AccessHeader] = [AccessHeader(name: "X-Proxy", value: "secret")],
        cookies: [AccessCookie] = []
    ) -> AccessProfile {
        AccessProfile(isEnabled: true, origins: [OriginAccess(origin: origin, headers: headers, cookies: cookies)])
    }

    private func request(_ url: String) -> URLRequest { URLRequest(url: URL(string: url)!) }

    func testOriginNormalization() {
        XCTAssertEqual(AccessOrigin(string: "WSS://APP.example.test:443/x"), origin)
        XCTAssertNotEqual(AccessOrigin(string: "https://app.example.test:8443"), origin)
        XCTAssertNotEqual(AccessOrigin(string: "http://app.example.test"), origin)
        XCTAssertNil(AccessOrigin(string: "file:///tmp"))
    }

    func testCredentialsOnlyReachConfiguredOrigin() throws {
        let p = profile()
        let sent = try p.prepared(request("https://app.example.test/api"), allowSelfSigned: false)
        XCTAssertEqual(sent?.value(forHTTPHeaderField: "X-Proxy"), "secret")
        for other in ["https://evil.test", "https://app.example.test.evil.test", "https://app.example.test:8443", "https://sub.app.example.test"] {
            XCTAssertNil(try p.prepared(request(other), allowSelfSigned: false))
        }
    }

    func testDisabledProfileSendsNothing() throws {
        var p = profile()
        p.isEnabled = false
        XCTAssertNil(try p.prepared(request("https://app.example.test"), allowSelfSigned: false))
    }

    func testInsecureTransportNeedsConsent() throws {
        XCTAssertThrowsError(try profile().prepared(request("https://app.example.test"), allowSelfSigned: true))
        var p = profile()
        p.allowsInsecureTransport = true
        XCTAssertNotNil(try p.prepared(request("https://app.example.test"), allowSelfSigned: true))

        let plain = AccessProfile(isEnabled: true, origins: [OriginAccess(origin: AccessOrigin(string: "http://app.example.test")!)])
        XCTAssertThrowsError(try plain.prepared(request("http://app.example.test"), allowSelfSigned: false))
    }

    func testServiceHeadersAndCookiesWin() throws {
        let p = profile(
            headers: [AccessHeader(name: "Authorization", value: "Basic proxy")],
            cookies: [
                AccessCookie(name: "SID", value: "proxy", path: "/", expiresAt: nil),
                AccessCookie(name: "gate", value: "a=b", path: "/", expiresAt: nil)
            ]
        )
        var req = request("https://app.example.test/api")
        req.setValue("Bearer service", forHTTPHeaderField: "Authorization")
        req.setValue("SID=service", forHTTPHeaderField: "Cookie")
        let sent = try p.prepared(req, allowSelfSigned: false)
        XCTAssertEqual(sent?.value(forHTTPHeaderField: "Authorization"), "Bearer service")
        XCTAssertEqual(sent?.value(forHTTPHeaderField: "Cookie"), "SID=service; gate=a=b")
    }

    func testCookiePathAndExpiry() {
        let now = Date()
        XCTAssertTrue(AccessCookie(name: "a", value: "b", path: "/app", expiresAt: nil).applies(to: "/app/x", now: now))
        XCTAssertFalse(AccessCookie(name: "a", value: "b", path: "/app", expiresAt: nil).applies(to: "/apple", now: now))
        XCTAssertFalse(AccessCookie(name: "a", value: "b", path: "/", expiresAt: now).applies(to: "/", now: now))
    }

    func testHeaderValidation() {
        XCTAssertTrue(AccessHeader(name: "CF-Access-Client-Id", value: "x").isValid)
        XCTAssertFalse(AccessHeader(name: "Cookie", value: "x").isValid)
        XCTAssertFalse(AccessHeader(name: "Bad Name", value: "x").isValid)
        XCTAssertFalse(AccessHeader(name: "X", value: "a\r\nHost: evil").isValid)
    }

    func testRedirectGuardBlocksOtherOrigins() {
        let guardian = RedirectGuard(origin: origin)
        XCTAssertTrue(guardian.allows(URL(string: "https://app.example.test/login")))
        XCTAssertFalse(guardian.wasBlocked)
        XCTAssertFalse(guardian.allows(URL(string: "https://auth.example.test/login")))
        XCTAssertTrue(guardian.wasBlocked)
        XCTAssertFalse(guardian.allows(URL(string: "http://app.example.test/")))
    }

    func testDraftLifecycle() {
        let persisted = PersistedProfiles()
        let store = AccessProfileStore(load: { [:] }, save: { persisted.value = $0 })
        let draft = UUID()
        store.setDraft(profile(), for: draft)
        XCTAssertNotNil(store.profile(for: draft))
        XCTAssertTrue(persisted.value.isEmpty)

        store.discardDraft(draft)
        XCTAssertNil(store.profile(for: draft))

        store.setDraft(profile(), for: draft)
        let instance = ServiceInstance(type: .beszel, label: "x", url: "https://moved.example.test")
        store.commitDraft(draft, to: instance)
        XCTAssertEqual(persisted.value[instance.id]?.origins, [])
        XCTAssertNil(store.profile(for: draft))
    }

    func testBackupDropsCookiesAndKeepsHeaders() throws {
        let p = profile(cookies: [AccessCookie(name: "gate", value: "session", path: "/", expiresAt: nil)])
        let entry = ServiceInstance(type: .beszel, label: "x", url: "https://app.example.test")
            .toBackupEntry(isPreferred: true, accessProfile: p)
        let data = try JSONEncoder().encode(entry)
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("session"))
        let decoded = try JSONDecoder().decode(BackupServiceEntry.self, from: data)
        XCTAssertEqual(decoded.accessProfile?.origins.first?.headers, [AccessHeader(name: "X-Proxy", value: "secret")])
    }

    func testOldBackupEntryStillDecodes() throws {
        let json = #"{"type":"beszel","label":"x","url":"https://app.example.test","allowSelfSigned":false,"isPreferred":true}"#
        let entry = try JSONDecoder().decode(BackupServiceEntry.self, from: Data(json.utf8))
        XCTAssertNil(entry.accessProfile)
        XCTAssertNotNil(entry.toServiceInstance())
    }
}

private final class PersistedProfiles: @unchecked Sendable {
    var value: [UUID: AccessProfile] = [:]
}
