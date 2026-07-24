package com.maneesh.universalai.connector.contract

import com.maneesh.universalai.connector.contract.extension.ExtensionNamespace
import com.maneesh.universalai.connector.contract.extension.ExtensionValue
import com.maneesh.universalai.connector.contract.extension.Extensions
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class UniversalAiStreamContractsTest {
    private val requestId = RequestId.of("request-1")
    private val responseId = ResponseId.of("response-1")
    private val target =
        UniversalAiTarget(
            providerId = ProviderId.of("deterministic"),
            modelId = ModelId.of("model-v1"),
        )

    @Test
    fun validSingleOutputSequenceAllowsSnapshotCompletionAndFinalUsageAdvance() {
        val output = UniversalAiOutput.text(OutputId.of("output-1"), 0, "Hello")
        val usageUpdate =
            usage(
                input = 2,
                output = 1,
                inputDetails = mapOf("cached" to 1),
            )
        val finalUsage =
            usage(
                input = 3,
                output = 2,
                inputDetails = mapOf("cached" to 2),
                outputDetails = mapOf("reasoning" to 1),
            )
        val validator = UniversalAiStreamSequenceValidator()

        listOf(
            event(UniversalAiStreamEventType.ResponseStarted, 1),
            event(
                type = UniversalAiStreamEventType.OutputStarted,
                sequence = 2,
                outputId = output.id,
                outputIndex = output.index,
            ),
            event(
                type = UniversalAiStreamEventType.OutputCompleted,
                sequence = 3,
                outputId = output.id,
                outputIndex = output.index,
                output = output,
            ),
            event(
                type = UniversalAiStreamEventType.UsageUpdated,
                sequence = 4,
                usage = usageUpdate,
            ),
            completedEvent(
                sequence = 5,
                outputs = listOf(output),
                usage = finalUsage,
            ),
        ).map { event ->
            UniversalAiStreamEvent.fromJson(event.toJson())
        }.forEach(validator::accept)

        validator.finish()
    }

    @Test
    fun streamEventVersionMarkerIsRequiredNonNullAndRejectsFutureMajors() {
        val current =
            event(
                type = UniversalAiStreamEventType.ResponseStarted,
                sequence = 1,
            ).toJson()

        assertFailsWith<SerializationException> {
            UniversalAiStreamEvent.fromJson(
                current.replace("\"contractVersion\":\"1\",", ""),
            )
        }
        assertFailsWith<SerializationException> {
            UniversalAiStreamEvent.fromJson(
                current.replace("\"contractVersion\":\"1\"", "\"contractVersion\":null"),
            )
        }
        assertSemanticFailure("unsupported_contract_version", "/contractVersion") {
            UniversalAiStreamEvent.fromJson(
                current.replace("\"contractVersion\":\"1\"", "\"contractVersion\":\"2\""),
            )
        }
    }

    @Test
    fun validMultiOutputSequenceAllowsInterleavedTextAndStructuredDeltas() {
        val textOutput = UniversalAiOutput.text(OutputId.of("text-output"), 0, "Hello")
        val structuredValue = StructuredOutputValue.parse("""{"answer":"yes"}""")
        val structuredOutput =
            UniversalAiOutput.structuredJson(
                id = OutputId.of("json-output"),
                index = 1,
                value = structuredValue,
            )
        val structuredJson = structuredValue.toJson()
        val split = structuredJson.indexOf(':') + 1
        val validator = UniversalAiStreamSequenceValidator()

        listOf(
            event(UniversalAiStreamEventType.ResponseStarted, 1),
            event(
                UniversalAiStreamEventType.OutputStarted,
                2,
                outputId = textOutput.id,
                outputIndex = textOutput.index,
            ),
            event(
                UniversalAiStreamEventType.OutputStarted,
                3,
                outputId = structuredOutput.id,
                outputIndex = structuredOutput.index,
            ),
            event(
                UniversalAiStreamEventType.OutputDelta,
                4,
                outputId = structuredOutput.id,
                outputIndex = structuredOutput.index,
                delta = structuredJson.substring(0, split),
            ),
            event(
                UniversalAiStreamEventType.OutputDelta,
                5,
                outputId = textOutput.id,
                outputIndex = textOutput.index,
                delta = "Hel",
            ),
            event(
                UniversalAiStreamEventType.OutputDelta,
                6,
                outputId = structuredOutput.id,
                outputIndex = structuredOutput.index,
                delta = structuredJson.substring(split),
            ),
            event(
                UniversalAiStreamEventType.OutputDelta,
                7,
                outputId = textOutput.id,
                outputIndex = textOutput.index,
                delta = "lo",
            ),
            event(
                UniversalAiStreamEventType.OutputCompleted,
                8,
                outputId = textOutput.id,
                outputIndex = textOutput.index,
                output = textOutput,
            ),
            event(
                UniversalAiStreamEventType.OutputCompleted,
                9,
                outputId = structuredOutput.id,
                outputIndex = structuredOutput.index,
                output = structuredOutput,
            ),
            completedEvent(
                sequence = 10,
                outputs = listOf(textOutput, structuredOutput),
            ),
        ).forEach(validator::accept)

        validator.finish()
    }

    @Test
    fun sequenceGapsAndReorderingAreRejectedAtTheSequenceField() {
        val gap = UniversalAiStreamSequenceValidator()
        gap.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/sequence",
        ) {
            gap.accept(
                event(
                    UniversalAiStreamEventType.UsageUpdated,
                    3,
                    usage = usage(1, 0),
                ),
            )
        }

        val reordered = UniversalAiStreamSequenceValidator()
        reordered.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/sequence",
        ) {
            reordered.accept(
                event(
                    UniversalAiStreamEventType.UsageUpdated,
                    1,
                    usage = usage(1, 0),
                ),
            )
        }
    }

    @Test
    fun requestAndResponseCorrelationCannotChange() {
        val changedRequest = UniversalAiStreamSequenceValidator()
        changedRequest.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/requestId",
        ) {
            changedRequest.accept(
                event(
                    type = UniversalAiStreamEventType.UsageUpdated,
                    sequence = 2,
                    requestId = RequestId.of("request-2"),
                    usage = usage(1, 0),
                ),
            )
        }

        val changedResponse = UniversalAiStreamSequenceValidator()
        changedResponse.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/responseId",
        ) {
            changedResponse.accept(
                event(
                    type = UniversalAiStreamEventType.UsageUpdated,
                    sequence = 2,
                    responseId = ResponseId.of("response-2"),
                    usage = usage(1, 0),
                ),
            )
        }
    }

    @Test
    fun outputDeltaRequiresAStartedOpenOutputWithStableIdentity() {
        val outputId = OutputId.of("output-1")
        val beforeStart = UniversalAiStreamSequenceValidator()
        beforeStart.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/outputId",
        ) {
            beforeStart.accept(
                event(
                    UniversalAiStreamEventType.OutputDelta,
                    2,
                    outputId = outputId,
                    outputIndex = 0,
                    delta = "A",
                ),
            )
        }

        val changedIndex = UniversalAiStreamSequenceValidator()
        changedIndex.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        changedIndex.accept(
            event(
                UniversalAiStreamEventType.OutputStarted,
                2,
                outputId = outputId,
                outputIndex = 0,
            ),
        )
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/outputIndex",
        ) {
            changedIndex.accept(
                event(
                    UniversalAiStreamEventType.OutputDelta,
                    3,
                    outputId = outputId,
                    outputIndex = 1,
                    delta = "A",
                ),
            )
        }
    }

    @Test
    fun accumulatedDeltasCannotExceedTheBoundedOutputLimit() {
        val outputId = OutputId.of("output-1")
        val validator = UniversalAiStreamSequenceValidator()
        val largeDelta = "a".repeat(600_000)
        validator.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        validator.accept(
            event(
                UniversalAiStreamEventType.OutputStarted,
                2,
                outputId = outputId,
                outputIndex = 0,
            ),
        )
        validator.accept(
            event(
                UniversalAiStreamEventType.OutputDelta,
                3,
                outputId = outputId,
                outputIndex = 0,
                delta = largeDelta,
            ),
        )

        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/delta",
        ) {
            validator.accept(
                event(
                    UniversalAiStreamEventType.OutputDelta,
                    4,
                    outputId = outputId,
                    outputIndex = 0,
                    delta = largeDelta,
                ),
            )
        }
    }

    @Test
    fun completedOutputMustMatchItsEnvelopeAndAccumulatedDeltas() {
        val outputId = OutputId.of("output-1")
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/output/id",
        ) {
            event(
                UniversalAiStreamEventType.OutputCompleted,
                1,
                outputId = outputId,
                outputIndex = 0,
                output = UniversalAiOutput.text(OutputId.of("different"), 0, "Hello"),
            )
        }

        val validator = UniversalAiStreamSequenceValidator()
        validator.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        validator.accept(
            event(
                UniversalAiStreamEventType.OutputStarted,
                2,
                outputId = outputId,
                outputIndex = 0,
            ),
        )
        validator.accept(
            event(
                UniversalAiStreamEventType.OutputDelta,
                3,
                outputId = outputId,
                outputIndex = 0,
                delta = "partial",
            ),
        )
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/output",
        ) {
            validator.accept(
                event(
                    UniversalAiStreamEventType.OutputCompleted,
                    4,
                    outputId = outputId,
                    outputIndex = 0,
                    output = UniversalAiOutput.text(outputId, 0, "different"),
                ),
            )
        }
    }

    @Test
    fun usageCountersAndPreviouslyReportedBreakdownsCannotRegress() {
        val tokenRegression = UniversalAiStreamSequenceValidator()
        tokenRegression.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        tokenRegression.accept(
            event(
                UniversalAiStreamEventType.UsageUpdated,
                2,
                usage = usage(2, 1),
            ),
        )
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/usage/outputTokens",
        ) {
            tokenRegression.accept(
                event(
                    UniversalAiStreamEventType.UsageUpdated,
                    3,
                    usage = usage(3, 0),
                ),
            )
        }

        val missingBreakdown = UniversalAiStreamSequenceValidator()
        missingBreakdown.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        missingBreakdown.accept(
            event(
                UniversalAiStreamEventType.UsageUpdated,
                2,
                usage =
                    usage(
                        input = 2,
                        output = 1,
                        inputDetails = mapOf("cached" to 1),
                    ),
            ),
        )
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/usage/inputDetails/cached",
        ) {
            missingBreakdown.accept(
                event(
                    UniversalAiStreamEventType.UsageUpdated,
                    3,
                    usage = usage(3, 2),
                ),
            )
        }
    }

    @Test
    fun terminalRequiresEveryOutputClosedAndAConsistentFinalSnapshot() {
        val output = UniversalAiOutput.text(OutputId.of("output-1"), 0, "Hello")
        val validator = UniversalAiStreamSequenceValidator()
        validator.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        validator.accept(
            event(
                UniversalAiStreamEventType.OutputStarted,
                2,
                outputId = output.id,
                outputIndex = output.index,
            ),
        )

        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/response/outputs",
        ) {
            validator.accept(
                completedEvent(
                    sequence = 3,
                    outputs = listOf(output),
                ),
            )
        }
    }

    @Test
    fun finishWithoutTerminalIsAnIncompleteStream() {
        val validator = UniversalAiStreamSequenceValidator()
        validator.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))

        assertSemanticFailure(
            code = "incomplete_stream",
            path = "/terminal",
        ) {
            validator.finish()
        }
    }

    @Test
    fun unknownNonterminalTypeAndExtensionsRoundTripWhileOrdinaryMembersDrop() {
        val extensions =
            Extensions.of(
                ExtensionNamespace.of("com.example.future") to
                    ExtensionValue.objectValue(
                        "progress" to ExtensionValue.number("0.5"),
                    ),
            )
        val original =
            event(
                type = UniversalAiStreamEventType.of("response.progress"),
                sequence = 1,
                extensions = extensions,
            )
        val withUnknownMember =
            original.toJson().replace(
                "\"responseId\"",
                "\"futureMember\":{\"preserved\":false},\"responseId\"",
            )

        val decoded = UniversalAiStreamEvent.fromJson(withUnknownMember)

        assertEquals(original, decoded)
        assertFalse(decoded.type.isKnown)
        assertEquals("response.progress", decoded.type.rawValue)
        assertTrue(decoded.toJson().contains("\"terminal\":false"))
        assertFalse(decoded.toJson().contains("futureMember"))
    }

    @Test
    fun unknownTerminalTypeIsRejectedWithItsDedicatedCode() {
        assertSemanticFailure(
            code = "unsupported_terminal_event",
            path = "/terminal",
        ) {
            event(
                type = UniversalAiStreamEventType.of("response.future_terminal"),
                sequence = 1,
                terminal = true,
            )
        }

        val failure =
            assertFailsWith<SerializationException> {
                UniversalAiStreamEvent.fromJson(
                    """
                    {
                      "contractVersion": "1",
                      "type": "response.future_terminal",
                      "terminal": true,
                      "sequence": 1,
                      "responseId": "response-1"
                    }
                    """.trimIndent(),
                )
            }
        assertEquals(
            "unsupported_terminal_event",
            failure.contractSemanticExceptionOrNull()?.code,
        )
        assertEquals(
            "/terminal",
            failure.contractSemanticExceptionOrNull()?.path,
        )
    }

    @Test
    fun duplicateTerminalAndLateEventsAreRejected() {
        val terminal = completedEvent(sequence = 2, outputs = emptyList())

        val duplicate = UniversalAiStreamSequenceValidator()
        duplicate.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        duplicate.accept(terminal)
        duplicate.finish()
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/terminal",
        ) {
            duplicate.accept(completedEvent(sequence = 3, outputs = emptyList()))
        }

        val late = UniversalAiStreamSequenceValidator()
        late.accept(event(UniversalAiStreamEventType.ResponseStarted, 1))
        late.accept(terminal)
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/terminal",
        ) {
            late.accept(
                event(
                    type = UniversalAiStreamEventType.of("response.progress"),
                    sequence = 3,
                ),
            )
        }
    }

    @Test
    fun eventShapeRequiresPositiveSafeSequenceNonemptyDeltasAndExplicitTerminal() {
        listOf(0L, -1L, MAX_JSON_SAFE_INTEGER + 1).forEach { invalidSequence ->
            assertSemanticFailure(
                code = "invalid_stream_sequence",
                path = "/sequence",
            ) {
                event(
                    UniversalAiStreamEventType.ResponseStarted,
                    invalidSequence,
                )
            }
        }
        assertSemanticFailure(
            code = "invalid_stream_sequence",
            path = "/delta",
        ) {
            event(
                UniversalAiStreamEventType.OutputDelta,
                1,
                outputId = OutputId.of("output-1"),
                outputIndex = 0,
                delta = "",
            )
        }
        assertFailsWith<SerializationException> {
            UniversalAiStreamEvent.fromJson(
                """
                {
                  "contractVersion": "1",
                  "type": "response.started",
                  "sequence": 1,
                  "responseId": "response-1"
                }
                """.trimIndent(),
            )
        }
    }

    private fun event(
        type: UniversalAiStreamEventType,
        sequence: Long,
        terminal: Boolean = type == UniversalAiStreamEventType.ResponseCompleted,
        requestId: RequestId? = this.requestId,
        responseId: ResponseId = this.responseId,
        outputId: OutputId? = null,
        outputIndex: Int? = null,
        delta: String? = null,
        output: UniversalAiOutput? = null,
        usage: UniversalAiUsage? = null,
        response: UniversalAiResponse? = null,
        extensions: Extensions = Extensions.Empty,
    ): UniversalAiStreamEvent =
        UniversalAiStreamEvent(
            type = type,
            terminal = terminal,
            sequence = sequence,
            requestId = requestId,
            responseId = responseId,
            outputId = outputId,
            outputIndex = outputIndex,
            delta = delta,
            output = output,
            usage = usage,
            response = response,
            extensions = extensions,
        )

    private fun completedEvent(
        sequence: Long,
        outputs: List<UniversalAiOutput>,
        usage: UniversalAiUsage? = null,
        requestId: RequestId? = this.requestId,
        responseId: ResponseId = this.responseId,
    ): UniversalAiStreamEvent =
        event(
            type = UniversalAiStreamEventType.ResponseCompleted,
            sequence = sequence,
            requestId = requestId,
            responseId = responseId,
            response =
                UniversalAiResponse(
                    id = responseId,
                    requestId = requestId,
                    target = target,
                    outputs = outputs,
                    usage = usage,
                    completionReason = UniversalAiCompletionReason.Stop,
                ),
        )

    private fun usage(
        input: Long,
        output: Long,
        inputDetails: Map<String, Long> = emptyMap(),
        outputDetails: Map<String, Long> = emptyMap(),
    ): UniversalAiUsage =
        UniversalAiUsage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = input + output,
            inputDetails = inputDetails,
            outputDetails = outputDetails,
        )

    private fun assertSemanticFailure(
        code: String,
        path: String,
        block: () -> Unit,
    ) {
        val failure =
            runCatching(block).exceptionOrNull()
                ?: fail("Expected semantic failure $code at $path.")
        val issue =
            failure.contractSemanticExceptionOrNull()
                ?: fail("Expected semantic failure but received ${failure::class.simpleName}.")
        assertEquals(code, issue.code)
        assertEquals(path, issue.path)
    }
}
