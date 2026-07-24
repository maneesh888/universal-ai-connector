package com.maneesh.universalai.apple

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.contract.OutputId
import com.maneesh.universalai.connector.contract.ResponseId
import com.maneesh.universalai.connector.contract.StructuredOutputValue
import com.maneesh.universalai.connector.contract.UniversalAiCompletionReason
import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiOutput
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.extension.ExtensionNamespace
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.internal.ConnectorEngine
import com.maneesh.universalai.connector.internal.DeterministicConnectorEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppleConnectorBridgeTests {
    @Test
    fun versionAndResponseUseCanonicalAppleValues() = runTest {
        val bridge = AppleConnectorBridge(this)
        val successes = mutableListOf<AppleBridgeResponse>()
        val errors = mutableListOf<AppleBridgeError>()

        assertEquals("0.1.0-alpha.1", bridge.version())
        bridge.respond(
            request = request(" hello "),
            onSuccess = successes::add,
            onError = errors::add,
        )
        advanceUntilIdle()

        assertEquals(1, successes.size)
        assertDeterministicResponse(
            response = successes.single(),
            expectedText = "Kotlin echo:  hello ",
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun responseFailurePreservesRawProviderErrorExactlyOnce() = runTest {
        val bridge = AppleConnectorBridge(this)
        val successes = mutableListOf<AppleBridgeResponse>()
        val errors = mutableListOf<AppleBridgeError>()

        bridge.respond(
            request = request("__force_error__"),
            onSuccess = successes::add,
            onError = errors::add,
        )
        advanceUntilIdle()

        assertTrue(successes.isEmpty())
        assertEquals(1, errors.size)
        with(errors.single()) {
            assertEquals("provider", category)
            assertEquals("simulated_failure", code)
            assertEquals(
                "The Universal AI Connector produced the requested simulated failure.",
                message,
            )
            assertNull(metadata)
            assertTrue(extensions.entries.isEmpty())
        }
    }

    @Test
    fun requestConversionFailureMapsToSafeValidationError() = runTest {
        val bridge = AppleConnectorBridge(this)
        val successes = mutableListOf<AppleBridgeResponse>()
        val errors = mutableListOf<AppleBridgeError>()

        bridge.respond(
            request =
                request(
                    content = "hello",
                    providerId = "INVALID",
                ),
            onSuccess = successes::add,
            onError = errors::add,
        )
        assertEquals(1, errors.size)
        advanceUntilIdle()

        assertTrue(successes.isEmpty())
        assertEquals(1, errors.size)
        with(errors.single()) {
            assertEquals("validation", category)
            assertEquals("invalid_request", code)
            assertEquals("Request validation failed.", message)
            assertTrue(extensions.entries.isEmpty())
            val metadata = assertNotNull(metadata)
            assertEquals(
                "/target/providerId",
                (metadata.member("path") as AppleBridgeJsonString).value,
            )
            assertEquals(
                "invalid_provider_id",
                (metadata.member("validationCode") as AppleBridgeJsonString).value,
            )
        }
    }

    @Test
    fun versionAndStructuredSchemaFailuresUseStableFullRequestPaths() = runTest {
        val bridge = AppleConnectorBridge(this)
        val schema =
            AppleBridgeJsonObject(
                listOf(
                    AppleBridgeJsonObjectEntry(
                        name = "type",
                        value = AppleBridgeJsonString("string"),
                    ),
                ),
            )
        val unsupportedKeywordSchema =
            AppleBridgeJsonObject(
                listOf(
                    AppleBridgeJsonObjectEntry(
                        name = "pattern",
                        value = AppleBridgeJsonString("x"),
                    ),
                ),
            )
        val requests =
            listOf(
                request(content = "version", contractVersion = "2"),
                request(
                    content = "missing",
                    responseFormat =
                        AppleBridgeResponseFormat(
                            kind = "json_schema",
                            schema = null,
                        ),
                ),
                request(
                    content = "plain unexpected",
                    responseFormat =
                        AppleBridgeResponseFormat(
                            kind = "plain_text",
                            schema = schema,
                        ),
                ),
                request(
                    content = "invalid schema",
                    responseFormat =
                        AppleBridgeResponseFormat(
                            kind = "json_schema",
                            schema = unsupportedKeywordSchema,
                        ),
                ),
                request(
                    content = "future unexpected",
                    responseFormat =
                        AppleBridgeResponseFormat(
                            kind = "future_format",
                            schema = schema,
                        ),
                ),
            )
        val errors = MutableList<AppleBridgeError?>(requests.size) { null }
        requests.forEachIndexed { index, value ->
            bridge.respond(
                request = value,
                onSuccess = { error("Expected request conversion failure.") },
                onError = { failure -> errors[index] = failure },
            )
        }
        assertTrue(errors.all { failure -> failure != null })
        advanceUntilIdle()

        val expected =
            listOf(
                "unsupported_contract_version" to "/contractVersion",
                "missing_response_schema" to "/responseFormat/schema",
                "unexpected_response_schema" to "/responseFormat/schema",
                "unsupported_schema_keyword" to "/responseFormat/schema/pattern",
                "unexpected_response_schema" to "/responseFormat/schema",
            )
        errors.zip(expected).forEach { (failure, expectation) ->
            with(assertNotNull(failure)) {
                assertEquals("validation", category)
                assertEquals("invalid_request", code)
                val safeMetadata = assertNotNull(metadata)
                assertEquals(
                    expectation.first,
                    (safeMetadata.member("validationCode") as AppleBridgeJsonString).value,
                )
                assertEquals(
                    expectation.second,
                    (safeMetadata.member("path") as AppleBridgeJsonString).value,
                )
            }
        }
    }

    @Test
    fun structuredSchemaConversionPreservesGenericJsonNumberTokens() = runTest {
        val longNumber = "1".repeat(129)
        val schema =
            AppleBridgeJsonObject(
                listOf(
                    AppleBridgeJsonObjectEntry(
                        name = "type",
                        value = AppleBridgeJsonString("number"),
                    ),
                    AppleBridgeJsonObjectEntry(
                        name = "minimum",
                        value = AppleBridgeJsonNumber(longNumber),
                    ),
                    AppleBridgeJsonObjectEntry(
                        name = "maximum",
                        value = AppleBridgeJsonNumber("1.2300e+40"),
                    ),
                    AppleBridgeJsonObjectEntry(
                        name = "const",
                        value = AppleBridgeJsonNumber("-0"),
                    ),
                ),
            )
        val engine = CapturingRequestEngine()
        val bridge =
            AppleConnectorBridge(
                connector = UniversalAiConnector(engine),
                injectedScope = this,
            )
        val successes = mutableListOf<AppleBridgeResponse>()
        val errors = mutableListOf<AppleBridgeError>()

        bridge.respond(
            request =
                request(
                    content = "schema numbers",
                    responseFormat =
                        AppleBridgeResponseFormat(
                            kind = "json_schema",
                            schema = schema,
                        ),
                ),
            onSuccess = successes::add,
            onError = errors::add,
        )
        advanceUntilIdle()

        assertEquals(1, successes.size)
        assertTrue(errors.isEmpty())
        val encodedSchema =
            assertNotNull(
                assertNotNull(engine.capturedRequest)
                    .responseFormat
                    .schema,
            ).toJson()
        assertTrue(encodedSchema.contains("\"minimum\":$longNumber"))
        assertTrue(encodedSchema.contains("\"maximum\":1.2300e+40"))
        assertTrue(encodedSchema.contains("\"const\":-0"))
    }

    @Test
    fun deterministicSelectionFailurePreservesCanonicalValidationError() = runTest {
        val bridge = AppleConnectorBridge(this)
        val successes = mutableListOf<AppleBridgeResponse>()
        val errors = mutableListOf<AppleBridgeError>()

        bridge.respond(
            request =
                request(
                    content = "hello",
                    modelId = "unsupported-model",
                ),
            onSuccess = successes::add,
            onError = errors::add,
        )
        advanceUntilIdle()

        assertTrue(successes.isEmpty())
        assertEquals(1, errors.size)
        with(errors.single()) {
            assertEquals("validation", category)
            assertEquals("invalid_request", code)
            assertEquals(
                "The deterministic connector supports only target deterministic/echo-v1.",
                message,
            )
            assertNull(metadata)
        }
    }

    @Test
    fun streamEmitsSixCanonicalEventsAndOneCompletion() = runTest {
        val bridge = AppleConnectorBridge(this)
        val events = mutableListOf<AppleBridgeStreamEvent>()
        var completions = 0
        val errors = mutableListOf<AppleBridgeError>()

        bridge.stream(
            request = request("chunk"),
            onEvent = events::add,
            onComplete = { completions += 1 },
            onError = errors::add,
        )
        advanceUntilIdle()

        assertEquals((1L..6L).toList(), events.map { it.sequence })
        assertEquals(
            listOf(
                "response.started",
                "output.started",
                "output.delta",
                "output.delta",
                "output.completed",
                "response.completed",
            ),
            events.map { it.type },
        )
        assertEquals(listOf(false, false, false, false, false, true), events.map { it.terminal })
        assertTrue(events.all { event -> event.contractVersion == "1" })
        assertTrue(events.all { event -> event.responseId == "deterministic-response" })
        assertTrue(events.all { event -> event.requestId == null })
        assertEquals(
            listOf(false, true, true, true, true, false),
            events.map { it.hasOutputIndex },
        )
        assertEquals(
            listOf(null, "deterministic-output-0", "deterministic-output-0", "deterministic-output-0", "deterministic-output-0", null),
            events.map { it.outputId },
        )
        assertEquals(listOf("Kotlin echo: ", "chunk"), events.mapNotNull { it.delta })
        with(assertNotNull(events[4].output)) {
            assertEquals("deterministic-output-0", id)
            assertEquals(0, index)
            assertEquals("text", kind)
            assertEquals("Kotlin echo: chunk", text)
            assertNull(structuredJson)
        }
        assertDeterministicResponse(
            response = assertNotNull(events.last().response),
            expectedText = "Kotlin echo: chunk",
        )
        assertEquals(1, completions)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun streamFailureUsesOneRawCanonicalErrorTerminal() = runTest {
        val bridge = AppleConnectorBridge(this)
        val events = mutableListOf<AppleBridgeStreamEvent>()
        var completions = 0
        val errors = mutableListOf<AppleBridgeError>()

        bridge.stream(
            request = request("__force_error__"),
            onEvent = events::add,
            onComplete = { completions += 1 },
            onError = errors::add,
        )
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertEquals(0, completions)
        assertEquals(1, errors.size)
        assertEquals("provider", errors.single().category)
        assertEquals("simulated_failure", errors.single().code)
    }

    @Test
    fun streamFailureAfterPartialDeltaDeliversOneErrorAndNoCompletion() = runTest {
        val bridge =
            AppleConnectorBridge(
                connector = UniversalAiConnector(PartialFailureEngine()),
                injectedScope = this,
            )
        val events = mutableListOf<AppleBridgeStreamEvent>()
        var completions = 0
        val errors = mutableListOf<AppleBridgeError>()

        bridge.stream(
            request = request("partial failure"),
            onEvent = events::add,
            onComplete = { completions += 1 },
            onError = errors::add,
        )
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequence })
        assertEquals(
            listOf("response.started", "output.started", "output.delta"),
            events.map { it.type },
        )
        assertEquals(listOf("Kotlin echo: "), events.mapNotNull { it.delta })
        assertTrue(events.none { event -> event.terminal })
        assertTrue(events.none { event -> event.type == "response.completed" })
        assertEquals(0, completions)
        assertEquals(1, errors.size)
        assertEquals("provider", errors.single().category)
        assertEquals("simulated_failure", errors.single().code)
        assertEquals(PARTIAL_FAILURE_MESSAGE, errors.single().message)
    }

    @Test
    fun streamStopsAfterFirstTerminalAndSuppressesLateFramesAndFailure() = runTest {
        val engine = LateAfterTerminalEngine()
        val bridge =
            AppleConnectorBridge(
                connector = UniversalAiConnector(engine),
                injectedScope = this,
            )
        val events = mutableListOf<AppleBridgeStreamEvent>()
        var completions = 0
        val errors = mutableListOf<AppleBridgeError>()

        bridge.stream(
            request = request("late attempts"),
            onEvent = events::add,
            onComplete = { completions += 1 },
            onError = errors::add,
        )
        advanceUntilIdle()

        assertEquals((1L..6L).toList(), events.map { event -> event.sequence })
        assertEquals(1, events.count { event -> event.terminal })
        assertEquals("response.completed", events.last().type)
        assertEquals(1, completions)
        assertTrue(errors.isEmpty())
        assertTrue(engine.duplicateTerminalAttempted)
        assertTrue(engine.lateEventAttempted)
        assertTrue(engine.lateFailureAttempted)
    }

    @Test
    fun responseCancellationDeliversNoCallback() = runTest {
        val bridge = AppleConnectorBridge(this)
        bridge.resetInstrumentation()
        var callbackDelivered = false

        val handle =
            bridge.respond(
                request = request("cancel"),
                onSuccess = { callbackDelivered = true },
                onError = { callbackDelivered = true },
            )
        handle.cancel()
        runCurrent()
        advanceUntilIdle()

        assertFalse(callbackDelivered)
        assertEquals(1, bridge.instrumentationSnapshot().responseCancellations)
    }

    @Test
    fun streamCancellationAfterFirstDeltaDeliversNoTerminal() = runTest {
        val bridge = AppleConnectorBridge(this)
        bridge.resetInstrumentation()
        val events = mutableListOf<AppleBridgeStreamEvent>()
        var terminalDelivered = false
        lateinit var handle: AppleCancellationHandle

        handle =
            bridge.stream(
                request = request("stop"),
                onEvent = { event ->
                    events += event
                    if (event.type == "output.delta") {
                        handle.cancel()
                    }
                },
                onComplete = { terminalDelivered = true },
                onError = { terminalDelivered = true },
            )
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequence })
        assertEquals(
            listOf("response.started", "output.started", "output.delta"),
            events.map { it.type },
        )
        assertEquals(listOf("Kotlin echo: "), events.mapNotNull { it.delta })
        assertTrue(events.none { event -> event.terminal })
        assertFalse(terminalDelivered)
        assertEquals(1, bridge.instrumentationSnapshot().streamCancellations)
    }

    @Test
    fun concurrentOperationsKeepIndependentExactlyOnceTerminals() = runTest {
        val bridge = AppleConnectorBridge(this)
        val responses = mutableListOf<AppleBridgeResponse>()
        val responseErrors = mutableListOf<AppleBridgeError>()
        val streamEvents = List(3) { mutableListOf<AppleBridgeStreamEvent>() }
        val streamCompletions = MutableList(3) { 0 }
        val streamErrors = List(3) { mutableListOf<AppleBridgeError>() }

        repeat(3) { index ->
            bridge.respond(
                request = request("request-$index"),
                onSuccess = responses::add,
                onError = responseErrors::add,
            )
            bridge.stream(
                request = request("stream-$index"),
                onEvent = streamEvents[index]::add,
                onComplete = { streamCompletions[index] += 1 },
                onError = streamErrors[index]::add,
            )
        }
        advanceUntilIdle()

        assertEquals(
            setOf(
                "Kotlin echo: request-0",
                "Kotlin echo: request-1",
                "Kotlin echo: request-2",
            ),
            responses.map { response -> response.outputs.single().text }.toSet(),
        )
        assertEquals(3, responses.size)
        assertTrue(responseErrors.isEmpty())
        repeat(3) { index ->
            val events = streamEvents[index]
            assertEquals((1L..6L).toList(), events.map { event -> event.sequence })
            assertEquals(
                listOf("Kotlin echo: ", "stream-$index"),
                events.mapNotNull { event -> event.delta },
            )
            assertEquals(1, events.count { event -> event.terminal })
            assertEquals(1, streamCompletions[index])
            assertTrue(streamErrors[index].isEmpty())
        }
    }

    @Test
    fun recursiveJsonAndExtensionsRoundTripThroughInternalConversions() {
        val recursivePayload =
            AppleBridgeJsonObject(
                listOf(
                    AppleBridgeJsonObjectEntry(
                        "array",
                        AppleBridgeJsonArray(
                            listOf(
                                AppleBridgeJsonNull(),
                                AppleBridgeJsonBoolean(true),
                                AppleBridgeJsonString("text"),
                                AppleBridgeJsonNumber("1.2300e+4"),
                                AppleBridgeJsonObject(
                                    listOf(
                                        AppleBridgeJsonObjectEntry(
                                            "nested",
                                            AppleBridgeJsonNumber("-0"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    AppleBridgeJsonObjectEntry(
                        "object",
                        AppleBridgeJsonObject(
                            listOf(
                                AppleBridgeJsonObjectEntry(
                                    "child",
                                    AppleBridgeJsonArray(
                                        listOf(
                                            AppleBridgeJsonString("x"),
                                            AppleBridgeJsonBoolean(false),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        val namespace = "com.example.roundtrip"
        val canonicalRequest =
            request(
                content = "round trip",
                extensions =
                    AppleBridgeExtensions(
                        listOf(
                            AppleBridgeExtensionEntry(
                                namespace = namespace,
                                payload = recursivePayload,
                            ),
                        ),
                    ),
            ).toCanonicalRequest()

        val canonicalPayload =
            assertNotNull(
                canonicalRequest.extensions[ExtensionNamespace.of(namespace)],
            )
        val canonicalArray = assertNotNull(canonicalPayload.array("array"))
        assertEquals(
            "1.2300e+4",
            (canonicalArray.values[3] as ExtensionValue.NumberValue).value.rawValue,
        )
        assertEquals(
            "-0",
            assertNotNull(canonicalArray.values[4] as? ExtensionValue.ObjectValue)
                .number("nested")
                ?.rawValue,
        )

        val canonicalResponse =
            UniversalAiResponse(
                id = ResponseId.of("recursive-response"),
                target = canonicalRequest.target,
                outputs =
                    listOf(
                        UniversalAiOutput.structuredJson(
                            id = OutputId.of("recursive-output"),
                            index = 0,
                            value =
                                StructuredOutputValue.parse(
                                    """
                                    {
                                      "array": [null, true, "text", 1.2300e+4, {"nested": -0}],
                                      "object": {"child": ["x", false]}
                                    }
                                    """.trimIndent(),
                                ),
                        ),
                    ),
                completionReason = UniversalAiCompletionReason.Stop,
                extensions = canonicalRequest.extensions,
            )
        val appleResponse = canonicalResponse.toAppleBridgeResponse()
        val roundTrippedExtension = appleResponse.extensions.entries.single()

        assertEquals(namespace, roundTrippedExtension.namespace)
        assertEquals(recursivePayload.snapshot(), roundTrippedExtension.payload.snapshot())
        assertEquals(
            recursivePayload.snapshot(),
            assertNotNull(appleResponse.outputs.single().structuredJson).snapshot(),
        )
    }

    private fun assertDeterministicResponse(
        response: AppleBridgeResponse,
        expectedText: String,
    ) {
        assertEquals("1", response.contractVersion)
        assertEquals("deterministic-response", response.id)
        assertNull(response.requestId)
        assertEquals("deterministic", response.target.providerRawValue)
        assertEquals("echo-v1", response.target.modelRawValue)
        assertNull(response.usage)
        assertEquals("stop", response.completionReason)
        assertTrue(response.extensions.entries.isEmpty())
        assertEquals(1, response.outputs.size)
        with(response.outputs.single()) {
            assertEquals("deterministic-output-0", id)
            assertEquals(0, index)
            assertEquals("text", kind)
            assertEquals(expectedText, text)
            assertNull(structuredJson)
            assertTrue(extensions.entries.isEmpty())
        }
    }

    private fun request(
        content: String,
        contractVersion: String = "1",
        providerId: String = "deterministic",
        modelId: String = "echo-v1",
        responseFormat: AppleBridgeResponseFormat =
            AppleBridgeResponseFormat(
                kind = "plain_text",
                schema = null,
            ),
        extensions: AppleBridgeExtensions = AppleBridgeExtensions(emptyList()),
    ): AppleBridgeRequest =
        AppleBridgeRequest(
            contractVersion = contractVersion,
            target =
                AppleBridgeTarget(
                    providerRawValue = providerId,
                    modelRawValue = modelId,
                ),
            input =
                listOf(
                    AppleBridgeTextInput(
                        role = "user",
                        content = content,
                    ),
                ),
            responseFormat = responseFormat,
            generation =
                AppleBridgeGenerationParameters(
                    hasMaxOutputTokens = false,
                    maxOutputTokens = 0L,
                    hasTemperature = false,
                    temperature = 0.0,
                    hasTopP = false,
                    topP = 0.0,
                    stopSequences = emptyList(),
                ),
            extensions = extensions,
        )

    private fun AppleBridgeJsonObject.member(name: String): AppleBridgeJsonValue =
        entries.single { entry -> entry.name == name }.value

    private fun AppleBridgeJsonValue.snapshot(): String =
        when (this) {
            is AppleBridgeJsonNull -> "null"
            is AppleBridgeJsonBoolean -> "boolean:$value"
            is AppleBridgeJsonString -> "string:${value.length}:$value"
            is AppleBridgeJsonNumber -> "number:$rawValue"
            is AppleBridgeJsonArray ->
                values.joinToString(
                    prefix = "array:[",
                    postfix = "]",
                    separator = ",",
                ) { value ->
                    value.snapshot()
                }

            is AppleBridgeJsonObject ->
                entries
                    .sortedBy { entry -> entry.name }
                    .joinToString(
                        prefix = "object:{",
                        postfix = "}",
                        separator = ",",
                    ) { entry ->
                        "${entry.name}=${entry.value.snapshot()}"
                    }
        }

    private class PartialFailureEngine : ConnectorEngine {
        private val delegate = DeterministicConnectorEngine()

        override suspend fun respond(request: UniversalAiRequest) = delegate.respond(request)

        override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> = flow {
            delegate.stream(request).take(PARTIAL_EVENT_COUNT).collect { event -> emit(event) }
            throw UniversalAiException(
                UniversalAiError(
                    category = UniversalAiErrorCategory.Provider,
                    code = UniversalAiErrorCode.SimulatedFailure,
                    message = PARTIAL_FAILURE_MESSAGE,
                ),
            )
        }
    }

    private class CapturingRequestEngine : ConnectorEngine {
        var capturedRequest: UniversalAiRequest? = null
            private set

        override suspend fun respond(
            request: UniversalAiRequest,
        ): UniversalAiResponse {
            capturedRequest = request
            return UniversalAiResponse(
                id = ResponseId.of("captured-response"),
                target = request.target,
                outputs =
                    listOf(
                        UniversalAiOutput.text(
                            id = OutputId.of("captured-output"),
                            index = 0,
                            text = "captured",
                        ),
                    ),
                completionReason = UniversalAiCompletionReason.Stop,
            )
        }

        override fun stream(
            request: UniversalAiRequest,
        ): Flow<UniversalAiStreamEvent> = flow {
            capturedRequest = request
        }
    }

    private class LateAfterTerminalEngine : ConnectorEngine {
        private val delegate = DeterministicConnectorEngine()

        var duplicateTerminalAttempted = false
            private set

        var lateEventAttempted = false
            private set

        var lateFailureAttempted = false
            private set

        override suspend fun respond(request: UniversalAiRequest) = delegate.respond(request)

        override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> = flow {
            val events = delegate.stream(request).toList()
            events.dropLast(1).forEach { event -> emit(event) }

            val terminal = events.last()
            try {
                emit(terminal)
            } catch (_: Throwable) {
                // This deliberately nonconforming engine continues to exercise adapter arbitration.
            }

            duplicateTerminalAttempted = true
            try {
                emit(terminal)
            } catch (_: Throwable) {
                // The adapter must already have stopped accepting upstream frames.
            }

            lateEventAttempted = true
            try {
                emit(
                    UniversalAiStreamEvent(
                        type = UniversalAiStreamEventType.of("response.progress"),
                        terminal = false,
                        sequence = terminal.sequence + 1,
                        responseId = terminal.responseId,
                        requestId = terminal.requestId,
                    ),
                )
            } catch (_: Throwable) {
                // The adapter must already have stopped accepting upstream frames.
            }

            lateFailureAttempted = true
            throw UniversalAiException(
                UniversalAiError(
                    category = UniversalAiErrorCategory.Provider,
                    code = UniversalAiErrorCode.SimulatedFailure,
                    message = LATE_FAILURE_MESSAGE,
                ),
            )
        }
    }

    private companion object {
        const val PARTIAL_EVENT_COUNT = 3
        const val PARTIAL_FAILURE_MESSAGE = "The deterministic partial stream failed."
        const val LATE_FAILURE_MESSAGE = "The deterministic stream failed after its terminal."
    }
}
