package com.myadidi.universalai.connector

import com.myadidi.universalai.connector.contract.ModelId
import com.myadidi.universalai.connector.contract.ProviderId
import com.myadidi.universalai.connector.contract.UniversalAiErrorCategory
import com.myadidi.universalai.connector.contract.UniversalAiException
import com.myadidi.universalai.connector.contract.UniversalAiInputRole
import com.myadidi.universalai.connector.contract.UniversalAiRequest
import com.myadidi.universalai.connector.contract.UniversalAiTarget
import com.myadidi.universalai.connector.contract.UniversalAiTextInput
import com.myadidi.universalai.connector.internal.ConnectorResourceOwnership
import com.myadidi.universalai.connector.internal.DeterministicConnectorEngine
import com.myadidi.universalai.connector.internal.provider.builtInProviderRegistration
import com.myadidi.universalai.connector.internal.transport.ConnectorBaseUrl
import com.myadidi.universalai.connector.internal.transport.ConnectorTransport
import com.myadidi.universalai.connector.internal.transport.ConnectorTransportChunkReader
import com.myadidi.universalai.connector.internal.transport.ConnectorTransportHeader
import com.myadidi.universalai.connector.internal.transport.ConnectorTransportRequest
import com.myadidi.universalai.connector.internal.transport.ConnectorTransportResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenRouterDiagnosticTransportTest {
    @Test
    fun actualFormatterUsesPureStdinMode() {
        val envelope = Json.parseToJsonElement("""{"http_status":429,"headers":[["Retry-After","60"]],"body_base64":"${java.util.Base64.getEncoder().encodeToString(ERROR.encodeToByteArray())}","body_complete":true}""").jsonObject
        val result = formatCapturedOpenRouterResponse(envelope)
        assertTrue(result.toString().contains("\"provider_name\":\"Google\""))
    }

    @Test
    fun bothRealAdaptersCaptureActualErrorHeadersAndMetadataWithoutPrivateValues(): Unit = runBlocking {
        for (provider in listOf("openrouter", "openai-compatible")) {
            val records = mutableListOf<String>()
            val fake = FakeTransport(429, ERROR.encodeToByteArray())
            val configuration = UniversalAiProviderConfiguration(ProviderId.of(provider), "https://openrouter.ai/api/v1") { KEY }
            UniversalAiConnector.createForTesting(
                engineFactory = ::DeterministicConnectorEngine,
                transport = OpenRouterDiagnosticTransport(fake, records::add, nextAttempt = { 7 }),
                ownership = ConnectorResourceOwnership.Owned,
                providerRegistrations = listOf(builtInProviderRegistration(configuration)),
            ).use { client ->
                val failure = assertFailsWith<UniversalAiException> {
                    client.respond(UniversalAiRequest(
                        target = UniversalAiTarget(ProviderId.of(provider), ModelId.of(MODEL)),
                        input = listOf(UniversalAiTextInput(UniversalAiInputRole.User, PROMPT)),
                    ))
                }
                assertEquals(UniversalAiErrorCategory.RateLimit, failure.error.category)
            }
            val record = records.single()
            assertTrue(record.contains("\"http_status\":429"))
            assertTrue(record.contains("\"provider_name\":\"Google\""))
            assertTrue(record.contains("\"seconds\":60"))
            assertTrue(record.contains("\"transport_attempt\":7"))
            assertTrue(record.contains("actual_connector_transport"))
            for (privateValue in listOf(KEY, MODEL, PROMPT, PRIVATE, "Set-Cookie", "Authorization")) assertFalse(record.contains(privateValue))
            assertEquals(1, fake.executions)
            assertEquals(1, fake.closes)
        }
    }

    @Test
    fun successAndCancellationDoNotLogOrReadAhead(): Unit = runBlocking {
        val records = mutableListOf<String>()
        val fake = FakeTransport(200, "fixture text".encodeToByteArray())
        val transport = OpenRouterDiagnosticTransport(fake, records::add, formatter = { error("must not run") })
        val cancellation = CancellationException("fixture cancellation")
        val thrown = assertFailsWith<CancellationException> { transport.execute(request()) { throw cancellation } }
        assertSame(cancellation, thrown)
        assertEquals(0, fake.reads)
        assertEquals("fixture text", transport.execute(request()) { readAll(it).decodeToString() })
        assertTrue(records.isEmpty())
        transport.close()
        assertEquals(1, fake.closes)
    }

    @Test
    fun boundedCapturePreservesEveryConsumerByteAndOriginalFailure(): Unit = runBlocking {
        val bytes = ByteArray(100_000) { 'x'.code.toByte() }
        val fake = FakeTransport(429, bytes)
        val records = mutableListOf<String>()
        val transport = OpenRouterDiagnosticTransport(fake, records::add)
        val original = IllegalStateException("fixture original failure")
        val thrown = assertFailsWith<IllegalStateException> {
            transport.execute(request()) { response ->
                assertEquals(bytes.toList(), readAll(response).toList())
                throw original
            }
        }
        assertSame(original, thrown)
        assertTrue(records.single().contains("oversized_response"))
        assertFalse(records.single().contains("xxxxx"))
        assertEquals(1, fake.executions)
    }

    @Test
    fun stuckFormatterCannotBlockOriginalFailureOrLeakAChild(): Unit = runBlocking {
        for (program in listOf("import time; time.sleep(60)", "import sys,time; sys.stdout.write('x'*100000);sys.stdout.flush();time.sleep(60)")) {
            val records = mutableListOf<String>()
            var child: Process? = null
            val transport = OpenRouterDiagnosticTransport(FakeTransport(429, ByteArray(100_000) { 120 }), records::add, formatter = { envelope ->
                formatCapturedOpenRouterResponse(envelope, processFactory = {
                    ProcessBuilder("python3", "-I", "-c", program).start().also { child = it }
                }, timeoutMillis = 200)
            })
            val original = IllegalStateException("fixture original failure")
            val started = System.nanoTime()
            assertSame(original, assertFailsWith<IllegalStateException> { transport.execute(request()) { readAll(it); throw original } })
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
            assertFalse(child?.isAlive ?: true)
            assertTrue(records.single().contains("connector_diagnostic_unavailable"))
        }
    }

    @Test
    fun formatterFailureStillKeepsActualNumericHeadersAndPreservesFailure(): Unit = runBlocking {
        val records = mutableListOf<String>()
        val transport = OpenRouterDiagnosticTransport(FakeTransport(429, ERROR.encodeToByteArray()), records::add, formatter = { error("fixture unavailable") })
        val original = IllegalStateException("fixture")
        assertSame(original, assertFailsWith<IllegalStateException> { transport.execute(request()) { readAll(it); throw original } })
        val record = records.single()
        assertTrue(record.contains("connector_diagnostic_unavailable"))
        assertTrue(record.contains("\"seconds\":60"))
        assertTrue(record.contains("x-ratelimit-remaining"))
        val brokenSink = OpenRouterDiagnosticTransport(FakeTransport(429, ERROR.encodeToByteArray()), diagnostic = { error("sink unavailable") })
        assertSame(original, assertFailsWith<IllegalStateException> { brokenSink.execute(request()) { readAll(it); throw original } })
    }

    @Test
    fun sensitiveEnumCollisionIsRedactedRatherThanCopied(): Unit = runBlocking {
        val fake = FakeTransport(429, ERROR.encodeToByteArray())
        val records = mutableListOf<String>()
        val transport = OpenRouterDiagnosticTransport(fake, records::add)
        assertFailsWith<IllegalStateException> { transport.execute(request("Google")) { readAll(it); error("fixture") } }
        assertTrue(records.single().contains("sensitive_collision_redacted"))
        assertFalse(records.single().contains("Google"))
    }

    private class FakeTransport(private val status: Int, private val bytes: ByteArray) : ConnectorTransport {
        var executions = 0
        var reads = 0
        var closes = 0
        override suspend fun <Result> execute(request: ConnectorTransportRequest, consumeResponse: suspend (ConnectorTransportResponse) -> Result): Result {
            executions++
            var index = 0
            return consumeResponse(ConnectorTransportResponse(status, listOf(
                ConnectorTransportHeader("Retry-After", "60"),
                ConnectorTransportHeader("X-RateLimit-Remaining", "0"),
                ConnectorTransportHeader("Set-Cookie", PRIVATE),
            ), ConnectorTransportChunkReader {
                reads++
                if (index >= bytes.size) null else bytes.copyOfRange(index, minOf(index + 1024, bytes.size)).also { index += it.size }
            }))
        }
        override fun close() { closes++ }
    }

    private companion object {
        const val KEY = "fixture-private-key-never-retained"
        const val MODEL = "fixture-private/model"
        const val PROMPT = "fixture-private-prompt"
        const val PRIVATE = "fixture-private-upstream-data"
        val ERROR = """{"error":{"code":429,"message":"Provider returned error $KEY $PROMPT","metadata":{"provider_name":"Google","raw":"$MODEL is rate-limited upstream $PRIVATE","request_id":"$PRIVATE"}}}"""
        fun request(key: String = KEY) = ConnectorTransportRequest(
            "POST", ConnectorBaseUrl.parse("https://openrouter.ai/api/v1"), "chat/completions",
            adapterHeaders = listOf(ConnectorTransportHeader("Authorization", "Bearer $key")),
            body = """{"model":"$MODEL","messages":[{"content":"$PROMPT"}]}""".encodeToByteArray(),
        )
        suspend fun readAll(response: ConnectorTransportResponse): ByteArray {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) bytes.write(response.body.readChunk() ?: break)
            return bytes.toByteArray()
        }
    }
}
