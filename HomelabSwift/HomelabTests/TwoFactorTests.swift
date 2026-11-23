import Network
import XCTest
@testable import Homelab

final class TwoFactorTests: XCTestCase {
    private func npm(_ json: String, cookie: String? = nil) throws -> String {
        try NpmLogin.token(from: Data(json.utf8), setCookie: cookie, baseURL: "https://npm.test")
    }

    func testNpmPlainLogin() throws {
        XCTAssertEqual(try npm(#"{"token":"abc","expires":"x"}"#), "abc")
        XCTAssertEqual(try npm(#"{"result":{"token":"inner"}}"#), "inner")
        XCTAssertEqual(try npm("{}", cookie: "token=plus; Path=/; HttpOnly"), "plus")
        XCTAssertThrowsError(try npm("{}"))
    }

    func testNpmChallenge() {
        XCTAssertThrowsError(try npm(#"{"requires_2fa":true,"challenge_token":"c1"}"#)) { error in
            guard case APIError.twoFactorRequired(let challenge) = error else { return XCTFail("\(error)") }
            XCTAssertEqual(challenge, TwoFactorChallenge(baseURL: "https://npm.test", kind: .authenticator(token: "c1")))
            XCTAssertTrue(error.needsUserSignIn)
        }
        XCTAssertThrowsError(try npm(#"{"requires_2fa":true}"#)) { XCTAssertFalse($0.needsUserSignIn) }
    }

    func testBeszelMfaOnlyOn401() {
        let body = Data(#"{"mfaId":"m1"}"#.utf8)
        XCTAssertEqual(BeszelLogin.mfaId(statusCode: 401, data: body), "m1")
        XCTAssertNil(BeszelLogin.mfaId(statusCode: 200, data: body))
        XCTAssertNil(BeszelLogin.mfaId(statusCode: 401, data: Data(#"{"message":"no"}"#.utf8)))
        XCTAssertNil(BeszelLogin.mfaId(statusCode: 401, data: Data("<html>".utf8)))
    }

    func testOnlyTwoFactorErrorsNeedTheUser() {
        XCTAssertTrue(APIError.signInRequired.needsUserSignIn)
        XCTAssertTrue(APIError.bothURLsFailed(primaryError: URLError(.timedOut), fallbackError: APIError.signInRequired).needsUserSignIn)
        XCTAssertFalse(APIError.unauthorized.needsUserSignIn)
        XCTAssertFalse(URLError(.timedOut).needsUserSignIn)
    }

    func testTokenRenewalWindow() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        func jwt(_ claims: String) -> String {
            let payload = Data(claims.utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
            return "h.\(payload).s"
        }
        XCTAssertFalse(TokenLifetime.shouldRenew(jwt(#"{"iat":999990,"exp":1000100}"#), assumedLifetime: 1, now: now))
        XCTAssertTrue(TokenLifetime.shouldRenew(jwt(#"{"iat":999900,"exp":1000010}"#), assumedLifetime: 1, now: now))
        XCTAssertFalse(TokenLifetime.shouldRenew(jwt(#"{"iat":999900,"exp":999999}"#), assumedLifetime: 1, now: now))
        XCTAssertTrue(TokenLifetime.shouldRenew(jwt(#"{"exp":1000010}"#), assumedLifetime: 100, now: now))
        XCTAssertFalse(TokenLifetime.shouldRenew("opaque", assumedLifetime: 100, now: now))
    }
}

final class TwoFactorClientTests: XCTestCase {
    func testNpmLoginAsksForCodeAndCompletes() async throws {
        let server = try await MockServer { request in
            switch request.route {
            case "POST /api/tokens": return (200, #"{"requires_2fa":true,"challenge_token":"c1"}"#)
            case "POST /api/tokens/2fa":
                return request.body.contains("c1") && request.body.contains("123456")
                    ? (200, #"{"token":"full"}"#) : (401, "{}")
            default: return (404, "{}")
            }
        }
        defer { server.stop() }
        let client = NginxProxyManagerAPIClient(instanceId: UUID())
        do {
            _ = try await client.authenticate(url: server.url, email: "a@b.test", password: "pw")
            XCTFail("expected a challenge")
        } catch APIError.twoFactorRequired(let challenge) {
            do {
                _ = try await client.completeTwoFactor(challenge, code: "000000")
                XCTFail("wrong code accepted")
            } catch APIError.twoFactorInvalid {}
            let token = try await client.completeTwoFactor(challenge, code: "123456")
            XCTAssertEqual(token, "full")
        }
    }

    func testNpmSilentLoginStopsAfterOneChallenge() async throws {
        let server = try await MockServer { request in
            request.route == "POST /api/tokens"
                ? (200, #"{"requires_2fa":true,"challenge_token":"c1"}"#) : (401, "{}")
        }
        defer { server.stop() }
        let signInAsked = expectation(description: "sign in required")
        let client = NginxProxyManagerAPIClient(instanceId: UUID())
        await client.configure(url: server.url, token: "old", email: "a@b.test", password: "pw")
        await client.setSignInRequiredCallback { if $0 == "old" { signInAsked.fulfill() } }
        for _ in 0..<3 {
            do {
                _ = try await client.getHostReport()
                XCTFail("expected an error")
            } catch {
                XCTAssertTrue(error.needsUserSignIn)
            }
        }
        await fulfillment(of: [signInAsked], timeout: 2)
        XCTAssertEqual(server.count("POST /api/tokens"), 1)

        await client.configure(url: server.url, token: "new", email: "a@b.test", password: "pw")
        _ = try? await client.getHostReport()
        XCTAssertEqual(server.count("POST /api/tokens"), 2)
    }

    func testBeszelEmailsCodeOnlyWhenAsked() async throws {
        let server = try await MockServer { request in
            switch request.route {
            case "POST /api/collections/users/auth-with-password": return (401, #"{"mfaId":"m1"}"#)
            case "POST /api/collections/users/request-otp": return (200, #"{"otpId":"o1"}"#)
            case "POST /api/collections/users/auth-with-otp":
                let ok = ["m1", "o1", "654321"].allSatisfy(request.body.contains)
                return ok ? (200, #"{"token":"full"}"#) : (400, "{}")
            default: return (401, "{}")
            }
        }
        defer { server.stop() }
        let client = BeszelAPIClient(instanceId: UUID())
        await client.configure(url: server.url, token: "old", email: "a@b.test", password: "pw")
        _ = try? await client.getSystem(id: "x")
        _ = try? await client.getSystem(id: "x")
        XCTAssertEqual(server.count("POST /api/collections/users/auth-with-password"), 1)
        XCTAssertEqual(server.count("POST /api/collections/users/request-otp"), 0)

        do {
            _ = try await client.authenticate(url: server.url, email: "a@b.test", password: "pw")
            XCTFail("expected a challenge")
        } catch APIError.twoFactorRequired(let challenge) {
            let sent = try await client.requestTwoFactorCode(challenge)
            XCTAssertEqual(server.count("POST /api/collections/users/request-otp"), 1)
            do {
                _ = try await client.completeTwoFactor(sent, code: "000000")
                XCTFail("wrong code accepted")
            } catch APIError.twoFactorInvalid {}
            let token = try await client.completeTwoFactor(sent, code: "654321")
            XCTAssertEqual(token, "full")
        }
    }

    func testBeszelWithoutSystemsIsNotSignedOut() async throws {
        let server = try await MockServer { request in
            request.route.hasPrefix("GET ")
                ? (200, #"{"page":1,"perPage":50,"totalItems":0,"totalPages":0,"items":[]}"#)
                : (401, #"{"mfaId":"m1"}"#)
        }
        defer { server.stop() }
        let client = BeszelAPIClient(instanceId: UUID())
        await client.configure(url: server.url, token: "valid", email: "a@b.test", password: "pw")
        let systems = try await client.getSystems()
        XCTAssertTrue(systems.items.isEmpty)
    }
}

/// Minimal loopback HTTP server; one request per connection.
private final class MockServer: @unchecked Sendable {
    struct Request: Sendable { let route: String; let body: String }

    private let listener: NWListener
    private let queue = DispatchQueue(label: "mock-server")
    private let lock = NSLock()
    private var routes: [String] = []
    private let handler: @Sendable (Request) -> (Int, String)

    init(handler: @escaping @Sendable (Request) -> (Int, String)) async throws {
        self.handler = handler
        let parameters = NWParameters.tcp
        parameters.requiredInterfaceType = .loopback
        listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            connection.start(queue: self.queue)
            self.read(connection, Data())
        }
        let ready = XCTestExpectation(description: "listening")
        listener.stateUpdateHandler = { if case .ready = $0 { ready.fulfill() } }
        listener.start(queue: queue)
        _ = await XCTWaiter.fulfillment(of: [ready], timeout: 5)
    }

    var url: String { "http://127.0.0.1:\(listener.port?.rawValue ?? 0)" }
    func count(_ route: String) -> Int { lock.withLock { routes.filter { $0 == route }.count } }
    func stop() { listener.cancel() }

    private func read(_ connection: NWConnection, _ buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, done, _ in
            guard let self else { return }
            let all = buffer + (data ?? Data())
            guard let request = Self.parse(all) else {
                if done { connection.cancel() } else { self.read(connection, all) }
                return
            }
            self.lock.withLock { self.routes.append(request.route) }
            let (status, body) = self.handler(request)
            let head = "HTTP/1.1 \(status) X\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n"
            connection.send(content: Data((head + body).utf8), completion: .contentProcessed { _ in connection.cancel() })
        }
    }

    private static func parse(_ data: Data) -> Request? {
        let text = String(decoding: data, as: UTF8.self)
        guard let split = text.range(of: "\r\n\r\n") else { return nil }
        let lines = text[..<split.lowerBound].components(separatedBy: "\r\n")
        let body = String(text[split.upperBound...])
        let length = lines.first { $0.lowercased().hasPrefix("content-length:") }
            .flatMap { Int($0.split(separator: ":")[1].trimmingCharacters(in: .whitespaces)) } ?? 0
        guard body.utf8.count >= length else { return nil }
        let start = lines[0].split(separator: " ")
        return Request(route: "\(start[0]) \(start[1].split(separator: "?")[0])", body: body)
    }
}
