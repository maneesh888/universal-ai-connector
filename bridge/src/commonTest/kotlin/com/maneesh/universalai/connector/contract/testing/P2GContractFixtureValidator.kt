package com.maneesh.universalai.connector.contract.testing

import com.maneesh.universalai.connector.contract.OutputId
import com.maneesh.universalai.connector.contract.RequestId
import com.maneesh.universalai.connector.contract.ResponseId
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamSequenceValidator
import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiOutput
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiUsage
import com.maneesh.universalai.connector.contract.contractSemanticExceptionOrNull
import com.maneesh.universalai.connector.contract.withPathPrefix
import com.maneesh.universalai.connector.contract.json.CanonicalJson
import com.maneesh.universalai.connector.contract.json.JsonNumberSemantics
import com.maneesh.universalai.connector.contract.schema.semanticEquals
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Common oracle for the direct P2-G response-side model families.
 *
 * NetworkNT remains the authoritative schema implementation in the JVM repository harness. This
 * classifier makes the corpus boundary observable on every common-test target and then requires
 * schema-valid fixtures to agree with the real production serializers on exact semantic code and
 * JSON Pointer.
 */
internal object P2GContractFixtureValidator {
    private val tokenPattern = Regex("^[a-z][a-z0-9._-]{0,63}$")
    private val errorCodePattern = Regex("^[a-z][a-z0-9._-]{0,127}$")

    fun validate(fixture: ContractSeedFixture): ContractSeedIssue? {
        require(fixture.family.isP2GFamily)
        val classifiedIssue = classify(fixture)
        if (classifiedIssue?.layer == ContractSeedLayer.SCHEMA) {
            return classifiedIssue
        }

        val productionIssue =
            try {
                productionRoundTrip(fixture)
                null
            } catch (failure: SerializationException) {
                failure.toSemanticIssueOrDecodeMismatch()
            } catch (failure: IllegalArgumentException) {
                failure.toSemanticIssueOrDecodeMismatch()
            }

        return when {
            classifiedIssue == null && productionIssue == null -> null
            classifiedIssue != null &&
                productionIssue != null &&
                classifiedIssue.code == productionIssue.code &&
                classifiedIssue.path == productionIssue.path -> productionIssue
            classifiedIssue == null && productionIssue != null ->
                semanticIssue(
                    code = "production_p2g_decode_rejected_valid_fixture",
                    path = productionIssue.path,
                )
            classifiedIssue != null && productionIssue == null ->
                semanticIssue(
                    code = "production_p2g_decode_accepted_invalid_fixture",
                    path = classifiedIssue.path,
                )
            else ->
                semanticIssue(
                    code = "production_p2g_semantic_issue_mismatch",
                    path = productionIssue?.path.orEmpty(),
                )
        }
    }

    fun productionRoundTrip(fixture: ContractSeedFixture): String {
        require(fixture.family.isP2GFamily)
        return when (fixture.family) {
            ContractSeedFamily.OPERATION_ID -> transcodeOperationId(fixture)
            ContractSeedFamily.OUTPUT ->
                transcode(UniversalAiOutput.serializer(), fixture.json)
            ContractSeedFamily.USAGE ->
                transcode(UniversalAiUsage.serializer(), fixture.json)
            ContractSeedFamily.ERROR ->
                transcode(UniversalAiError.serializer(), fixture.json)
            ContractSeedFamily.RESPONSE ->
                transcode(UniversalAiResponse.serializer(), fixture.json)
            ContractSeedFamily.STREAM_EVENT ->
                UniversalAiStreamEvent.fromJson(fixture.json).toJson()
            ContractSeedFamily.STREAM_SEQUENCE ->
                productionStreamSequenceRoundTrip(fixture.json)
            else -> error("${fixture.family} is not a P2-G fixture family.")
        }
    }

    private fun classify(fixture: ContractSeedFixture): ContractSeedIssue? {
        val element =
            try {
                CanonicalJson.parseToElement(fixture.json)
            } catch (_: SerializationException) {
                return schemaIssue("invalid_json", "", null)
            } catch (_: IllegalArgumentException) {
                return schemaIssue("invalid_json", "", null)
            }

        return when (fixture.family) {
            ContractSeedFamily.OPERATION_ID -> validateOperationId(fixture, element)
            ContractSeedFamily.OUTPUT -> validateOutput(element)
            ContractSeedFamily.USAGE -> validateUsage(element)
            ContractSeedFamily.ERROR -> validateError(element)
            ContractSeedFamily.RESPONSE -> validateResponse(element)
            ContractSeedFamily.STREAM_EVENT -> validateStreamEvent(element)
            ContractSeedFamily.STREAM_SEQUENCE -> validateStreamSequence(element)
            else -> error("${fixture.family} is not a P2-G fixture family.")
        }
    }

