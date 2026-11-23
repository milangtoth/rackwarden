import Foundation

/// One WebSocket, so tests can stand in for the server.
protocol WebSocketChannel: Sendable {
    func send(_ text: String) async throws
    func receive() async throws -> Data
    func close()
}

struct URLSessionChannel: WebSocketChannel {
    let task: URLSessionWebSocketTask

    func send(_ text: String) async throws {
        try await reportingSignIn { try await task.send(.string(text)) }
    }

    func receive() async throws -> Data {
        try await reportingSignIn {
            switch try await task.receive() {
            case .string(let text): return Data(text.utf8)
            case .data(let data): return data
            @unknown default: throw APIError.decodingError(URLError(.cannotDecodeContentData))
            }
        }
    }

    func close() {
        task.cancel(with: .normalClosure, reason: nil)
    }

    private func reportingSignIn<T: Sendable>(_ work: @Sendable () async throws -> T) async throws -> T {
        do {
            return try await withTaskCancellationHandler {
                try await work()
            } onCancel: {
                task.cancel()
            }
        } catch {
            try Task.checkCancellation()
            if (task.delegate as? RedirectGuard)?.wasBlocked == true { throw APIError.forwardAuthRequired }
            throw error
        }
    }
}
