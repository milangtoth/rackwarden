import XCTest
@testable import Homelab

/// A TrueNAS server that counts logins and limits them like the real one.
private final class FakeTrueNAS: @unchecked Sendable {
    private let lock = NSLock()
    private var loginCount = 0
    private var openCount = 0
    var loginLimit = 20
    var acceptsKey = true
    var endpoint = "/api/current"
    var refused: Set<String> = []
    var breaking: Set<String> = []

    var logins: Int { lock.withLock { loginCount } }
    var opened: Int { lock.withLock { openCount } }

    func open(_ url: URL) -> any WebSocketChannel {
        lock.withLock { openCount += 1 }
        return Channel(server: self, reachable: url.path == endpoint)
    }

    func reply(to text: String) throws -> Data {
        let call = try JSONSerialization.jsonObject(with: Data(text.utf8)) as! [String: Any]
        let method = call["method"] as! String
        if breaking.contains(method) { throw URLError(.networkConnectionLost) }
        var reply: [String: Any] = ["jsonrpc": "2.0", "id": call["id"]!]
        switch method {
        case "auth.login_with_api_key":
            let allowed = lock.withLock {
                loginCount += 1
                return loginCount <= loginLimit
            }
            if allowed { reply["result"] = acceptsKey } else { reply["error"] = ["message": "Rate Limit Exceeded"] }
        case _ where refused.contains(method):
            reply["error"] = ["message": "Not authorized"]
        case "system.info":
            reply["result"] = ["version": "25.10", "hostname": "nas"]
        case "pool.query":
            reply["result"] = [["id": 1, "name": "tank", "status": "ONLINE", "healthy": true, "size": 100, "allocated": 40]]
        case "app.query":
            reply["result"] = [["state": "RUNNING"]]
        default:
            reply["result"] = [Any]()
        }
        return try JSONSerialization.data(withJSONObject: reply)
    }

    private final class Channel: WebSocketChannel, @unchecked Sendable {
        let server: FakeTrueNAS
        let reachable: Bool
        private let lock = NSLock()
        private var pending: [String] = []

        init(server: FakeTrueNAS, reachable: Bool) {
            self.server = server
            self.reachable = reachable
        }

        func send(_ text: String) async throws {
            guard reachable else { throw URLError(.badServerResponse) }
            lock.withLock { pending.append(text) }
        }

        func receive() async throws -> Data {
            try server.reply(to: lock.withLock { pending.removeFirst() })
        }

        func close() {}
    }
}

final class TrueNASClientTests: XCTestCase {
    private func client(_ server: FakeTrueNAS) async -> TrueNASAPIClient {
        let client = TrueNASAPIClient(instanceId: UUID(), makeChannel: { server.open($0) })
        await client.configure(url: "https://nas.example.test", apiKey: "1-key")
        return client
    }

    func testTheDashboardLogsInOnce() async throws {
        let server = FakeTrueNAS()
        let client = await client(server)
        let snapshot = try await client.getDashboardSnapshot()
        XCTAssertEqual(snapshot.pools.map(\.name), ["tank"])
        XCTAssertEqual(snapshot.workloadSummary.appsRunning, 1)
        XCTAssertEqual(server.logins, 1)
    }

    func testOpeningTheDashboardAfterHomeStaysUnderTheLoginLimit() async throws {
        let server = FakeTrueNAS()
        // What a key without full access is refused.
        server.refused = ["iscsi.target.query", "vm.query", "app.query"]
        let client = await client(server)
        let online = await client.ping()
        XCTAssertTrue(online)
        for _ in 0..<5 { _ = try await client.getDashboardSnapshot() }
        XCTAssertEqual(server.logins, 6)
    }

    func testARefusedMethodLeavesTheOthers() async throws {
        let server = FakeTrueNAS()
        server.refused = ["disk.query"]
        let snapshot = try await client(server).getDashboardSnapshot()
        XCTAssertTrue(snapshot.disks.isEmpty)
        XCTAssertEqual(snapshot.pools.count, 1)
        XCTAssertEqual(server.opened, 1)
    }

    func testABrokenConnectionSkipsOnlyThatMethod() async throws {
        let server = FakeTrueNAS()
        server.breaking = ["disk.query"]
        let snapshot = try await client(server).getDashboardSnapshot()
        XCTAssertEqual(snapshot.pools.count, 1)
        XCTAssertEqual(snapshot.workloadSummary.appsTotal, 1)
        XCTAssertEqual(server.logins, 2)
    }

    func testALimitedLoginIsNotRetried() async {
        let server = FakeTrueNAS()
        server.loginLimit = 0
        do {
            _ = try await client(server).getDashboardSnapshot()
            XCTFail("Expected the limit to be reported")
        } catch {
            XCTAssertTrue("\(error)".contains("Rate Limit Exceeded"), "\(error)")
        }
        XCTAssertEqual(server.logins, 1)
    }

    func testARejectedKeyIsNotRetried() async {
        let server = FakeTrueNAS()
        server.acceptsKey = false
        do {
            _ = try await client(server).getDashboardSnapshot()
            XCTFail("Expected the key to be rejected")
        } catch {
            guard case APIError.unauthorized = error else { return XCTFail("\(error)") }
        }
        XCTAssertEqual(server.logins, 1)
    }

    func testTheWorkingEndpointIsRemembered() async throws {
        let server = FakeTrueNAS()
        server.endpoint = "/websocket"
        let client = await client(server)
        _ = try await client.getDashboardSnapshot()
        XCTAssertEqual(server.opened, 3)
        _ = try await client.getDashboardSnapshot()
        XCTAssertEqual(server.opened, 4)
    }

    func testAnExpiredProxySessionIsReported() async {
        let client = TrueNASAPIClient(instanceId: UUID(), makeChannel: { _ in SignedOutChannel() })
        do {
            try await client.authenticate(url: "https://nas.example.test", apiKey: "1-key")
            XCTFail("Expected a sign-in")
        } catch {
            guard case APIError.forwardAuthRequired = error else { return XCTFail("\(error)") }
        }
    }
}

private struct SignedOutChannel: WebSocketChannel {
    func send(_ text: String) async throws { throw APIError.forwardAuthRequired }
    func receive() async throws -> Data { throw APIError.forwardAuthRequired }
    func close() {}
}
