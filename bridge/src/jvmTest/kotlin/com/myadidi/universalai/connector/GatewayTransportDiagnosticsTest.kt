@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package com.myadidi.universalai.connector

import com.myadidi.universalai.connector.internal.transport.ConnectorTransport
import com.myadidi.universalai.connector.internal.transport.ConnectorTransportRequest
import com.myadidi.universalai.connector.internal.transport.ConnectorTransportResponse
import com.myadidi.universalai.connector.contract.ModelId
import com.myadidi.universalai.connector.contract.ProviderId
import com.myadidi.universalai.connector.contract.UniversalAiErrorCategory
import com.myadidi.universalai.connector.contract.UniversalAiException
import com.myadidi.universalai.connector.contract.UniversalAiInputRole
import com.myadidi.universalai.connector.contract.UniversalAiRequest
import com.myadidi.universalai.connector.contract.UniversalAiTarget
import com.myadidi.universalai.connector.contract.UniversalAiTextInput
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.net.SocketException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GatewayTransportDiagnosticsTest {
    @Test
    fun actualPublicClientRecordsEngineCauseBeforeFixedTransportMapping(): Unit = runBlocking {
        val records = mutableListOf<String>()
        val original = IOException("fixture $KEY $MODEL", SocketException("Connection reset by peer $PRIVATE"))
        var requests = 0
        var closes = 0
        val mock = MockEngine { data ->
            requests++
            val callerKey = data.attributes.allKeys.single { it.name == "uac.gateway.diagnostic.caller-job" }
            assertFalse(data.attributes[callerKey] === data.executionContext)
            throw original
        }
        val engine = object : HttpClientEngine by mock {
            override fun close() { closes++; mock.close() }
        }
        GatewayDiagnosticClient(configuration(), engine, records::add, sensitiveValues = { listOf(KEY, MODEL) }).use { client ->
            val failure = assertFailsWith<UniversalAiException> { client.respond(request()) }
            assertEquals(UniversalAiErrorCategory.Transport, failure.error.category)
            assertEquals("transport_failure", failure.error.code.rawValue)
            assertEquals("The HTTP transport failed.", failure.message)
        }
        assertEquals(1, requests)
        assertEquals(1, closes)
        val record = records.single()
        assertTrue(record.contains("engine_before_response"))
        assertTrue(record.contains("\"caller_context_available\":true"))
        assertTrue(record.contains("IOException"))
        assertTrue(record.contains("SocketException"))
        assertTrue(record.contains("connection_reset"))
        for (value in listOf(KEY, MODEL, PRIVATE, "gateway.example.invalid", "Bearer")) assertFalse(record.contains(value))
    }

    @Test
    fun successfulResponseAndHeadersRemainUnchangedAndOwnedEngineCloses(): Unit = runBlocking {
        val records = mutableListOf<String>()
        var requests = 0
        var closes = 0
        val mock = MockEngine { data ->
            requests++
            assertEquals("Bearer $KEY", data.headers[HttpHeaders.Authorization])
            respond("""{"id":"fixture-response","object":"chat.completion","model":"$MODEL","choices":[{"index":0,"message":{"role":"assistant","content":"ready"},"finish_reason":"stop"}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        }
        val engine = object : HttpClientEngine by mock {
            override fun close() { closes++; mock.close() }
        }
        GatewayDiagnosticClient(configuration(), engine, records::add).use { client ->
            assertEquals("ready", client.respond(request()).outputs.single().text)
        }
        assertEquals(1, requests)
        assertEquals(1, closes)
        assertTrue(records.isEmpty())
    }

    @Test
    fun cancellationStaysCancellationWithoutFailureDiagnostics(): Unit = runBlocking {
        val records = mutableListOf<String>()
        val cancellation = CancellationException("fixture caller cancellation")
        val engine = MockEngine { throw cancellation }
        GatewayDiagnosticClient(configuration(), engine, records::add).use { client ->
            assertFailsWith<CancellationException> { client.respond(request()) }
        }
        assertTrue(records.isEmpty())
    }

    @Test
    fun cancelledCallerWithSocketCloseFailureDoesNotLogNetworkFailure(): Unit = runBlocking {
        repeat(25) {
            val records = mutableListOf<String>()
            val started = CompletableDeferred<Unit>()
            val engine = MockEngine {
                started.complete(Unit)
                try { awaitCancellation() } catch (_: CancellationException) { throw IOException("Connection reset during fixture cancellation $PRIVATE") }
            }
            val pending = async {
                GatewayDiagnosticClient(configuration(), engine, records::add).use { it.respond(request()) }
            }
            withTimeout(2_000) { started.await() }
            pending.cancel()
            assertFailsWith<CancellationException> { pending.await() }
            assertTrue(records.isEmpty())
        }
    }

    @Test
    fun causeChainsAreBoundedWithoutCopyingPrivateMessages(): Unit = runBlocking {
        val records = mutableListOf<String>()
        var failure: Throwable = SocketException("Unexpected end of stream $PRIVATE")
        repeat(8) { failure = IOException("$KEY $MODEL", failure) }
        val original = failure
        GatewayDiagnosticClient(configuration(), MockEngine { throw original }, records::add).use { client ->
            assertFailsWith<UniversalAiException> { client.respond(request()) }
        }
        val record = records.single()
        assertTrue(record.contains("\"cause_chain_truncated\":true"))
        assertEquals(4, Regex("IOException").findAll(record).count())
        for (value in listOf(KEY, MODEL, PRIVATE)) assertFalse(record.contains(value))
    }

    @Test
    fun cleanupFailurePreservesOriginalAndNeverExposesRawCloseMessage(): Unit = runBlocking {
        val mock = MockEngine { throw IOException("fixture network failure") }
        val engine = object : HttpClientEngine by mock {
            override fun close() { mock.close(); throw IOException("private close detail $PRIVATE") }
        }
        val failure = assertFailsWith<UniversalAiException> {
            GatewayDiagnosticClient(configuration(), engine, diagnostic = {}).use { it.respond(request()) }
        }
        assertEquals("transport_failure", failure.error.code.rawValue)
        assertEquals("Gateway test resource cleanup failed.", failure.suppressed.single().message)
        assertFalse(failure.stackTraceToString().contains(PRIVATE))
    }

    @Test
    fun diagnosticSinkFailureDoesNotChangeTransportFailure(): Unit = runBlocking {
        val engine = MockEngine { throw IOException("fixture network failure $PRIVATE") }
        GatewayDiagnosticClient(configuration(), engine, diagnostic = { error("fixture sink unavailable") }).use { client ->
            val failure = assertFailsWith<UniversalAiException> { client.respond(request()) }
            assertEquals("transport_failure", failure.error.code.rawValue)
        }
    }

    @Test
    fun clientConstructionFailureSurvivesPrivateEngineCloseFailure() {
        var closes = 0
        val mock = MockEngine { error("must not request") }
        val engine = object : HttpClientEngine by mock {
            override fun close() { closes++; mock.close(); throw IOException("private close $PRIVATE") }
        }
        val unsupported = UniversalAiConnectorConfiguration(listOf(
            UniversalAiProviderConfiguration(ProviderId.of("unsupported-fixture"), "https://gateway.example.invalid/v1") { KEY },
        ))
        val failure = assertFailsWith<UniversalAiException> { GatewayDiagnosticClient(unsupported, engine, diagnostic = {}) }
        assertEquals(1, closes)
        assertEquals("Gateway test resource cleanup failed.", failure.suppressed.single().message)
        assertFalse(failure.stackTraceToString().contains(PRIVATE))
    }

    @Test
    fun transportConstructionFailureIsNotReplacedByPrivateCloseFailure() {
        val mock = MockEngine { error("must not request") }
        val engine = object : HttpClientEngine by mock {
            override fun close() { mock.close(); throw IOException("private close $PRIVATE") }
        }
        val original = IllegalArgumentException("fixture construction failure")
        val failure = assertFailsWith<IllegalArgumentException> {
            createGatewayDiagnosticTransport(sensitiveValues = { emptyList() }, engine = engine, createTransport = { throw original })
        }
        assertSame(original, failure)
        assertFalse(failure.stackTraceToString().contains(PRIVATE))
        assertEquals("Gateway test resource cleanup failed.", failure.suppressed.single().message)
    }

    @Test
    fun transportCloseAttemptsBothResourcesAndReturnsOnlySafeError() {
        var engineCloses = 0
        var transportCloses = 0
        val mock = MockEngine { error("must not request") }
        val engine = object : HttpClientEngine by mock {
            override fun close() { engineCloses++; mock.close(); throw IOException("private engine close $PRIVATE") }
        }
        val transport = createGatewayDiagnosticTransport(sensitiveValues = { emptyList() }, engine = engine, createTransport = {
            object : ConnectorTransport {
                override suspend fun <Result> execute(request: ConnectorTransportRequest, consumeResponse: suspend (ConnectorTransportResponse) -> Result): Result = error("must not request")
                override fun close() { transportCloses++; throw IOException("private transport close $PRIVATE") }
            }
        })
        val failure = assertFailsWith<IllegalStateException> { transport.close() }
        assertEquals("Gateway test resource cleanup failed.", failure.message)
        assertEquals(1, engineCloses)
        assertEquals(1, transportCloses)
        assertFalse(failure.stackTraceToString().contains(PRIVATE))
        transport.close()
        assertEquals(1, engineCloses)
    }

    @Test
    fun knownClassCollisionWithSecretIsRedacted(): Unit = runBlocking {
        val records = mutableListOf<String>()
        val engine = MockEngine { throw IOException("fixture $PRIVATE") }
        GatewayDiagnosticClient(configuration(), engine, records::add, sensitiveValues = { listOf("IOException") }).use { client ->
            assertFailsWith<UniversalAiException> { client.respond(request()) }
        }
        assertTrue(records.single().contains("sensitive_collision_redacted"))
        assertFalse(records.single().contains("IOException"))
    }

    private companion object {
        const val KEY = "fixture-private-gateway-key"
        const val MODEL = "fixture-private-gateway-model"
        const val PRIVATE = "fixture-private-prompt-and-response"
        val PROVIDER = ProviderId.of("openai-compatible")
        fun configuration() = UniversalAiConnectorConfiguration(listOf(
            UniversalAiProviderConfiguration(PROVIDER,"https://gateway.example.invalid/v1") { KEY },
        ))
        fun request() = UniversalAiRequest(
            target = UniversalAiTarget(PROVIDER, ModelId.of(MODEL)),
            input = listOf(UniversalAiTextInput(UniversalAiInputRole.User, PRIVATE)),
        )
    }
}
