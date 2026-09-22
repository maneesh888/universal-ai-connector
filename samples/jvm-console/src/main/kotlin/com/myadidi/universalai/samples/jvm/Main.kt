package com.myadidi.universalai.samples.jvm

import com.myadidi.universalai.connector.UniversalAiConnector
import com.myadidi.universalai.connector.contract.ModelId
import com.myadidi.universalai.connector.contract.ProviderId
import com.myadidi.universalai.connector.contract.UniversalAiException
import com.myadidi.universalai.connector.contract.UniversalAiInputRole
import com.myadidi.universalai.connector.contract.UniversalAiRequest
import com.myadidi.universalai.connector.contract.UniversalAiResponse
import com.myadidi.universalai.connector.contract.UniversalAiStreamEvent
import com.myadidi.universalai.connector.contract.UniversalAiStreamEventType
import com.myadidi.universalai.connector.contract.UniversalAiTarget
import com.myadidi.universalai.connector.contract.UniversalAiTextInput
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
        try {
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
        } finally {
            connector.close()
        }
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

fun main(args: Array<String>) {
    // No credentials, URLs, or model IDs are accepted as command-line arguments.
    if (args.isEmpty()) {
        runConsoleLifecycle { JvmConsoleSample.execute(::println) }
        return
    }
    val provider = if (args.size == 2 && args[0] == "--live")
        com.myadidi.universalai.samples.host.LiveProvider.entries.firstOrNull { it.id == args[1] }
    else null
    if (provider == null) {
        System.err.println("Usage: run [--live openai|anthropic|openrouter|openai-compatible]. Inputs use process environment or non-echoing credential input.")
        kotlin.system.exitProcess(2)
    }
    val prefix = if (provider.id == "openai-compatible") "GATEWAY" else provider.id.uppercase()
    val password = System.getenv("${prefix}_API_KEY")?.toCharArray()
        ?: System.console()?.readPassword("Credential (session only): ")
    if (password == null) {
        System.err.println("Credential input unavailable. Use process environment or a real terminal.")
        kotlin.system.exitProcess(2)
    }
    val passed = try {
        runConsoleLifecycle {
            runLiveConsole(this, provider, System.getenv("${prefix}_LIVE_BASE_URL").orEmpty(),
                password.concatToString(), System.getenv("${prefix}_LIVE_MODEL").orEmpty(), ::println)
        }
    } catch (_: Exception) {
        System.err.println("Live console failed or cancelled; no response body retained.")
        false
    } finally { password.fill('\u0000') }
    if (!passed) kotlin.system.exitProcess(1)
}