    private fun validateOperationId(
        fixture: ContractSeedFixture,
        element: JsonElement,
    ): ContractSeedIssue? {
        val value =
            element.stringOrNull()
                ?: return schemaIssue("invalid_operation_id", "", "type")
        if (value.isEmpty()) {
            return schemaIssue("invalid_operation_id", "", "minLength")
        }
        if (value.jsonSchemaCodePointCount() > MAX_OPERATION_ID_SCHEMA_CHARACTERS) {
            return schemaIssue("invalid_operation_id", "", "maxLength")
        }
        val (code, path) =
            when {
                fixture.id.startsWith("v1-response-id-") -> "invalid_response_id" to "/id"
                fixture.id.startsWith("v1-output-id-") -> "invalid_output_id" to "/id"
                else -> "invalid_request_id" to "/requestId"
            }
        return operationIdentifierSemanticIssue(value, code, path)
    }

    private fun validateOutput(element: JsonElement): ContractSeedIssue? {
        val output =
            element as? JsonObject
                ?: return schemaIssue("invalid_output", "", "type")
        validateRequiredString(output, "id", "invalid_output_id", "/id")?.let { return it }
        val index =
            output.requiredInteger("index")
                ?: return schemaIssue(
                    "invalid_output_index",
                    "/index",
                    if ("index" in output) "type" else "required",
                )
        if (index < 0) {
            return schemaIssue("output_index_out_of_range", "/index", "minimum")
        }
        if (index > MAX_OUTPUT_INDEX) {
            return schemaIssue("output_index_out_of_range", "/index", "maximum")
        }
        val kind =
            output.requiredString("kind")
                ?: return schemaIssue(
                    "invalid_output_kind",
                    "/kind",
                    if ("kind" in output) "type" else "required",
                )
        if (!tokenPattern.matches(kind)) {
            return schemaIssue("invalid_output_kind", "/kind", "pattern")
        }
        output["text"]?.let { text ->
            val value =
                text.stringOrNull()
                    ?: return schemaIssue("invalid_output_text", "/text", "type")
            if (value.jsonSchemaCodePointCount() > MAX_OUTPUT_TEXT_SCHEMA_CHARACTERS) {
                return schemaIssue("invalid_output_text", "/text", "maxLength")
            }
        }

        return when (kind) {
            "text" ->
                when {
                    "json" in output -> semanticIssue("unexpected_output_json", "/json")
                    "text" !in output -> semanticIssue("missing_output_text", "/text")
                    else -> null
                }
            "structured_json" ->
                when {
                    "text" in output -> semanticIssue("unexpected_output_text", "/text")
                    "json" !in output ->
                        semanticIssue("missing_structured_output_json", "/json")
                    else -> null
                }
            else ->
                when {
                    "text" in output -> semanticIssue("unexpected_output_text", "/text")
                    "json" in output -> semanticIssue("unexpected_output_json", "/json")
                    else -> null
                }
        }
    }

    private fun validateUsage(element: JsonElement): ContractSeedIssue? {
        val usage =
            element as? JsonObject
                ?: return schemaIssue("invalid_usage", "", "type")
        listOf("inputTokens", "outputTokens", "totalTokens").forEach { name ->
            usageCountSchemaIssue(usage, name)?.let { return it }
        }
        val input = checkNotNull(usage.getValue("inputTokens").numberTokenOrNull()?.toSafeLong())
        val output = checkNotNull(usage.getValue("outputTokens").numberTokenOrNull()?.toSafeLong())
        val total = checkNotNull(usage.getValue("totalTokens").numberTokenOrNull()?.toSafeLong())

        if (input + output != total) {
            return semanticIssue("usage_total_mismatch", "/totalTokens")
        }
        validateUsageDetails(usage, "inputDetails", input)?.let { return it }
        validateUsageDetails(usage, "outputDetails", output)?.let { return it }
        return null
    }

    private fun validateError(element: JsonElement): ContractSeedIssue? {
        val error =
            element as? JsonObject
                ?: return schemaIssue("invalid_error", "", "type")
        val category =
            error.requiredString("category")
                ?: return schemaIssue(
                    "invalid_error_category",
                    "/category",
                    if ("category" in error) "type" else "required",
                )
        if (!tokenPattern.matches(category)) {
            return schemaIssue("invalid_error_category", "/category", "pattern")
        }
        val code =
            error.requiredString("code")
                ?: return schemaIssue(
                    "invalid_error_code",
                    "/code",
                    if ("code" in error) "type" else "required",
                )
        if (!errorCodePattern.matches(code)) {
            return schemaIssue("invalid_error_code", "/code", "pattern")
        }
        val message =
            error.requiredString("message")
                ?: return schemaIssue(
                    "invalid_error_message",
                    "/message",
                    if ("message" in error) "type" else "required",
                )
        if (message.isEmpty()) {
            return schemaIssue("invalid_error_message", "/message", "minLength")
        }
        if (message.jsonSchemaCodePointCount() > MAX_ERROR_MESSAGE_SCHEMA_CHARACTERS) {
            return schemaIssue("invalid_error_message", "/message", "maxLength")
        }
        return if (
            message.isBlank() ||
            message.any { character ->
                character.code < 0x20 ||
                    character.code in 0x7f..0x9f ||
                    character.code == 0x061c ||
                    character.code in 0x200e..0x200f ||
                    character.code in 0x2028..0x202e ||
                    character.code in 0x2066..0x2069
            } ||
            message.encodeToByteArray().size > MAX_ERROR_MESSAGE_BYTES
        ) {
            semanticIssue("invalid_error_message", "/message")
        } else {
            null
        }
    }

