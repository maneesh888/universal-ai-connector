import Foundation
import UniversalAiConnectorBridge

public final class UniversalAiConnectorPOC: @unchecked Sendable {
    private let bridge: PocBridge

    public init() {
        bridge = PocBridge()
    }

    public var version: String {
        bridge.version()
    }

    public func greeting(name: String) -> String {
        bridge.greeting(name: name)
    }

    public func respond(to input: String) async throws -> String {
        let state = LockedOperationState<String>()

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                state.installContinuation(continuation)

                let handle = bridge.respond(
                    input: input,
                    onSuccess: { response in
                        state.succeed(response)
                    },
                    onError: { error in
                        state.fail(Self.map(error))
                    }
                )
                state.installHandle(handle)
            }
        } onCancel: {
            state.cancel()
        }
    }

    public func stream(
        input: String
    ) -> AsyncThrowingStream<ConnectorStreamEvent, Error> {
        let cancellation = LockedCancellationHandle()

        let callbackStream = AsyncThrowingStream<ConnectorStreamEvent, Error> { continuation in
            continuation.onTermination = { @Sendable _ in
                cancellation.cancel()
            }

            let handle = bridge.stream(
                input: input,
                onEvent: { event in
                    continuation.yield(
                        ConnectorStreamEvent(
                            sequence: Int(event.sequence),
                            text: event.text
                        )
                    )
                },
                onComplete: {
                    continuation.finish()
                },
                onError: { error in
                    continuation.finish(throwing: Self.map(error))
                }
            )
            cancellation.install(handle)
        }

        let iteratorBox = ThrowingStreamIteratorBox(stream: callbackStream)

        return AsyncThrowingStream(unfolding: {
            try await withTaskCancellationHandler {
                try Task.checkCancellation()
                let event = try await iteratorBox.next()
                try Task.checkCancellation()
                return event
            } onCancel: {
                cancellation.cancel()
            }
        })
    }

    public func resetDiagnostics() {
        bridge.resetInstrumentation()
    }

    public func diagnostics() -> ConnectorDiagnostics {
        let snapshot = bridge.instrumentationSnapshot()
        return ConnectorDiagnostics(
            unaryCancellations: Int(snapshot.unaryCancellations),
            streamCancellations: Int(snapshot.streamCancellations)
        )
    }

    private static func map(_ error: PocBridgeError) -> ConnectorError {
        switch error.code {
        case "invalid_input":
            return .invalidInput
        case "simulated_failure":
            return .simulatedFailure(
                code: error.code,
                message: error.message
            )
        default:
            return .bridgeFailure(message: error.message)
        }
    }
}
