package com.maneesh.universalai.samples.jvm

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.UniversalAiConnectorException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

internal object JvmConsoleSample {
    suspend fun execute(writeLine: (String) -> Unit) {
        val connector = UniversalAiConnector()
        writeLine("Version: ${connector.version}")

        val response = connector.respond("hello from JVM")
        writeLine("Response: $response")

        val events = connector.stream("stream").toList()
        writeLine(
            events.joinToString(
                prefix = "Stream: ",
                separator = " | ",
            ) { event -> "${event.sequence}:${event.text}" },
        )

        val forcedFailure =
            try {
                connector.respond(UniversalAiConnector.SIMULATED_ERROR_INPUT)
                error("The deterministic forced error did not occur.")
            } catch (failure: UniversalAiConnectorException) {
                failure
            }
        writeLine(
            "Error: ${forcedFailure.code.stableValue}: ${forcedFailure.message}",
        )

        coroutineScope {
            val cancelledRequest =
                async(start = CoroutineStart.UNDISPATCHED) {
                    connector.respond("cancel this response")
                }
            cancelledRequest.cancelAndJoin()
            check(cancelledRequest.isCancelled)
        }
        writeLine("One-shot cancellation: cancelled")

        val firstEvent = connector.stream("stop").take(1).toList().single()
        writeLine("Stream stopped after event: ${firstEvent.sequence}:${firstEvent.text}")
    }
}

fun main() =
    runBlocking {
        JvmConsoleSample.execute(::println)
    }