    private fun validateResponse(element: JsonElement): ContractSeedIssue? {
        val response =
            element as? JsonObject
                ?: return schemaIssue("invalid_response", "", "type")
        validateEnvelope(response)?.let { return it }
        validateRequiredString(response, "id", "invalid_response_id", "/id")?.let { return it }
        operationIdentifierSemanticIssue(
            value = checkNotNull(response.requiredString("id")),
            code = "invalid_response_id",
            path = "/id",
        )?.let { return it }
        response["requestId"]?.let {
            validateRequiredString(
                document = response,
                name = "requestId",
                code = "invalid_request_id",
                path = "/requestId",
            )?.let { issue -> return issue }
            operationIdentifierSemanticIssue(
                value = checkNotNull(response.requiredString("requestId")),
                code = "invalid_request_id",
                path = "/requestId",
            )?.let { issue -> return issue }
        }
        if ("target" !in response) {
            return schemaIssue("missing_target", "", "required")
        }
        val outputs =
            response["outputs"]
                ?: return schemaIssue("missing_outputs", "", "required")
        val outputArray =
            outputs as? JsonArray
                ?: return schemaIssue("invalid_outputs", "/outputs", "type")
        if (outputArray.size > MAX_RESPONSE_OUTPUTS) {
            return schemaIssue("response_output_limit_exceeded", "/outputs", "maxItems")
        }
        val completionReason =
            response.requiredString("completionReason")
                ?: return schemaIssue(
                    "invalid_completion_reason",
                    "/completionReason",
                    if ("completionReason" in response) "type" else "required",
                )
        if (!tokenPattern.matches(completionReason)) {
            return schemaIssue("invalid_completion_reason", "/completionReason", "pattern")
        }
        response["usage"]?.let { usage ->
            validateUsage(usage)?.let { issue ->
                return issue.withPrefix("/usage")
            }
        }

        val seenIds = mutableSetOf<String>()
        outputArray.forEachIndexed { position, outputElement ->
            validateOutput(outputElement)?.let { issue ->
                return issue.withPrefix("/outputs/$position")
            }
            val output = outputElement as JsonObject
            val index = checkNotNull(output.requiredInteger("index"))
            if (index != position) {
                return semanticIssue("output_index_mismatch", "/outputs/$position/index")
            }
            val id = checkNotNull(output.requiredString("id"))
            if (!seenIds.add(id)) {
                return semanticIssue("duplicate_output_id", "/outputs/$position/id")
            }
        }
        return null
    }

