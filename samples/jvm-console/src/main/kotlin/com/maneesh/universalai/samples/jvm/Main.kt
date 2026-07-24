package com.maneesh.universalai.samples.jvm

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

internal object JvmConsoleSample {
    suspend fun execute(writeLine: (String) -> Unit) {
        val connector = UniversalAiConnector()
        writeLine("Version: ${connector.version}")

        val response = connector.respond(request("hello from JVM"))
        writeLine("Response: ${response.textOutput()}")

        val events = connector.stream(request("stream")).toList()
        writeLine(
            events.joinToString(
                prefix = "Stream: ",
                separator = " | ",
                transform = { event -> event.render() },
            ),
        )

        val forcedFailure =
            try {
                connector.respond(request(UniversalAiConnector.SIMULATED_ERROR_INPUT))
                error("The deterministic forced error did not occur.")
            } catch (failure: UniversalAiException) {
                failure
            }
        writeLine(
            "Error: ${forcedFailure.error.category.rawValue}/" +
                "${forcedFailure.error.code.rawValue}: ${forcedFailure.error.message}",
        )

        coroutineScope {
            val cancelledRequest =
                async(start = CoroutineStart.UNDISPATCHED) {
                    connector.respond(request("cancel this response"))
                }
            cancelledRequest.cancelAndJoin()
            check(cancelledRequest.isCancelled)
        }
        writeLine("One-shot cancellation: cancelled")

        val firstDelta =
            connector
                .stream(request("stop"))
                .first { event -> event.type == UniversalAiStreamEventType.OutputDelta }
        writeLine("Stream stopped after event: ${firstDelta.render()}")
    }

    private fun request(input: String): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = ProviderId.of("deterministic"),
                    modelId = ModelId.of("echo-v1"),
                ),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = input,
                    ),
                ),
        )

    private fun UniversalAiResponse.textOutput(): String =
        checkNotNull(outputs.single().text) {
            "The deterministic connector must return one text output."
        }

    private fun UniversalAiStreamEvent.render(): String =
        buildString {
            append(sequence)
            append(':')
            append(type.rawValue)
            delta?.let { value ->
                append(":delta=")
                append(value)
            }
            output?.let { completedOutput ->
                append(":output=")
                append(checkNotNull(completedOutput.text))
            }
            response?.let { completedResponse ->
                append(":response=")
                append(completedResponse.textOutput())
            }
            if (terminal) {
                append(":terminal=true")
            }
        }
}

fun main() =
    runBlocking {
        JvmConsoleSample.execute(::println)
    }
