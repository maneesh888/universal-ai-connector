package com.myadidi.universalai.connector

import com.myadidi.universalai.connector.contract.UniversalAiError
import com.myadidi.universalai.connector.contract.UniversalAiErrorCategory
import com.myadidi.universalai.connector.contract.UniversalAiErrorCode
import com.myadidi.universalai.connector.contract.UniversalAiException
import com.myadidi.universalai.connector.contract.extension.ExtensionValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenRouterLiveRequestGuardTest {
    @Test
    fun serializesRequestStartsWithoutRealSleeps() = runBlocking {
        var nanos = 0L
        val starts = mutableListOf<Long>()
        val guard = OpenRouterLiveRequestGuard(
            nanoTime = { nanos },
            pause = { nanos += it * 1_000_000 },
        )
        guard.request { starts += nanos }
        nanos += 700_000_000
        guard.request { starts += nanos }
        nanos += 5_000_000_000
        guard.request { starts += nanos }
        assertEquals(listOf(0L, 4_000_000_000L, 9_000_000_000L), starts)
    }

    @Test
    fun rateLimitRetainsOnlySafeTimingAndStopsSubsequentCalls() = runBlocking {
        val diagnostics = mutableListOf<String>()
        val guard = OpenRouterLiveRequestGuard(diagnostic = diagnostics::add)
        val failure = UniversalAiException(
            UniversalAiError(
                category = UniversalAiErrorCategory.RateLimit,
                code = UniversalAiErrorCode.of("provider_rate_limited"),
                message = "sensitive-provider-message",
                metadata = ExtensionValue.objectValue(mapOf(
                    "statusCode" to ExtensionValue.number("429"),
                    "retryAfterMillis" to ExtensionValue.number("60000"),
                    "requestId" to ExtensionValue.string("sensitive-request-id"),
                    "providerBody" to ExtensionValue.string("sensitive-credential-value"),
                )),
            ),
        )
        assertSame(failure, assertFailsWith<UniversalAiException> { guard.request { throw failure } })
        var called = false
        assertFailsWith<IllegalStateException> { guard.request { called = true } }
        assertFalse(called)
        assertEquals(listOf("UAC_OPENROUTER_LIVE_FAILURE rate_limited=true status=429 retry_after_ms=60000"), diagnostics)
    }

    @Test
    fun concurrentCallersShareOneStartCadence() = runBlocking {
        var nanos = 0L
        val starts = mutableListOf<Long>()
        val guard = OpenRouterLiveRequestGuard(nanoTime = { nanos }, pause = {
            yield()
            nanos += it * 1_000_000
        })
        List(4) { async { guard.request { starts += nanos } } }.awaitAll()
        assertEquals(listOf(0L, 4_000_000_000L, 8_000_000_000L, 12_000_000_000L), starts)
    }

    @Test
    fun malformedTimingCannotLeakMetadataOrBlockNonRateFailures() = runBlocking {
        val diagnostics = mutableListOf<String>()
        val guard = OpenRouterLiveRequestGuard(diagnostic = diagnostics::add)
        val failure = UniversalAiException(
            UniversalAiError(
                category = UniversalAiErrorCategory.Validation,
                code = UniversalAiErrorCode.of("provider_invalid_request"),
                message = "private-response-message",
                metadata = ExtensionValue.objectValue(mapOf(
                    "statusCode" to ExtensionValue.string("private-header-value"),
                    "retryAfterMillis" to ExtensionValue.number("-1"),
                )),
            ),
        )
        assertSame(failure, assertFailsWith<UniversalAiException> { guard.observe { throw failure } })
        var called = false
        guard.request { called = true }
        assertTrue(called)
        assertEquals(listOf("UAC_OPENROUTER_LIVE_FAILURE rate_limited=false status=unknown retry_after_ms=unknown"), diagnostics)
    }

    @Test
    fun cancellationWhilePacingReleasesTheSlotWithoutIssuingARequest() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        var nanos = 0L
        var called = false
        val guard = OpenRouterLiveRequestGuard(nanoTime = { nanos }, pause = {
            waiting.complete(Unit)
            awaitCancellation()
        })
        guard.request { }
        val pending = async { guard.request { called = true } }
        waiting.await()
        pending.cancel()
        assertFailsWith<CancellationException> { pending.await() }
        assertFalse(called)
        nanos = 4_000_000_000L
        guard.request { called = true }
        assertTrue(called)
    }
}
