import Foundation

enum SocketIOValue: Encodable, Sendable {
    case string(String)
    case object([String: String])

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }
}

/// The text packets of Socket.IO 4 (Engine.IO 4) that a plain client needs.
enum SocketIOPacket: Equatable, Sendable {
    case open
    case ping
    case connected
    case refused(String)
    case disconnected
    case event(name: String, args: [Data])
    case ack(id: Int, args: [Data])
    case other

    static func parse(_ text: String) -> SocketIOPacket {
        guard let kind = text.first else { return .other }
        switch kind {
        case "0": return .open
        case "1": return .disconnected
        case "2": return .ping
        case "4": break
        default: return .other
        }
        let message = text.dropFirst()
        guard let type = message.first else { return .other }
        var body = message.dropFirst()
        // Only the default namespace is used; others come as "/name,".
        if body.hasPrefix("/") { return .other }
        switch type {
        case "0": return .connected
        case "1": return .disconnected
        case "4":
            struct Reason: Decodable { let message: String? }
            return .refused((try? JSONDecoder().decode(Reason.self, from: Data(body.utf8)))?.message ?? "")
        case "2", "3":
            let digits = body.prefix { $0.isASCII && $0.isNumber }
            body = body.dropFirst(digits.count)
            guard let array = try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? [Any] else { return .other }
            let args = array.compactMap { try? JSONSerialization.data(withJSONObject: $0, options: .fragmentsAllowed) }
            if type == "3" {
                guard let id = Int(digits) else { return .other }
                return .ack(id: id, args: args)
            }
            guard let name = array.first as? String, args.count == array.count else { return .other }
            return .event(name: name, args: Array(args.dropFirst()))
        default:
            return .other
        }
    }

    static func event(_ name: String, _ args: [SocketIOValue], id: Int) throws -> String {
        let data = try JSONEncoder().encode([.string(name)] + args)
        return "42\(id)" + String(decoding: data, as: UTF8.self)
    }
}

/// One Socket.IO connection, read by one caller at a time.
actor SocketIOConnection {
    struct Event: Sendable {
        let name: String
        let args: [Data]
    }

    private let channel: any WebSocketChannel
    private var nextId = 0
    private var pending: [Event] = []
    private var deadline: Task<Void, Never>?
    private var timedOut = false

    init(channel: any WebSocketChannel) {
        self.channel = channel
    }

    /// Ends the connection when the time is up; a waiting read then fails with `timedOut`.
    func setDeadline(_ seconds: TimeInterval) {
        deadline?.cancel()
        deadline = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            await self?.expire()
        }
    }

    private func expire() {
        timedOut = true
        channel.close()
    }

    func close() {
        deadline?.cancel()
        channel.close()
    }

    func connect() async throws {
        while true {
            switch try await read() {
            case .open: try await send("40")
            case .connected: return
            case .refused(let reason): throw APIError.custom(reason)
            default: continue
            }
        }
    }

    /// Sends an event and waits for its answer. Events arriving meanwhile are kept.
    func call(_ name: String, _ args: [SocketIOValue]) async throws -> Data {
        let id = nextId
        nextId += 1
        try await send(try SocketIOPacket.event(name, args, id: id))
        while true {
            switch try await read() {
            case .ack(let answered, let args) where answered == id:
                return args.first ?? Data("null".utf8)
            case .event(let name, let args):
                pending.append(Event(name: name, args: args))
            default:
                continue
            }
        }
    }

    func nextEvent() async throws -> Event {
        if !pending.isEmpty { return pending.removeFirst() }
        while true {
            if case .event(let name, let args) = try await read() { return Event(name: name, args: args) }
        }
    }

    /// Returns events to the front, for whoever reads next.
    func putBack(_ events: [Event]) {
        pending.insert(contentsOf: events, at: 0)
    }

    private func send(_ text: String) async throws {
        do {
            try await channel.send(text)
        } catch {
            throw timedOut ? URLError(.timedOut) : error
        }
    }

    private func read() async throws -> SocketIOPacket {
        do {
            while true {
                let packet = SocketIOPacket.parse(String(decoding: try await channel.receive(), as: UTF8.self))
                switch packet {
                case .ping: try await channel.send("3")
                case .disconnected: throw URLError(.networkConnectionLost)
                case .other: continue
                default: return packet
                }
            }
        } catch {
            throw timedOut ? URLError(.timedOut) : error
        }
    }
}