    private fun validateStreamEvent(element: JsonElement): ContractSeedIssue? {
        val event =
            element as? JsonObject
                ?: return schemaIssue("invalid_stream_event", "", "type")
        validateEnvelope(event)?.let { return it }
        val type =
            event.requiredString("type")
                ?: return schemaIssue(
                    "invalid_stream_event_type",
                    "/type",
                    if ("type" in event) "type" else "required",
                )
        if (!tokenPattern.matches(type)) {
            return schemaIssue("invalid_stream_event_type", "/type", "pattern")
        }
        val terminal =
            (event["terminal"] as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.booleanOrNull
                ?: return schemaIssue(
                    "invalid_stream_terminal",
                    "/terminal",
                    if ("terminal" in event) "type" else "required",
                )
        val sequenceToken =
            event["sequence"]?.numberTokenOrNull()
                ?: return schemaIssue(
                    "invalid_stream_sequence",
                    "/sequence",
                    if ("sequence" in event) "type" else "required",
                )
        if (!JsonNumberSemantics.isMathematicalInteger(sequenceToken)) {
            return schemaIssue("invalid_stream_sequence", "/sequence", "type")
        }
        if (JsonNumberSemantics.compare(sequenceToken, "1")?.let { it < 0 } == true) {
            return schemaIssue("invalid_stream_sequence", "/sequence", "minimum")
        }
        if (
            JsonNumberSemantics.compare(sequenceToken, MAX_JSON_SAFE_INTEGER.toString())
                ?.let { it > 0 } == true
        ) {
            return schemaIssue("invalid_stream_sequence", "/sequence", "maximum")
        }
        if (sequenceToken.toSafeLong() == null) {
            return schemaIssue("invalid_stream_sequence", "/sequence", "type")
        }
        validateRequiredString(
            document = event,
            name = "responseId",
            code = "invalid_response_id",
            path = "/responseId",
        )?.let { return it }
        operationIdentifierSemanticIssue(
            value = checkNotNull(event.requiredString("responseId")),
            code = "invalid_response_id",
            path = "/responseId",
        )?.let { return it }
        event["requestId"]?.let {
            validateRequiredString(event, "requestId", "invalid_request_id", "/requestId")
                ?.let { issue -> return issue }
            operationIdentifierSemanticIssue(
                value = checkNotNull(event.requiredString("requestId")),
                code = "invalid_request_id",
                path = "/requestId",
            )?.let { issue -> return issue }
        }
        event["outputId"]?.let {
            validateRequiredString(event, "outputId", "invalid_output_id", "/outputId")
                ?.let { issue -> return issue }
            operationIdentifierSemanticIssue(
                value = checkNotNull(event.requiredString("outputId")),
                code = "invalid_output_id",
                path = "/outputId",
            )?.let { issue -> return issue }
        }
        val outputIndex =
            event["outputIndex"]?.let {
                val value =
                    event.requiredInteger("outputIndex")
                        ?: return schemaIssue(
                            "invalid_stream_output_index",
                            "/outputIndex",
                            "type",
                        )
                if (value < 0) {
                    return schemaIssue(
                        "invalid_stream_output_index",
                        "/outputIndex",
                        "minimum",
                    )
                }
                if (value > MAX_OUTPUT_INDEX) {
                    return schemaIssue(
                        "invalid_stream_output_index",
                        "/outputIndex",
                        "maximum",
                    )
                }
                value
            }
        val delta =
            event["delta"]?.let { value ->
                val text =
                    value.stringOrNull()
                        ?: return schemaIssue("invalid_stream_delta", "/delta", "type")
                if (text.jsonSchemaCodePointCount() > MAX_OUTPUT_TEXT_SCHEMA_CHARACTERS) {
                    return schemaIssue("invalid_stream_delta", "/delta", "maxLength")
                }
                text
            }
        event["output"]?.let { output ->
            validateOutput(output)?.let { issue -> return issue.withPrefix("/output") }
        }
        event["usage"]?.let { usage ->
            validateUsage(usage)?.let { issue -> return issue.withPrefix("/usage") }
        }
        event["response"]?.let { response ->
            validateResponse(response)?.let { issue -> return issue.withPrefix("/response") }
        }

        if (delta != null && delta.isEmpty()) {
            return semanticIssue("invalid_stream_sequence", "/delta")
        }
        if (delta != null && delta.encodeToByteArray().size > MAX_STREAM_DELTA_BYTES) {
            return semanticIssue("invalid_stream_sequence", "/delta")
        }
        val knownTypes =
            setOf(
                "response.started",
                "output.started",
                "output.delta",
                "output.completed",
                "usage.updated",
                "response.completed",
            )
        if (type !in knownTypes && terminal) {
            return semanticIssue("unsupported_terminal_event", "/terminal")
        }
        if ((type == "response.completed") != terminal) {
            return semanticIssue("invalid_stream_sequence", "/terminal")
        }
        val outputId = event.requiredString("outputId")
        if ((outputId == null) != (outputIndex == null)) {
            return semanticIssue(
                "invalid_stream_sequence",
                if (outputId == null) "/outputId" else "/outputIndex",
            )
        }
        (event["output"] as? JsonObject)?.let { output ->
            if (outputId != output.requiredString("id")) {
                return semanticIssue("invalid_stream_sequence", "/output/id")
            }
            if (outputIndex != output.requiredInteger("index")) {
                return semanticIssue("invalid_stream_sequence", "/output/index")
            }
        }
        (event["response"] as? JsonObject)?.let { response ->
            if (event.requiredString("responseId") != response.requiredString("id")) {
                return semanticIssue("invalid_stream_sequence", "/response/id")
            }
            if (event.requiredString("requestId") != response.requiredString("requestId")) {
                return semanticIssue("invalid_stream_sequence", "/response/requestId")
            }
        }

        return validateStreamEventCoupling(
            type = type,
            event = event,
            outputId = outputId,
            outputIndex = outputIndex,
        )
    }

    private fun validateStreamEventCoupling(
        type: String,
        event: JsonObject,
        outputId: String?,
        outputIndex: Int?,
    ): ContractSeedIssue? {
        val outputScoped = outputId != null && outputIndex != null
        val rules =
            when (type) {
                "response.started" -> StreamFieldRules()
                "output.started" -> StreamFieldRules(outputScopeRequired = true)
                "output.delta" ->
                    StreamFieldRules(
                        outputScopeRequired = true,
                        deltaRequired = true,
                    )
                "output.completed" ->
                    StreamFieldRules(
                        outputScopeRequired = true,
                        outputRequired = true,
                    )
                "usage.updated" -> StreamFieldRules(usageRequired = true)
                "response.completed" -> StreamFieldRules(responseRequired = true)
                else -> return null
            }
        if (outputScoped != rules.outputScopeRequired) {
            return semanticIssue("invalid_stream_sequence", "/outputId")
        }
        listOf(
            "delta" to rules.deltaRequired,
            "output" to rules.outputRequired,
            "usage" to rules.usageRequired,
            "response" to rules.responseRequired,
        ).forEach { (name, required) ->
            if ((name in event) != required) {
                return semanticIssue("invalid_stream_sequence", "/$name")
            }
        }
        return null
    }

    private fun validateStreamSequence(element: JsonElement): ContractSeedIssue? {
        val events =
            element as? JsonArray
                ?: return schemaIssue("invalid_stream_sequence", "", "type")
        if (events.isEmpty()) {
            return schemaIssue("empty_stream_sequence", "", "minItems")
        }
        val eventObjects = mutableListOf<JsonObject>()
        events.forEachIndexed { index, event ->
            validateStreamEvent(event)?.let { issue ->
                return issue.withPrefix("/$index")
            }
            eventObjects += event as JsonObject
        }
        return StreamSequenceOracle(eventObjects).validate()
    }

    private class StreamSequenceOracle(
        private val events: List<JsonObject>,
    ) {
        private var expectedSequence = 1L
        private var terminalAccepted = false
        private var expectedRequestId: String? = null
        private var expectedResponseId: String? = null
        private val outputsById = mutableMapOf<String, OracleOutputState>()
        private val outputIdsByIndex = mutableMapOf<Int, String>()
        private var latestUsage: JsonObject? = null

        fun validate(): ContractSeedIssue? {
            events.forEachIndexed { eventIndex, event ->
                if (terminalAccepted) {
                    return failure(eventIndex, "/terminal")
                }
                val sequence =
                    checkNotNull(event.getValue("sequence").numberTokenOrNull()?.toSafeLong())
                if (sequence != expectedSequence) {
                    return failure(eventIndex, "/sequence")
                }
                val type = checkNotNull(event.requiredString("type"))
                val requestId = event.requiredString("requestId")
                val responseId = checkNotNull(event.requiredString("responseId"))
                if (eventIndex == 0) {
                    if (type != "response.started") {
                        return failure(eventIndex, "/type")
                    }
                    expectedRequestId = requestId
                    expectedResponseId = responseId
                } else {
                    if (type == "response.started") {
                        return failure(eventIndex, "/type")
                    }
                    if (requestId != expectedRequestId) {
                        return failure(eventIndex, "/requestId")
                    }
                    if (responseId != expectedResponseId) {
                        return failure(eventIndex, "/responseId")
                    }
                }

                event.requiredString("outputId")?.let { outputId ->
                    val state = outputsById[outputId]
                    if (state?.completedOutput != null) {
                        return failure(eventIndex, "/outputId")
                    }
                }
                when (type) {
                    "output.started" ->
                        acceptOutputStarted(eventIndex, event)?.let { return it }
                    "output.delta" ->
                        acceptOutputDelta(eventIndex, event)?.let { return it }
                    "output.completed" ->
                        acceptOutputCompleted(eventIndex, event)?.let { return it }
                    "usage.updated" ->
                        acceptUsageUpdated(eventIndex, event)?.let { return it }
                    "response.completed" ->
                        acceptResponseCompleted(eventIndex, event)?.let { return it }
                }

                expectedSequence += 1
                if (event.getValue("terminal").booleanValue()) {
                    terminalAccepted = true
                }
            }

            return if (terminalAccepted) {
                null
            } else {
                ContractSeedIssue(
                    layer = ContractSeedLayer.SEMANTIC,
                    code = "incomplete_stream",
                    path = "/terminal",
                )
            }
        }

        private fun acceptOutputStarted(
            eventIndex: Int,
            event: JsonObject,
        ): ContractSeedIssue? {
            val id = checkNotNull(event.requiredString("outputId"))
            val index = checkNotNull(event.requiredInteger("outputIndex"))
            if (id in outputsById) {
                return failure(eventIndex, "/outputId")
            }
            if (index in outputIdsByIndex) {
                return failure(eventIndex, "/outputIndex")
            }
            outputsById[id] = OracleOutputState(index)
            outputIdsByIndex[index] = id
            return null
        }

        private fun acceptOutputDelta(
            eventIndex: Int,
            event: JsonObject,
        ): ContractSeedIssue? {
            val state = openOutput(eventIndex, event) ?: return lastOpenOutputFailure
            state.deltas.append(checkNotNull(event.requiredString("delta")))
            return null
        }

        private fun acceptOutputCompleted(
            eventIndex: Int,
            event: JsonObject,
        ): ContractSeedIssue? {
            val state = openOutput(eventIndex, event) ?: return lastOpenOutputFailure
            val completed = event.getValue("output") as JsonObject
            val expectedContent =
                completed.requiredString("text")
                    ?: completed["json"]?.toString()
            if (
                expectedContent == null && state.deltas.isNotEmpty() ||
                expectedContent != null &&
                    state.deltas.isNotEmpty() &&
                    state.deltas.toString() != expectedContent
            ) {
                return failure(eventIndex, "/output")
            }
            state.completedOutput = completed
            return null
        }

        private fun acceptUsageUpdated(
            eventIndex: Int,
            event: JsonObject,
        ): ContractSeedIssue? {
            val current = event.getValue("usage") as JsonObject
            latestUsage?.let { previous ->
                monotonicUsageIssue(previous, current, "/$eventIndex/usage")?.let { return it }
            }
            latestUsage = current
            return null
        }

        private fun acceptResponseCompleted(
            eventIndex: Int,
            event: JsonObject,
        ): ContractSeedIssue? {
            if (outputsById.values.any { state -> state.completedOutput == null }) {
                return failure(eventIndex, "/response/outputs")
            }
            val response = event.getValue("response") as JsonObject
            val completedOutputs =
                outputsById.entries
                    .sortedBy { (_, state) -> state.index }
                    .map { (_, state) -> checkNotNull(state.completedOutput) }
            val finalOutputs = response.getValue("outputs") as JsonArray
            if (
                finalOutputs.size != completedOutputs.size ||
                finalOutputs.zip(completedOutputs).any { (final, completed) ->
                    !final.semanticEquals(completed)
                }
            ) {
                return failure(eventIndex, "/response/outputs")
            }
            latestUsage?.let { previous ->
                val finalUsage =
                    response["usage"] as? JsonObject
                        ?: return failure(eventIndex, "/response/usage")
                monotonicUsageIssue(
                    previous = previous,
                    current = finalUsage,
                    path = "/$eventIndex/response/usage",
                )?.let { return it }
            }
            return null
        }

        private var lastOpenOutputFailure: ContractSeedIssue? = null

        private fun openOutput(
            eventIndex: Int,
            event: JsonObject,
        ): OracleOutputState? {
            val id = checkNotNull(event.requiredString("outputId"))
            val index = checkNotNull(event.requiredInteger("outputIndex"))
            val state = outputsById[id]
            if (state == null) {
                lastOpenOutputFailure = failure(eventIndex, "/outputId")
                return null
            }
            if (state.index != index || outputIdsByIndex[index] != id) {
                lastOpenOutputFailure = failure(eventIndex, "/outputIndex")
                return null
            }
            if (state.completedOutput != null) {
                lastOpenOutputFailure = failure(eventIndex, "/outputId")
                return null
            }
            lastOpenOutputFailure = null
            return state
        }

        private fun monotonicUsageIssue(
            previous: JsonObject,
            current: JsonObject,
            path: String,
        ): ContractSeedIssue? {
            listOf("inputTokens", "outputTokens", "totalTokens").forEach { name ->
                val previousValue =
                    checkNotNull(previous.getValue(name).numberTokenOrNull()?.toSafeLong())
                val currentValue =
                    checkNotNull(current.getValue(name).numberTokenOrNull()?.toSafeLong())
                if (currentValue < previousValue) {
                    return ContractSeedIssue(
                        layer = ContractSeedLayer.SEMANTIC,
                        code = "invalid_stream_sequence",
                        path = "$path/$name",
                    )
                }
            }
            listOf("inputDetails", "outputDetails").forEach { detailName ->
                val previousDetails = previous[detailName] as? JsonObject ?: JsonObject(emptyMap())
                val currentDetails = current[detailName] as? JsonObject ?: JsonObject(emptyMap())
                previousDetails.forEach { (name, previousElement) ->
                    val previousValue =
                        checkNotNull(previousElement.numberTokenOrNull()?.toSafeLong())
                    val currentValue =
                        currentDetails[name]?.numberTokenOrNull()?.toSafeLong()
                    if (currentValue == null || currentValue < previousValue) {
                        return ContractSeedIssue(
                            layer = ContractSeedLayer.SEMANTIC,
                            code = "invalid_stream_sequence",
                            path = "$path/$detailName/${name.escapePointerToken()}",
                        )
                    }
                }
            }
            return null
        }

        private fun failure(
            eventIndex: Int,
            path: String,
        ) = ContractSeedIssue(
            layer = ContractSeedLayer.SEMANTIC,
            code = "invalid_stream_sequence",
            path = "/$eventIndex$path",
        )

        private class OracleOutputState(
            val index: Int,
        ) {
            val deltas = StringBuilder()
            var completedOutput: JsonObject? = null
        }
    }

    private data class StreamFieldRules(
        val outputScopeRequired: Boolean = false,
        val deltaRequired: Boolean = false,
        val outputRequired: Boolean = false,
        val usageRequired: Boolean = false,
        val responseRequired: Boolean = false,
    )

    private fun validateEnvelope(document: JsonObject): ContractSeedIssue? {
        val version =
            document["contractVersion"]
                ?: return schemaIssue("missing_contract_version", "", "required")
        return if (
            version !is JsonPrimitive ||
            !version.isString ||
            version.content != ContractSeedFixtures.CONTRACT_VERSION
        ) {
            schemaIssue(
                code =
                    if (version is JsonPrimitive && version.isString) {
                        "unsupported_contract_version"
                    } else {
                        "invalid_contract_version"
                    },
                path = "/contractVersion",
                keyword = "const",
            )
        } else {
            null
        }
    }

    private fun validateRequiredString(
        document: JsonObject,
        name: String,
        code: String,
        path: String,
    ): ContractSeedIssue? {
        val value =
            document.requiredString(name)
                ?: return schemaIssue(
                    code,
                    path,
                    if (name in document) "type" else "required",
                )
        return when {
            value.isEmpty() -> schemaIssue(code, path, "minLength")
            value.jsonSchemaCodePointCount() > MAX_OPERATION_ID_SCHEMA_CHARACTERS ->
                schemaIssue(code, path, "maxLength")
            else -> null
        }
    }

    private fun usageCountSchemaIssue(
        document: JsonObject,
        name: String,
    ): ContractSeedIssue? {
        val path = "/$name"
        val token =
            document[name]?.numberTokenOrNull()
                ?: return schemaIssue(
                    "invalid_usage_token_count",
                    path,
                    if (name in document) "type" else "required",
                )
        if (!JsonNumberSemantics.isMathematicalInteger(token)) {
            return schemaIssue("invalid_usage_token_count", path, "type")
        }
        if (JsonNumberSemantics.compare(token, "0")?.let { it < 0 } == true) {
            return schemaIssue("usage_token_count_out_of_range", path, "minimum")
        }
        if (
            JsonNumberSemantics.compare(token, MAX_JSON_SAFE_INTEGER.toString())
                ?.let { it > 0 } == true
        ) {
            return schemaIssue("usage_token_count_out_of_range", path, "maximum")
        }
        return if (token.toSafeLong() == null) {
            schemaIssue("invalid_usage_token_count", path, "type")
        } else {
            null
        }
    }

    private fun validateUsageDetails(
        document: JsonObject,
        name: String,
        aggregate: Long,
    ): ContractSeedIssue? {
        val element = document[name] ?: return null
        val details =
            element as? JsonObject
                ?: return schemaIssue("invalid_usage_details", "/$name", "type")
        if (details.size > MAX_USAGE_DETAILS) {
            return schemaIssue("usage_detail_limit_exceeded", "/$name", "maxProperties")
        }
        var sum = 0L
        details.forEach { (key, value) ->
            if (!tokenPattern.matches(key)) {
                return schemaIssue(
                    "invalid_usage_detail_key",
                    "/$name",
                    "propertyNames",
                )
            }
            val detailPath = "/$name/${key.escapePointerToken()}"
            val token =
                value.numberTokenOrNull()
                    ?: return schemaIssue(
                        "invalid_usage_token_count",
                        detailPath,
                        "type",
                    )
            if (!JsonNumberSemantics.isMathematicalInteger(token)) {
                return schemaIssue(
                    "invalid_usage_token_count",
                    detailPath,
                    "type",
                )
            }
            if (JsonNumberSemantics.compare(token, "0")?.let { it < 0 } == true) {
                return schemaIssue(
                    "usage_token_count_out_of_range",
                    detailPath,
                    "minimum",
                )
            }
            if (
                JsonNumberSemantics.compare(token, MAX_JSON_SAFE_INTEGER.toString())
                    ?.let { it > 0 } == true
            ) {
                return schemaIssue(
                    "usage_token_count_out_of_range",
                    detailPath,
                    "maximum",
                )
            }
            val detail = token.toSafeLong()
                ?: return schemaIssue(
                    "invalid_usage_token_count",
                    detailPath,
                    "type",
                )
            if (sum > aggregate || detail > aggregate - sum) {
                return semanticIssue("usage_details_exceed_aggregate", "/$name")
            }
            sum += detail
        }
        return null
    }

    private fun productionStreamSequenceRoundTrip(rawJson: String): String {
        val elements =
            CanonicalJson.parseToElement(rawJson) as? JsonArray
                ?: throw SerializationException("Expected an array for a stream sequence.")
        val validator = UniversalAiStreamSequenceValidator()
        val events =
            elements.mapIndexed { index, element ->
                val event =
                    withSemanticPathPrefix("/$index") {
                        CanonicalJson.format.decodeFromJsonElement(
                            UniversalAiStreamEvent.serializer(),
                            element,
                        )
                    }
                withSemanticPathPrefix("/$index") {
                    validator.accept(event)
                }
                event
            }
        validator.finish()
        return CanonicalJson.encode(
            ListSerializer(UniversalAiStreamEvent.serializer()),
            events,
        )
    }

    private inline fun <T> withSemanticPathPrefix(
        prefix: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (failure: SerializationException) {
            val issue = failure.contractSemanticExceptionOrNull() ?: throw failure
            val contextual = issue.withPathPrefix(prefix)
            throw SerializationException(
                message = contextual.message ?: contextual.code,
                cause = contextual,
            )
        } catch (failure: IllegalArgumentException) {
            val issue = failure.contractSemanticExceptionOrNull() ?: throw failure
            val contextual = issue.withPathPrefix(prefix)
            throw SerializationException(
                message = contextual.message ?: contextual.code,
                cause = contextual,
            )
        }

    private fun transcodeOperationId(fixture: ContractSeedFixture): String =
        when {
            fixture.id.startsWith("v1-response-id-") ->
                transcode(ResponseId.serializer(), fixture.json)
            fixture.id.startsWith("v1-output-id-") ->
                transcode(OutputId.serializer(), fixture.json)
            fixture.id.startsWith("v1-request-id-") ->
                transcode(RequestId.serializer(), fixture.json)
            else -> {
                val request = transcode(RequestId.serializer(), fixture.json)
                check(request == transcode(ResponseId.serializer(), fixture.json))
                check(request == transcode(OutputId.serializer(), fixture.json))
                request
            }
        }

    private fun operationIdentifierSemanticIssue(
        value: String,
        code: String,
        path: String,
    ): ContractSeedIssue? =
        if (
            value.any { character ->
                character.isWhitespace() || character.code < 0x20 || character.code == 0x7f
            } ||
            value.encodeToByteArray().size > MAX_OPERATION_ID_BYTES
        ) {
            semanticIssue(code, path)
        } else {
            null
        }

    private fun <T> transcode(
        serializer: KSerializer<T>,
        rawJson: String,
    ): String {
        val decoded = CanonicalJson.decode(serializer, rawJson)
        return CanonicalJson.encode(serializer, decoded)
    }

    private fun Throwable.toSemanticIssueOrDecodeMismatch(): ContractSeedIssue {
        val issue = contractSemanticExceptionOrNull()
        return if (issue == null) {
            semanticIssue(
                code = "production_p2g_decode_failed_without_semantic_issue",
                path = "",
            )
        } else {
            semanticIssue(issue.code, issue.path)
        }
    }

    private fun JsonObject.requiredString(name: String): String? =
        this[name]?.stringOrNull()

    private fun JsonObject.requiredInteger(name: String): Int? {
        val token = this[name]?.numberTokenOrNull() ?: return null
        if (!JsonNumberSemantics.isMathematicalInteger(token)) {
            return null
        }
        return JsonNumberSemantics.toExactIntOrNull(token)
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

    private fun JsonElement.booleanValue(): Boolean =
        checkNotNull((this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull)

    private fun JsonElement.numberTokenOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive === JsonNull || primitive.isString || primitive.booleanOrNull != null) {
            return null
        }
        return primitive.content.takeIf(JsonNumberSemantics::isNumber)
    }

    private fun String.toSafeLong(): Long? {
        val normalized = JsonNumberSemantics.normalize(this) ?: return null
        if (normalized == "0") {
            return 0L
        }
        val exponentMarker = normalized.lastIndexOf('e')
        if (exponentMarker <= 0 || exponentMarker == normalized.lastIndex) {
            return null
        }
        val digits = normalized.substring(0, exponentMarker)
        if (digits.startsWith('-')) {
            return null
        }
        val exponent = normalized.substring(exponentMarker + 1).toIntOrNull() ?: return null
        if (exponent < 0) {
            return null
        }
        return buildString(digits.length + exponent) {
            append(digits)
            repeat(exponent) {
                append('0')
            }
        }.toLongOrNull()
    }

    private fun ContractSeedIssue.withPrefix(prefix: String): ContractSeedIssue =
        copy(path = if (path.isEmpty()) prefix else "$prefix$path")

    private fun String.escapePointerToken(): String =
        replace("~", "~0").replace("/", "~1")

    private fun schemaIssue(
        code: String,
        path: String,
        keyword: String?,
    ) = ContractSeedIssue(
        layer = ContractSeedLayer.SCHEMA,
        code = code,
        path = path,
        keyword = keyword,
    )

    private fun semanticIssue(
        code: String,
        path: String,
    ) = ContractSeedIssue(
        layer = ContractSeedLayer.SEMANTIC,
        code = code,
        path = path,
    )

    private const val MAX_OPERATION_ID_SCHEMA_CHARACTERS: Int = 256
    private const val MAX_OPERATION_ID_BYTES: Int = 256
    private const val MAX_OUTPUT_INDEX: Int = 127
    private const val MAX_OUTPUT_TEXT_SCHEMA_CHARACTERS: Int = 1_048_576
    private const val MAX_STREAM_DELTA_BYTES: Int = 1_048_576
    private const val MAX_ERROR_MESSAGE_SCHEMA_CHARACTERS: Int = 4_096
    private const val MAX_ERROR_MESSAGE_BYTES: Int = 4_096
    private const val MAX_RESPONSE_OUTPUTS: Int = 128
    private const val MAX_USAGE_DETAILS: Int = 64
    private const val MAX_JSON_SAFE_INTEGER: Long = 9_007_199_254_740_991L
}

internal val ContractSeedFamily.isP2GFamily: Boolean
    get() =
        when (this) {
            ContractSeedFamily.OPERATION_ID,
            ContractSeedFamily.OUTPUT,
            ContractSeedFamily.USAGE,
            ContractSeedFamily.ERROR,
            ContractSeedFamily.RESPONSE,
            ContractSeedFamily.STREAM_EVENT,
            ContractSeedFamily.STREAM_SEQUENCE,
            -> true
            else -> false
        }
