import Foundation

// MARK: - Models

struct DockgeStack: Identifiable, Hashable, Sendable {
    enum Status: Int, Sendable {
        case unknown, draft, created, running, exited
    }

    /// Empty for the server itself, otherwise the host of one of its agents.
    let endpoint: String
    let name: String
    let status: Status
    /// Stacks that were not made in Dockge can only be shown.
    let isManaged: Bool

    var id: String { "\(endpoint)|\(name)" }
}

struct DockgeService: Identifiable, Hashable, Sendable {
    let name: String
    let state: String
    let ports: [String]
    var id: String { name }
    var isRunning: Bool { ["running", "healthy"].contains(state.lowercased()) }
}

struct DockgeOverview: Hashable, Sendable {
    let stacks: [DockgeStack]
    let offlineAgents: [String]

    var runningCount: Int { stacks.count { $0.status == .running } }
    /// Every server that answered, its own first.
    var endpoints: [String] { Set(stacks.map(\.endpoint)).sorted() }
}

enum DockgeAction: String, CaseIterable, Sendable {
    case start = "startStack"
    case stop = "stopStack"
    case restart = "restartStack"
    case update = "updateStack"
}

// MARK: - API Client

/// Dockge only speaks Socket.IO. Its events are internal; these are from 1.5.0.
actor DockgeAPIClient {
    private let instanceId: UUID
    private let makeChannel: (@Sendable (URL) throws -> any WebSocketChannel)?
    private var engine: BaseNetworkEngine
    private var baseURL = ""
    private var fallbackURL = ""
    private var token = ""
    private var allowSelfSigned = true
    private var onSignInRequired: (@Sendable (String) -> Void)?

    init(instanceId: UUID, makeChannel: (@Sendable (URL) throws -> any WebSocketChannel)? = nil) {
        self.instanceId = instanceId
        self.makeChannel = makeChannel
        engine = BaseNetworkEngine(serviceType: .dockge, instanceId: instanceId)
    }

    func configure(url: String, token: String, fallbackUrl: String? = nil, allowSelfSigned: Bool) {
        baseURL = Self.cleanURL(url)
        fallbackURL = Self.cleanURL(fallbackUrl ?? "")
        self.token = token
        self.allowSelfSigned = allowSelfSigned
        engine = BaseNetworkEngine(serviceType: .dockge, instanceId: instanceId, allowSelfSigned: allowSelfSigned)
    }

    func setSignInRequiredCallback(_ callback: @escaping @Sendable (String) -> Void) {
        onSignInRequired = callback
    }

    func ping() async -> Bool {
        for base in [baseURL, fallbackURL] where !base.isEmpty {
            if await engine.pingURL("\(base)/") { return true }
        }
        return false
    }

    // MARK: - Login

    private struct Reply: Decodable {
        let ok: Bool?
        let msg: String?
        let token: String?
        let tokenRequired: Bool?
    }

    /// Returns a session token. The password itself is not needed again.
    func login(username: String, password: String, code: String? = nil) async throws -> String {
        let connection = try await connect()
        defer { Task { await connection.close() } }
        var fields = ["username": username, "password": password]
        if let code { fields["token"] = code }
        let reply = try await decode(Reply.self, connection.call("login", [.object(fields)]))
        if reply.tokenRequired == true {
            throw APIError.twoFactorRequired(TwoFactorChallenge(baseURL: baseURL, kind: .authenticator(token: "")))
        }
        guard reply.ok == true, let token = reply.token, !token.isEmpty else {
            switch reply.msg {
            case "authInvalidToken": throw APIError.twoFactorInvalid
            case "authIncorrectCreds", nil: throw APIError.unauthorized
            case let message?: throw APIError.custom(message)
            }
        }
        return token
    }

    // MARK: - Reading

    func getOverview() async throws -> DockgeOverview {
        let connection = try await signedIn()
        defer { Task { await connection.close() } }
        await connection.setDeadline(20)

        var lists: [String: [DockgeStack]] = [:]
        var agents: Set<String>?
        var offline: Set<String> = []
        var waitsForAgents = false
        do {
            while true {
                if lists[""] != nil, let agents,
                   agents.allSatisfy({ lists[$0] != nil || offline.contains($0) }) { break }
                if lists[""] != nil, agents != nil, !waitsForAgents {
                    // Agents that stay silent must not hold up the rest for long.
                    waitsForAgents = true
                    await connection.setDeadline(8)
                }
                let event = try await connection.nextEvent()
                switch event.name {
                case "agentList":
                    agents = Set((Self.first(AgentList.self, event)?.agentList ?? [:]).keys.filter { !$0.isEmpty })
                case "agentStatus":
                    guard let status = Self.first(AgentStatus.self, event) else { continue }
                    if status.status == "offline" { offline.insert(status.endpoint) } else { offline.remove(status.endpoint) }
                case "agent":
                    guard event.args.count == 2, Self.first(String.self, event) == "stackList",
                          let list = try? JSONDecoder().decode(StackList.self, from: event.args[1]) else { continue }
                    lists[list.endpoint ?? ""] = list.stacks
                default:
                    continue
                }
            }
        } catch let error as URLError where error.code == .timedOut && lists[""] != nil {
            // Show what did arrive.
        }
        let missing = (agents ?? []).filter { lists[$0] == nil }
        return DockgeOverview(
            stacks: lists.values.flatMap { $0 }.sorted {
                ($0.endpoint, $0.name) < ($1.endpoint, $1.name)
            },
            offlineAgents: missing.sorted()
        )
    }

    func getServices(_ stack: DockgeStack) async throws -> [DockgeService] {
        let connection = try await signedIn()
        defer { Task { await connection.close() } }
        await connection.setDeadline(20)
        try await waitForAgent(stack.endpoint, connection)
        let reply = try await decode(
            ServiceList.self, connection.call("agent", Self.arguments(stack, "serviceStatusList"))
        )
        guard reply.ok == true else { throw APIError.custom(reply.msg ?? Translations.current().errorUnknown) }
        return (reply.serviceStatusList ?? [:])
            .map { DockgeService(name: $0.key, state: $0.value.state, ports: $0.value.ports) }
            .sorted { $0.name < $1.name }
    }

    // MARK: - Actions

    /// Returns once Docker has finished, which can take minutes for an update.
    func perform(_ action: DockgeAction, on stack: DockgeStack) async throws {
        guard stack.isManaged else { throw APIError.custom(Translations.current().dockgeNotManaged) }
        let connection = try await signedIn()
        defer { Task { await connection.close() } }
        await connection.setDeadline(20)
        try await waitForAgent(stack.endpoint, connection)
        await connection.setDeadline(Self.actionTimeout)
        let reply: Reply
        do {
            reply = try await decode(Reply.self, connection.call("agent", Self.arguments(stack, action.rawValue)))
        } catch is URLError {
            // The server carries on without us, so this is not a failure of the action.
            throw APIError.custom(Translations.current().dockgeUnconfirmed)
        }
        guard reply.ok == true else { throw APIError.custom(reply.msg ?? Translations.current().errorUnknown) }
    }

    private static let actionTimeout: TimeInterval = 900

    private static func arguments(_ stack: DockgeStack, _ event: String) -> [SocketIOValue] {
        [.string(stack.endpoint), .string(event), .string(stack.name)]
    }

    /// The server connects to its agents only after we sign in, and never answers for an offline one.
    private func waitForAgent(_ endpoint: String, _ connection: SocketIOConnection) async throws {
        guard !endpoint.isEmpty else { return }
        let offline = APIError.custom(String(format: Translations.current().dockgeAgentOffline, endpoint))
        do {
            while true {
                let event = try await connection.nextEvent()
                guard event.name == "agentStatus", let status = Self.first(AgentStatus.self, event),
                      status.endpoint == endpoint else { continue }
                if status.status == "online" { return }
                if status.status == "offline" { throw offline }
            }
        } catch let error as URLError where error.code == .timedOut {
            throw offline
        }
    }

    // MARK: - Connecting

    private func signedIn() async throws -> SocketIOConnection {
        let connection = try await connect()
        do {
            if token.isEmpty {
                try await waitForAutoLogin(connection)
            } else {
                let reply = try await decode(Reply.self, connection.call("loginByToken", [.string(token)]))
                guard reply.ok == true else {
                    onSignInRequired?(token)
                    throw APIError.signInRequired
                }
            }
            return connection
        } catch {
            await connection.close()
            throw error
        }
    }

    /// With its own login turned off, Dockge signs everyone in by itself.
    private func waitForAutoLogin(_ connection: SocketIOConnection) async throws {
        await connection.setDeadline(6)
        var skipped: [SocketIOConnection.Event] = []
        do {
            while true {
                let event = try await connection.nextEvent()
                switch event.name {
                case "autoLogin":
                    // The stack list can arrive before this.
                    await connection.putBack(skipped)
                    return
                case "setup": throw APIError.custom(Translations.current().dockgeNeedsSetup)
                default: skipped.append(event)
                }
            }
        } catch let error as URLError where error.code == .timedOut {
            throw APIError.unauthorized
        }
    }

    private func connect() async throws -> SocketIOConnection {
        guard !baseURL.isEmpty else { throw APIError.notConfigured }
        do {
            return try await connect(to: baseURL)
        } catch let primary {
            try Task.checkCancellation()
            guard !fallbackURL.isEmpty else { throw primary }
            do {
                return try await connect(to: fallbackURL)
            } catch let fallback {
                throw APIError.bothURLsFailed(primaryError: primary, fallbackError: fallback)
            }
        }
    }

    private func connect(to base: String) async throws -> SocketIOConnection {
        let connection = SocketIOConnection(channel: try openChannel(base))
        await connection.setDeadline(12)
        do {
            try await connection.connect()
            return connection
        } catch {
            await connection.close()
            throw error
        }
    }

    private func openChannel(_ base: String) throws -> any WebSocketChannel {
        guard var components = URLComponents(string: base) else { throw APIError.invalidURL }
        components.scheme = components.scheme?.lowercased() == "http" ? "ws" : "wss"
        components.path += "/socket.io/"
        components.query = "EIO=4&transport=websocket"
        guard let url = components.url else { throw APIError.invalidURL }
        if let makeChannel { return try makeChannel(url) }
        // Pings arrive every 25 seconds, so a quiet connection is a dead one.
        let session = BaseNetworkEngine.authSession(
            instanceId: instanceId, allowSelfSigned: allowSelfSigned, timeout: 60, totalTimeout: Self.actionTimeout + 60
        )
        defer { session.session.finishTasksAndInvalidate() }
        let task = try session.webSocketTask(with: url)
        task.maximumMessageSize = 16 * 1024 * 1024
        task.resume()
        return URLSessionChannel(task: task)
    }

    // MARK: - Helpers

    private func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw APIError.decodingError(error)
        }
    }

    private static func first<T: Decodable>(_ type: T.Type, _ event: SocketIOConnection.Event) -> T? {
        event.args.first.flatMap { try? JSONDecoder().decode(type, from: $0) }
    }

    private static func cleanURL(_ url: String) -> String {
        url.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "/+$", with: "", options: .regularExpression)
    }

    // MARK: - Responses

    private struct AgentList: Decodable {
        struct Agent: Decodable {}
        let agentList: [String: Agent]?
    }

    private struct AgentStatus: Decodable {
        let endpoint: String
        let status: String
    }

    private struct StackList: Decodable {
        struct Stack: Decodable {
            let name: String
            let status: Int?
            let isManagedByDockge: Bool?
        }

        let endpoint: String?
        let stackList: [String: Stack]

        var stacks: [DockgeStack] {
            stackList.values.map {
                DockgeStack(
                    endpoint: endpoint ?? "",
                    name: $0.name,
                    status: DockgeStack.Status(rawValue: $0.status ?? 0) ?? .unknown,
                    isManaged: $0.isManagedByDockge ?? false
                )
            }
        }
    }

    private struct ServiceList: Decodable {
        /// A plain state, or a state with its ports, depending on the version.
        struct Service: Decodable {
            let state: String
            let ports: [String]

            private enum CodingKeys: String, CodingKey { case state, ports }

            init(from decoder: Decoder) throws {
                if let text = try? decoder.singleValueContainer().decode(String.self) {
                    state = text
                    ports = []
                } else {
                    let values = try decoder.container(keyedBy: CodingKeys.self)
                    state = (try? values.decode(String.self, forKey: .state)) ?? ""
                    ports = (try? values.decode([String].self, forKey: .ports)) ?? []
                }
            }
        }

        let ok: Bool?
        let msg: String?
        let serviceStatusList: [String: Service]?
    }
}
