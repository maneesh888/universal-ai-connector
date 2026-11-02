package com.myadidi.universalai.connector

import com.myadidi.universalai.connector.contract.UniversalAiErrorCategory
import com.myadidi.universalai.connector.contract.UniversalAiException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Test-only pacing; production generation requests are never retried here. */
internal class OpenRouterLiveRequestGuard(
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val diagnostic: (String) -> Unit = System.err::println,
) {
    private val mutex = Mutex()
    private var lastStartedAtNanos: Long? = null
    private val rateLimited = AtomicBoolean(false)

    suspend fun awaitSlot() {
        mutex.withLock {
            requireAvailable()
            lastStartedAtNanos?.let { previous ->
                val elapsedMillis = (nanoTime() - previous) / 1_000_000
                val remainingMillis = (4_000 - elapsedMillis).coerceAtLeast(0)
                if (remainingMillis > 0) pause(remainingMillis)
            }
            requireAvailable()
            lastStartedAtNanos = nanoTime()
        }
    }

    suspend fun <T> request(block: suspend () -> T): T {
        awaitSlot()
        return observe(block)
    }

    suspend fun <T> observe(block: suspend () -> T): T =
        try {
            block()
        } catch (failure: UniversalAiException) {
            val error = failure.error
            val limited = error.category == UniversalAiErrorCategory.RateLimit
            if (limited) rateLimited.set(true)
            val status = error.metadata?.number("statusCode")?.toLongOrNull()?.takeIf { it in 100..599 }
            val retry = error.metadata?.number("retryAfterMillis")?.toLongOrNull()?.takeIf { it in 0..86_400_000 }
            // Never emit request IDs, messages, model/endpoint values, headers or provider bodies.
            diagnostic(
                "UAC_OPENROUTER_LIVE_FAILURE rate_limited=$limited " +
                    "status=${status ?: "unknown"} retry_after_ms=${retry ?: "unknown"}",
            )
            throw failure
        }

    private fun requireAvailable() {
        check(!rateLimited.get()) {
            "OpenRouter live suite stopped after rate limiting; no further requests were sent."
        }
    }
}
