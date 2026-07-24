import UniversalAiConnectorBridge

/// The supported Apple client for Universal AI Connector.
///
/// A connector is reusable and supports concurrent responses and independently
/// created streams. Each returned stream supports one consuming task. Cancelling
/// an operation's consuming task is propagated to Kotlin. The connector owns no
/// caller-managed resource, so no explicit cleanup method is required.
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

    /// Returns one deterministic canonical response.
    ///
    /// Cancelling the calling task cancels the Kotlin coroutine and throws
    /// `CancellationError`. Bridge failures are mapped to
    /// ``UniversalAiConnectorError``.
    public func respond(
        to request: UniversalAiRequest
    ) async throws -> UniversalAiResponse {
        try Task.checkCancellation()
        do {
            try UniversalAiContractValidation.validateRequest(request)
        } catch let failure as UniversalAiContractValidationError {
            throw failure.requestConnectorError
        }
        try Task.checkCancellation()

        let state = LockedOperationState<UniversalAiResponse>()

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                state.installContinuation(continuation)

                let handle = bridge.respond(
                    request: request.toBridge(),
                    onSuccess: { response in
                        state.succeed(Self.map(response))
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

    /// Returns an ordered canonical event stream.
    ///
    /// Each returned stream supports one consumer. Concurrent iteration of the
    /// same stream is outside the supported contract; create a separate stream
    /// for each concurrent operation.
    ///
    /// The explicit `response.completed` terminal event is yielded before the
    /// stream finishes normally. Operation failures are thrown out of band and
    /// caller cancellation remains `CancellationError`.
    ///
    /// Cancelling the consuming Swift task while it awaits the next event cancels
    /// the Kotlin coroutine exactly once and makes that read throw
    /// `CancellationError`, even when the returned stream remains strongly
    /// retained. A normal `for try await` break does not request prompt
    /// cancellation while the stream remains retained; the underlying work ends
    /// when its iterator and sequence are released.
    public func stream(
        request: UniversalAiRequest
    ) -> AsyncThrowingStream<UniversalAiStreamEvent, Error> {
        let state = LockedStreamState<UniversalAiStreamEvent>()
        let testingHooks = self.testingHooks

        let callbackStream = AsyncThrowingStream<UniversalAiStreamEvent, Error> {
            continuation in
            continuation.onTermination = { @Sendable _ in
                state.cancel()
            }
            state.installContinuation(continuation)

            if Task.isCancelled {
                state.cancel()
                return
            }
            do {
                try UniversalAiContractValidation.validateRequest(request)
            } catch let failure as UniversalAiContractValidationError {
                state.fail(failure.requestConnectorError)
                return
            } catch {
                state.fail(error)
                return
            }
            if Task.isCancelled {
                state.cancel()
                return
            }

            let handle = bridge.stream(
                request: request.toBridge(),
                onEvent: { event in
                    let mappedEvent = Self.map(event)
                    if mappedEvent.terminal {
                        state.yieldTerminal(mappedEvent)
                    } else {
                        state.yield(mappedEvent)
                    }
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
            if Task.isCancelled {
                state.cancel()
            }
            state.installCancellation {
                handleBox.cancel()
            }
            if Task.isCancelled {
                state.cancel()
            }
        }

        return callbackStream
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
        UniversalAiConnectorError(
            trustedCategory: UniversalAiErrorCategory(
                trustedRawValue: error.category
            ),
            code: UniversalAiErrorCode(trustedRawValue: error.code),
            message: error.message,
            metadata: error.metadata.map(map),
            extensions: map(error.extensions)
        )
    }

    private static func map(
        _ response: AppleBridgeResponse
    ) -> UniversalAiResponse {
        UniversalAiResponse(
            contractVersion: response.contractVersion,
            id: UniversalAiResponseId(rawValue: response.id),
            requestId: response.requestId.map(UniversalAiRequestId.init),
            target: map(response.target),
            outputs: response.outputs.map(map),
            usage: response.usage.map(map),
            completionReason: UniversalAiCompletionReason(
                rawValue: response.completionReason
            ),
            extensions: map(response.extensions)
        )
    }

    private static func map(
        _ event: AppleBridgeStreamEvent
    ) -> UniversalAiStreamEvent {
        UniversalAiStreamEvent(
            contractVersion: event.contractVersion,
            type: UniversalAiStreamEventType(rawValue: event.type),
            terminal: event.terminal,
            sequence: event.sequence,
            responseId: UniversalAiResponseId(rawValue: event.responseId),
            requestId: event.requestId.map(UniversalAiRequestId.init),
            outputId: event.outputId.map(UniversalAiOutputId.init),
            outputIndex: event.hasOutputIndex ? Int(event.outputIndex) : nil,
            delta: event.delta,
            output: event.output.map(map),
            usage: event.usage.map(map),
            response: event.response.map(map),
            extensions: map(event.extensions)
        )
    }

    private static func map(_ target: AppleBridgeTarget) -> UniversalAiTarget {
        UniversalAiTarget(
            providerId: UniversalAiProviderId(
                rawValue: target.providerRawValue
            ),
            modelId: UniversalAiModelId(rawValue: target.modelRawValue)
        )
    }

    private static func map(_ output: AppleBridgeOutput) -> UniversalAiOutput {
        UniversalAiOutput(
            id: UniversalAiOutputId(rawValue: output.id),
            index: Int(output.index),
            kind: UniversalAiOutputKind(rawValue: output.kind),
            text: output.text,
            structuredJson: output.structuredJson.map {
                UniversalAiStructuredOutputValue(value: map($0))
            },
            extensions: map(output.extensions)
        )
    }

    private static func map(_ usage: AppleBridgeUsage) -> UniversalAiUsage {
        UniversalAiUsage(
            inputTokens: usage.inputTokens,
            outputTokens: usage.outputTokens,
            totalTokens: usage.totalTokens,
            inputDetails: Dictionary(
                uniqueKeysWithValues: usage.inputDetails.map {
                    ($0.name, $0.value)
                }
            ),
            outputDetails: Dictionary(
                uniqueKeysWithValues: usage.outputDetails.map {
                    ($0.name, $0.value)
                }
            ),
            extensions: map(usage.extensions)
        )
    }

    private static func map(
        _ extensions: AppleBridgeExtensions
    ) -> UniversalAiExtensions {
        UniversalAiExtensions(
            trustedEntries: Dictionary(
                uniqueKeysWithValues: extensions.entries.map {
                    (
                        UniversalAiExtensionNamespace(
                            trustedRawValue: $0.namespace_
                        ),
                        map($0.payload)
                    )
                }
            )
        )
    }

    private static func map(
        _ object: AppleBridgeJsonObject
    ) -> UniversalAiJsonObject {
        UniversalAiJsonObject(
            Dictionary(
                uniqueKeysWithValues: object.entries.map {
                    ($0.name, map($0.value))
                }
            )
        )
    }

    private static func map(
        _ value: AppleBridgeJsonValue
    ) -> UniversalAiJsonValue {
        switch value {
        case is AppleBridgeJsonNull:
            return .null
        case let value as AppleBridgeJsonBoolean:
            return .boolean(value.value)
        case let value as AppleBridgeJsonString:
            return .string(value.value)
        case let value as AppleBridgeJsonNumber:
            return .number(
                UniversalAiJsonNumber(trustedRawValue: value.rawValue)
            )
        case let value as AppleBridgeJsonArray:
            return .array(value.values.map(map))
        case let value as AppleBridgeJsonObject:
            return .object(map(value))
        default:
            preconditionFailure("Unsupported private Apple bridge JSON value.")
        }
    }
}

private extension UniversalAiRequest {
    func toBridge() -> AppleBridgeRequest {
        AppleBridgeRequest(
            contractVersion: contractVersion,
            target: target.toBridge(),
            input: input.map { $0.toBridge() },
            responseFormat: responseFormat.toBridge(),
            generation: generation.toBridge(),
            extensions: extensions.toBridge()
        )
    }
}

private extension UniversalAiTarget {
    func toBridge() -> AppleBridgeTarget {
        AppleBridgeTarget(
            providerRawValue: providerId.rawValue,
            modelRawValue: modelId.rawValue
        )
    }
}

private extension UniversalAiTextInput {
    func toBridge() -> AppleBridgeTextInput {
        AppleBridgeTextInput(role: role.rawValue, content: content)
    }
}

private extension UniversalAiResponseFormat {
    func toBridge() -> AppleBridgeResponseFormat {
        AppleBridgeResponseFormat(
            kind: kind.rawValue,
            schema: schema?.value.toBridge()
        )
    }
}

private extension UniversalAiGenerationParameters {
    func toBridge() -> AppleBridgeGenerationParameters {
        AppleBridgeGenerationParameters(
            hasMaxOutputTokens: maxOutputTokens != nil,
            maxOutputTokens: Int64(maxOutputTokens ?? 0),
            hasTemperature: temperature != nil,
            temperature: temperature ?? 0,
            hasTopP: topP != nil,
            topP: topP ?? 0,
            stopSequences: stopSequences
        )
    }
}

private extension UniversalAiExtensions {
    func toBridge() -> AppleBridgeExtensions {
        AppleBridgeExtensions(
            entries: entries.map {
                AppleBridgeExtensionEntry(
                    namespace: $0.key.rawValue,
                    payload: $0.value.toBridge()
                )
            }
        )
    }
}

private extension UniversalAiJsonObject {
    func toBridge() -> AppleBridgeJsonObject {
        AppleBridgeJsonObject(
            entries: members.map {
                AppleBridgeJsonObjectEntry(
                    name: $0.key,
                    value: $0.value.toBridge()
                )
            }
        )
    }
}

private extension UniversalAiJsonValue {
    func toBridge() -> AppleBridgeJsonValue {
        switch self {
        case .null:
            return AppleBridgeJsonNull()
        case let .boolean(value):
            return AppleBridgeJsonBoolean(value: value)
        case let .string(value):
            return AppleBridgeJsonString(value: value)
        case let .number(value):
            return AppleBridgeJsonNumber(rawValue: value.rawValue)
        case let .array(values):
            return AppleBridgeJsonArray(values: values.map { $0.toBridge() })
        case let .object(value):
            return value.toBridge()
        }
    }
}
