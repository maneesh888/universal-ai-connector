package com.myadidi.universalai.connector

import com.myadidi.universalai.connector.internal.transport.ConnectorTransport
import com.myadidi.universalai.connector.internal.transport.ConnectorTransportChunkReader
import com.myadidi.universalai.connector.internal.transport.ConnectorTransportHeader
import com.myadidi.universalai.connector.internal.transport.ConnectorTransportRequest
import com.myadidi.universalai.connector.internal.transport.ConnectorTransportResponse
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Test-only bounded capture: no retries, extra requests, raw logging or production API changes. */
internal class OpenRouterDiagnosticTransport(
    private val delegate: ConnectorTransport,
    private val diagnostic: (String) -> Unit = System.err::println,
    private val formatter: (JsonObject) -> JsonObject = { formatCapturedOpenRouterResponse(it) },
    private val nextAttempt: () -> Long = { attempts.incrementAndGet() },
) : ConnectorTransport {
    override suspend fun <Result> execute(
        request: ConnectorTransportRequest,
        consumeResponse: suspend (ConnectorTransportResponse) -> Result,
    ): Result {
        val attempt = nextAttempt()
        return delegate.execute(request) { response ->
            val prefix = ByteArray(MAX_CAPTURE + 1)
            var captured = 0
            var complete = false
            var failed = response.statusCode !in 200..299
            val reader = ConnectorTransportChunkReader {
                val chunk = response.body.readChunk()
                if (chunk == null) complete = true
                else {
                    val count = minOf(chunk.size, prefix.size - captured)
                    chunk.copyInto(prefix, captured, 0, count)
                    captured += count
                }
                chunk
            }
            try {
                consumeResponse(ConnectorTransportResponse(response.statusCode, response.headers, reader))
            } catch (cancellation: CancellationException) {
                failed = false
                throw cancellation
            } catch (failure: Throwable) {
                failed = true
                throw failure
            } finally {
                try {
                    if (failed) {
                        // Some adapters classify status without reading its body. Drain only an
                        // already-failed HTTP response, inside its callback and bounded by time/size.
                        if (response.statusCode !in 200..299 && !complete && captured < prefix.size) {
                            try {
                                withTimeoutOrNull(1_000) {
                                    while (!complete && captured < prefix.size) reader.readChunk() ?: break
                                }
                            } catch (_: CancellationException) {
                                // A reader-local cancellation must not mask the known HTTP failure
                                // or prevent the rate-limit guard from latching.
                                currentCoroutineContext().ensureActive()
                            } catch (_: Exception) { /* Keep original error and available metadata. */ }
                        }
                        val envelope = buildJsonObject {
                            put("http_status", response.statusCode)
                            put("headers", JsonArray(response.headers.filter(::diagnosticHeader).take(64).map {
                                JsonArray(listOf(JsonPrimitive(it.name.lowercase()), JsonPrimitive(it.value)))
                            }))
                            put("body_base64", Base64.getEncoder().encodeToString(prefix.copyOf(captured)))
                            put("body_complete", complete)
                            put("event_stream", response.headers.any {
                                it.name.equals("content-type", true) && it.value.substringBefore(';').trim().equals("text/event-stream", true)
                            })
                        }
                        val formatted = try {
                            formatter(envelope)
                        } catch (_: Exception) {
                            fallbackResponseDiagnostic(response.statusCode, response.headers)
                        }
                        val result = JsonObject(formatted + ("transport_attempt" to JsonPrimitive(attempt)))
                        val text = result.toString()
                        val safe = if (sensitiveInputs(request).any { it.isNotEmpty() && text.contains(it) }) {
                            "{\"outcome\":\"sensitive_collision_redacted\"}"
                        } else text
                        emitSafely(safe)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Diagnostics must never replace the original provider/cancellation failure.
                    emitSafely("{\"outcome\":\"connector_diagnostic_unavailable\"}")
                } finally {
                    prefix.fill(0)
                }
            }
        }
    }

    override fun close() = delegate.close()

    private fun emitSafely(value: String) {
        try { diagnostic("UAC_OPENROUTER_RESPONSE_DIAGNOSTIC $value") } catch (_: Exception) { /* Preserve original failure. */ }
    }

    private companion object {
        const val MAX_CAPTURE = 65_536
        val attempts = AtomicLong()
    }
}

private fun diagnosticHeader(header: ConnectorTransportHeader): Boolean =
    (header.name.lowercase() in DIAGNOSTIC_HEADERS) && header.value.length <= 128 &&
        header.value.all { it.code in 32..126 }

private val DIAGNOSTIC_HEADERS = setOf("retry-after", "ratelimit-limit", "ratelimit-remaining", "ratelimit-reset") +
    listOf("limit", "remaining", "reset", "limit-requests", "remaining-requests", "reset-requests", "limit-tokens", "remaining-tokens", "reset-tokens").map { "x-ratelimit-$it" }


private fun fallbackResponseDiagnostic(status: Int, headers: List<ConnectorTransportHeader>): JsonObject {
    val grouped = headers.filter(::diagnosticHeader).groupBy { it.name.lowercase() }
    fun single(name: String) = grouped[name]?.singleOrNull()?.value
    fun number(value: String?) = value?.takeIf { it.matches(Regex("[0-9]{1,15}")) }?.toLongOrNull()
    val rates = buildJsonObject {
        for (name in DIAGNOSTIC_HEADERS - "retry-after") {
            val value = single(name) ?: continue
            val integer = number(value)
            val duration = Regex("([0-9]{1,5})(s|m|h)").matchEntire(value)
            if (integer != null) put(name, buildJsonObject { put("number", integer) })
            else if (duration != null) {
                val seconds = duration.groupValues[1].toLong() * when (duration.groupValues[2]) { "m" -> 60; "h" -> 3600; else -> 1 }
                if (seconds <= 86400) put(name, buildJsonObject { put("duration_seconds", seconds) })
            }
        }
    }
    return buildJsonObject {
        put("http_status", status)
        put("response_source", "actual_connector_transport")
        put("outcome", "connector_diagnostic_unavailable")
        put("rate_limit_headers", rates)
        val retry = single("retry-after")
        val seconds = number(retry)?.takeIf { it <= 86400 }
        if (seconds != null) put("retry_after", buildJsonObject { put("seconds", seconds) })
        else if (retry != null) {
            try {
                val time = java.time.ZonedDateTime.parse(retry, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                put("retry_after", buildJsonObject { put("at_utc", time.toString()) })
            } catch (_: Exception) { /* No arbitrary header text. */ }
        }
    }
}

private fun sensitiveInputs(request: ConnectorTransportRequest): List<String> = buildList {
    request.headers.filter { it.name.equals("authorization", true) }.forEach {
        add(it.value); add(it.value.removePrefix("Bearer "))
    }
    try {
        val body = Json.parseToJsonElement(request.body?.decodeToString() ?: "{}").jsonObject
        body["model"]?.jsonPrimitive?.contentOrNull?.let(::add)
        body["messages"]?.jsonArray?.forEach { message ->
            message.jsonObject["content"]?.jsonPrimitive?.contentOrNull?.let(::add)
        }
    } catch (_: Exception) {
        // Only known request fields participate in a collision check; none is ever output.
    }
}

internal fun formatCapturedOpenRouterResponse(
    envelope: JsonObject,
    processFactory: () -> Process = ::capturedFormatterProcess,
    timeoutMillis: Long = 5_000,
): JsonObject {
    require(timeoutMillis in 1..5_000)
    val input = envelope.toString().encodeToByteArray()
    check(input.size <= 100_000)
    val process = processFactory()
    val workers = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "uac-diagnostic-io").apply { isDaemon = true }
    }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    fun remaining() = (deadline - System.nanoTime()).coerceAtLeast(1)
    val writer = workers.submit<Unit> { process.outputStream.use { it.write(input) } }
    val reader = workers.submit<ByteArray> { process.inputStream.readNBytes(32_769) }
    try {
        val output = reader.get(remaining(), TimeUnit.NANOSECONDS)
        check(output.size <= 32_768)
        writer.get(remaining(), TimeUnit.NANOSECONDS)
        check(process.waitFor(remaining(), TimeUnit.NANOSECONDS) && process.exitValue() == 0)
        return Json.parseToJsonElement(output.decodeToString()).jsonObject
    } finally {
        // Terminate before closing pipes: a synchronized stream close must not wait on a
        // blocked writer. All child I/O shares the deadline; cleanup waits are bounded too.
        if (process.isAlive) process.destroyForcibly()
        process.waitFor(1, TimeUnit.SECONDS)
        writer.cancel(true)
        reader.cancel(true)
        workers.shutdownNow()
        workers.awaitTermination(1, TimeUnit.SECONDS)
        if (!process.isAlive) {
            process.inputStream.close()
            process.outputStream.close()
            process.errorStream.close()
        }
    }
}

private fun capturedFormatterProcess(): Process {
    val script = checkNotNull(System.getProperty("uac.openrouter.diagnostic.script"))
    return ProcessBuilder("python3", "-I", "-B", script, "--captured-stdin")
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .apply {
            environment().keys.removeIf { name ->
                listOf("OPENAI_", "ANTHROPIC_", "OPENROUTER_", "GATEWAY_", "UAC_", "ORG_GRADLE_PROJECT_").any(name::startsWith)
            }
        }.start()
}
