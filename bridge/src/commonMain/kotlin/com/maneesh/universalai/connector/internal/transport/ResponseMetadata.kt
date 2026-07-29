package com.maneesh.universalai.connector.internal.transport

import io.ktor.http.fromHttpToGmtDate
import io.ktor.util.date.GMTDate

/** Provider-neutral response metadata extracted from bounded, allowlisted HTTP fields. */
internal data class ConnectorResponseMetadata(
    val requestId: String?,
    val retryAfterMillis: Long?,
)

/**
 * Extracts response metadata without retaining raw response headers.
 *
 * Request-ID precedence is `x-request-id`, `request-id`, then `x-correlation-id`. Within one
 * header name, the first valid field wins. `Retry-After` uses the first valid field and accepts
 * either non-negative delta-seconds or an HTTP date no more than one day in the future. Its raw
 * ASCII field value is limited to 128 bytes before optional whitespace is removed.
 */
internal object ConnectorResponseMetadataExtractor {
    fun extract(
        headers: List<ConnectorTransportHeader>,
        nowEpochMilliseconds: Long = GMTDate().timestamp,
    ): ConnectorResponseMetadata =
        ConnectorResponseMetadata(
            requestId = extractRequestId(headers),
            retryAfterMillis = extractRetryAfter(headers, nowEpochMilliseconds),
        )

    private fun extractRequestId(headers: List<ConnectorTransportHeader>): String? {
        REQUEST_ID_HEADER_PRECEDENCE.forEach { allowedName ->
            headers.forEach { header ->
                if (header.name.equals(allowedName, ignoreCase = true)) {
                    normalizeRequestId(header.value)?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractRetryAfter(
        headers: List<ConnectorTransportHeader>,
        nowEpochMilliseconds: Long,
    ): Long? {
        headers.forEach { header ->
            if (header.name.equals(RETRY_AFTER_HEADER_NAME, ignoreCase = true)) {
                parseRetryAfter(header.value, nowEpochMilliseconds)?.let { return it }
            }
        }
        return null
    }

    private fun normalizeRequestId(value: String): String? {
        val normalized = value.trim(' ', '\t')
        if (
            normalized.isEmpty() ||
            normalized.length > MAX_REQUEST_ID_BYTES ||
            !normalized.isWellFormedMetadataUnicode() ||
            normalized.encodeToByteArray().size > MAX_REQUEST_ID_BYTES ||
            normalized.any(Char::isUnsafeMetadataCharacter)
        ) {
            return null
        }
        return normalized
    }

    private fun parseRetryAfter(
        value: String,
        nowEpochMilliseconds: Long,
    ): Long? {
        if (value.length > MAX_RETRY_AFTER_VALUE_BYTES) {
            return null
        }
        val normalized = value.trim(' ', '\t')
        if (
            normalized.isEmpty() ||
            normalized.any { character -> character.code !in 0x20..0x7e }
        ) {
            return null
        }
        if (normalized.all { character -> character in '0'..'9' }) {
            val seconds = normalized.toLongOrNull() ?: return null
            if (seconds > MAX_RETRY_AFTER_MILLIS / MILLIS_PER_SECOND) {
                return null
            }
            return seconds * MILLIS_PER_SECOND
        }

        val targetEpochMilliseconds =
            try {
                normalized.fromHttpToGmtDate().timestamp
            } catch (_: Throwable) {
                return null
            }
        if (targetEpochMilliseconds < nowEpochMilliseconds) {
            return null
        }
        val latestAccepted =
            if (nowEpochMilliseconds > Long.MAX_VALUE - MAX_RETRY_AFTER_MILLIS) {
                Long.MAX_VALUE
            } else {
                nowEpochMilliseconds + MAX_RETRY_AFTER_MILLIS
            }
        if (targetEpochMilliseconds > latestAccepted) {
            return null
        }
        return targetEpochMilliseconds - nowEpochMilliseconds
    }
}

private fun Char.isUnsafeMetadataCharacter(): Boolean =
    code <= 0x1f ||
        code == 0x7f ||
        code in 0x80..0x9f ||
        this == '\u2028' ||
        this == '\u2029' ||
        code in 0x202a..0x202e ||
        code in 0x2066..0x2069

private fun String.isWellFormedMetadataUnicode(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            character.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) {
                    return false
                }
                index += 2
            }
            character.isLowSurrogate() -> return false
            else -> index += 1
        }
    }
    return true
}

private const val RETRY_AFTER_HEADER_NAME: String = "retry-after"
private const val MAX_REQUEST_ID_BYTES: Int = 256
private const val MAX_RETRY_AFTER_VALUE_BYTES: Int = 128
private const val MILLIS_PER_SECOND: Long = 1_000
private const val MAX_RETRY_AFTER_MILLIS: Long = 86_400_000

private val REQUEST_ID_HEADER_PRECEDENCE: List<String> =
    listOf(
        "x-request-id",
        "request-id",
        "x-correlation-id",
    )
