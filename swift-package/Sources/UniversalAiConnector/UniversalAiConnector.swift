import UniversalAiConnectorBridge

/// The supported Apple client for Universal AI Connector.
///
/// A connector is reusable and supports concurrent responses and streams. Each
/// operation is owned by the calling Swift task: task or stream cancellation is
/// propagated to Kotlin. The connector owns no caller-managed resource, so no
/// explicit cleanup method is required.
public final class UniversalAiConnector: @unchecked Sendable {
    private let bridge: AppleConnectorBridge
    private let testingHooks: UniversalAiConnectorTestingHooks

    /// Creates a deterministic, secretless connector.
    public convenience init() {
        self.init(
            bridge: AppleConnectorBridge(),
            testingHooks: UniversalAiConnectorTestingHooks()
        )
    }

    init(
        bridge: AppleConnectorBridge = AppleConnectorBridge(),
        testingHooks: UniversalAiConnectorTestingHooks
    ) {
        self.bridge = bridge
        self.testingHooks = testingHooks
    }

    /// The package version.
    public var version: String {
        bridge.version()
    }

    /// Returns one deterministic response.
    ///
    /// Cancelling the calling task cancels the Kotlin coroutine and throws
    /// `CancellationError`. Bridge failures are mapped to
    /// ``UniversalAiConnectorError``.
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
                let handleBox = AppleCancellationHandleBox(handle)
                testingHooks.beforeResponseCancellationInstallation()
                state.installCancellation {
                    handleBox.cancel()
                }
            }
        } onCancel: {
            state.cancel()
        }
    }

    /// Returns an ordered deterministic event stream.
    ///
    /// Ending iteration or cancelling the consuming task cancels the Kotlin
    /// coroutine. Exactly one completion, error, or cancellation is delivered.
    public func stream(
        input: String
    ) -> AsyncThrowingStream<UniversalAiStreamEvent, Error> {
        let state = LockedStreamState<UniversalAiStreamEvent>()

        let callbackStream = AsyncThrowingStream<UniversalAiStreamEvent, Error> {
            continuation in
            continuation.onTermination = { @Sendable _ in
                state.cancel()
            }
            state.installContinuation(continuation)

            let handle = bridge.stream(
                input: input,
                onEvent: { event in
                    state.yield(
                        UniversalAiStreamEvent(
                            sequence: Int(event.sequence),
                            text: event.text
                        )
                    )
                },
                onComplete: {
                    state.finish()
                },
                onError: { error in
                    state.fail(Self.map(error))
                }
            )
            let handleBox = AppleCancellationHandleBox(handle)
            testingHooks.beforeStreamCancellationInstallation()
            state.installCancellation {
                handleBox.cancel()
            }
        }

        let iteratorBox = ThrowingStreamIteratorBox(stream: callbackStream)

        return AsyncThrowingStream(unfolding: {
            try await withTaskCancellationHandler {
                try Task.checkCancellation()
                let event = try await iteratorBox.next()
                try Task.checkCancellation()
                return event
            } onCancel: {
                state.cancel()
            }
        })
    }

    func resetDiagnosticsForTesting() {
        bridge.resetInstrumentation()
    }

    func diagnosticsForTesting() -> UniversalAiConnectorDiagnostics {
        let snapshot = bridge.instrumentationSnapshot()
        return UniversalAiConnectorDiagnostics(
            responseCancellations: Int(snapshot.responseCancellations),
            streamCancellations: Int(snapshot.streamCancellations)
        )
    }

    private static func map(
        _ error: AppleBridgeError
    ) -> UniversalAiConnectorError {
        switch error.code {
        case "invalid_input":
            return .invalidInput
        case "simulated_failure":
            return .simulatedFailure(
                code: error.code,
                message: error.message
            )
        default:
            return .connectorFailure(message: error.message)
        }
    }
}
