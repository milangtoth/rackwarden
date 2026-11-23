import XCTest
@testable import Homelab

/// A Dockge server that only knows the events the app uses.
private final class FakeDockge: @unchecked Sendable {
    private let lock = NSLock()
    private var log: [String] = []
    var validToken = "jwt"
    var needsCode = false
    var signsInByItself = false
    var agents: [String: String] = [:]
    var sendsAgentList = true
    /// Drops the connection when this agent event arrives.
    var dropsOn: String?
    var failsWith: String?
    let url = NSMutableString()

    var calls: [String] { lock.withLock { log } }
    func record(_ call: String) { lock.withLock { log.append(call) } }

    func open(_ url: URL) -> any WebSocketChannel {
        lock.withLock { self.url.setString(url.absoluteString) }
        return Channel(self)
    }

    private final class Channel: WebSocketChannel, @unchecked Sendable {
        private let server: FakeDockge
        private let stream: AsyncThrowingStream<String, Error>
        private let out: AsyncThrowingStream<String, Error>.Continuation
        private var iterator: AsyncThrowingStream<String, Error>.Iterator

        init(_ server: FakeDockge) {
            self.server = server
            (stream, out) = AsyncThrowingStream.makeStream()
            iterator = stream.makeAsyncIterator()
            out.yield(#"0{"sid":"a","pingInterval":25000,"pingTimeout":20000,"upgrades":[]}"#)
            out.yield("2")
        }

        func receive() async throws -> Data {
            guard let next = try await iterator.next() else { throw URLError(.networkConnectionLost) }
            return Data(next.utf8)
        }

        func close() { out.finish(throwing: URLError(.cancelled)) }

        func send(_ text: String) async throws {
            if text == "3" { return server.record("pong") }
            if text == "40" {
                out.yield(#"40{"sid":"b"}"#)
                out.yield(#"42["info",{"version":"1.5.0"}]"#)
                if server.signsInByItself {
                    afterLogin()
                    out.yield(#"42["autoLogin"]"#)
                }
                return
            }
            let digits = text.dropFirst(2).prefix { $0.isNumber }
            let array = try JSONSerialization.jsonObject(with: Data(text.dropFirst(2 + digits.count).utf8)) as! [Any]
            func ack(_ json: String) { out.yield("43\(digits)[\(json)]") }
            switch array[0] as! String {
            case "login":
                let fields = array[1] as! [String: String]
                if fields["password"] != "pw" { return ack(#"{"ok":false,"msg":"authIncorrectCreds","msgi18n":true}"#) }
                if server.needsCode, fields["token"] == nil { return ack(#"{"tokenRequired":true}"#) }
                if server.needsCode, fields["token"] != "123456" { return ack(#"{"ok":false,"msg":"authInvalidToken"}"#) }
                ack(#"{"ok":true,"token":"jwt"}"#)
            case "loginByToken":
                guard array[1] as! String == server.validToken else { return ack(#"{"ok":false,"msg":"authInvalidToken"}"#) }
                afterLogin()
                ack(#"{"ok":true}"#)
            case "agent":
                let event = array[2] as! String
                server.record("\(array[1])|\(event)|\(array[3])")
                if event == server.dropsOn { return out.finish(throwing: URLError(.networkConnectionLost)) }
                if let message = server.failsWith { return ack(#"{"ok":false,"msg":"\#(message)","msgi18n":true}"#) }
                if event == "serviceStatusList" {
                    return ack(#"{"ok":true,"serviceStatusList":{"web":"running","db":{"state":"exited","ports":["5432:5432"]}}}"#)
                }
                ack(#"{"ok":true,"msg":"Done","msgi18n":true}"#)
            default:
                break
            }
        }

        private func afterLogin() {
            if server.sendsAgentList {
                let list = ([""] + server.agents.keys).map { #""\#($0)":{"url":"","username":"","endpoint":"\#($0)"}"# }
                out.yield(#"42["agentList",{"ok":true,"agentList":{\#(list.joined(separator: ","))}}]"#)
            }
            out.yield(#"42["agent","stackList",{"ok":true,"stackList":{"b":{"name":"b","status":3,"isManagedByDockge":true,"endpoint":""},"a":{"name":"a","status":4,"isManagedByDockge":false}},"endpoint":""}]"#)
            for (endpoint, status) in server.agents {
                out.yield(#"42["agentStatus",{"endpoint":"\#(endpoint)","status":"connecting"}]"#)
                out.yield(#"42["agentStatus",{"endpoint":"\#(endpoint)","status":"\#(status)"}]"#)
                if status == "online" {
                    out.yield(#"42["agent","stackList",{"ok":true,"stackList":{"remote":{"name":"remote","status":3,"isManagedByDockge":true}},"endpoint":"\#(endpoint)"}]"#)
                }
            }
        }
    }
}

final class DockgeTests: XCTestCase {
    private func client(_ server: FakeDockge, token: String = "jwt") async -> DockgeAPIClient {
        let client = DockgeAPIClient(instanceId: UUID(), makeChannel: { server.open($0) })
        await client.configure(url: "https://dockge.example.test/", token: token, allowSelfSigned: false)
        return client
    }

    private let stack = DockgeStack(endpoint: "", name: "b", status: .running, isManaged: true)

    func testPacketsAreRead() throws {
        XCTAssertEqual(SocketIOPacket.parse("2"), .ping)
        XCTAssertEqual(SocketIOPacket.parse(#"40{"sid":"x"}"#), .connected)
        XCTAssertEqual(SocketIOPacket.parse(#"44{"message":"no"}"#), .refused("no"))
        XCTAssertEqual(SocketIOPacket.parse("41"), .disconnected)
        XCTAssertEqual(SocketIOPacket.parse("1"), .disconnected)
        XCTAssertEqual(SocketIOPacket.parse(#"4312[{"ok":true}]"#), .ack(id: 12, args: [Data(#"{"ok":true}"#.utf8)]))
        XCTAssertEqual(SocketIOPacket.parse(#"427["x","y"]"#), .event(name: "x", args: [Data(#""y""#.utf8)]))
        for odd in ["", "4", "42", "42nonsense", "43[]", "42/other,[\"x\"]", "451-[\"x\"]", "é"] {
            XCTAssertEqual(SocketIOPacket.parse(odd), .other, odd)
        }
        XCTAssertEqual(try SocketIOPacket.event("a", [.string("q\"")], id: 3), #"423["a","q\""]"#)
    }

    func testStacksAreListed() async throws {
        let server = FakeDockge()
        let overview = try await client(server).getOverview()
        XCTAssertEqual(overview.stacks.map(\.name), ["a", "b"])
        XCTAssertEqual(overview.runningCount, 1)
        XCTAssertEqual(overview.stacks.map(\.isManaged), [false, true])
        XCTAssertEqual(server.url as String, "wss://dockge.example.test/socket.io/?EIO=4&transport=websocket")
        XCTAssertTrue(server.calls.contains("pong"))
    }

    func testAgentsAreIncluded() async throws {
        let server = FakeDockge()
        server.agents = ["one.example.test": "online", "two.example.test": "offline"]
        let overview = try await client(server).getOverview()
        XCTAssertEqual(overview.endpoints, ["", "one.example.test"])
        XCTAssertEqual(overview.offlineAgents, ["two.example.test"])
    }

    func testLoginWithAndWithoutACode() async throws {
        let server = FakeDockge()
        let client = await client(server, token: "")
        let token = try await client.login(username: "me", password: "pw")
        XCTAssertEqual(token, "jwt")

        do {
            _ = try await client.login(username: "me", password: "wrong")
            XCTFail("Expected a rejection")
        } catch APIError.unauthorized {}

        server.needsCode = true
        do {
            _ = try await client.login(username: "me", password: "pw")
            XCTFail("Expected a code to be needed")
        } catch APIError.twoFactorRequired {}
        do {
            _ = try await client.login(username: "me", password: "pw", code: "000000")
            XCTFail("Expected the code to be rejected")
        } catch APIError.twoFactorInvalid {}
        let second = try await client.login(username: "me", password: "pw", code: "123456")
        XCTAssertEqual(second, "jwt")
    }

    func testARejectedSessionAsksForSignIn() async throws {
        let server = FakeDockge()
        let client = await client(server, token: "old")
        let asked = expectation(description: "asked to sign in")
        await client.setSignInRequiredCallback { if $0 == "old" { asked.fulfill() } }
        do {
            _ = try await client.getOverview()
            XCTFail("Expected a sign-in")
        } catch APIError.signInRequired {}
        await fulfillment(of: [asked], timeout: 2)
    }

    func testLoginTurnedOff() async throws {
        let server = FakeDockge()
        server.signsInByItself = true
        let overview = try await client(server, token: "").getOverview()
        XCTAssertEqual(overview.stacks.count, 2)
    }

    func testNoLoginIsNotEnoughWhenDockgeWantsOne() async throws {
        do {
            _ = try await client(FakeDockge(), token: "").getOverview()
            XCTFail("Expected a login to be needed")
        } catch APIError.unauthorized {}
    }

    func testServicesInBothForms() async throws {
        let services = try await client(FakeDockge()).getServices(stack)
        XCTAssertEqual(services.map(\.name), ["db", "web"])
        XCTAssertEqual(services.map(\.isRunning), [false, true])
        XCTAssertEqual(services.first?.ports, ["5432:5432"])
    }

    func testActionsReachTheRightStack() async throws {
        let server = FakeDockge()
        server.agents = ["one.example.test": "online"]
        let client = await client(server)
        for action in DockgeAction.allCases { try await client.perform(action, on: stack) }
        let remote = DockgeStack(endpoint: "one.example.test", name: "remote", status: .running, isManaged: true)
        try await client.perform(.restart, on: remote)
        XCTAssertEqual(server.calls.filter { $0 != "pong" }, [
            "|startStack|b", "|stopStack|b", "|restartStack|b", "|updateStack|b", "one.example.test|restartStack|remote"
        ])
    }

    func testForeignStacksAndOfflineAgentsAreNotTouched() async throws {
        let server = FakeDockge()
        server.agents = ["two.example.test": "offline"]
        let client = await client(server)
        for target in [
            DockgeStack(endpoint: "", name: "a", status: .running, isManaged: false),
            DockgeStack(endpoint: "two.example.test", name: "x", status: .running, isManaged: true)
        ] {
            do {
                try await client.perform(.stop, on: target)
                XCTFail("Expected a refusal")
            } catch {}
        }
        XCTAssertTrue(server.calls.allSatisfy { $0 == "pong" })
    }

    func testALostConnectionIsNotReportedAsAFailedAction() async throws {
        let server = FakeDockge()
        server.dropsOn = "updateStack"
        do {
            try await client(server).perform(.update, on: stack)
            XCTFail("Expected an uncertain result")
        } catch {
            XCTAssertEqual(error.localizedDescription, Translations.current().dockgeUnconfirmed)
        }
        XCTAssertEqual(server.calls.filter { $0 != "pong" }.count, 1)
    }

    func testAFailedActionGivesDockgesReason() async throws {
        let server = FakeDockge()
        server.failsWith = "Failed to restart"
        do {
            try await client(server).perform(.restart, on: stack)
            XCTFail("Expected a failure")
        } catch {
            XCTAssertEqual(error.localizedDescription, "Failed to restart")
        }
    }
}
