package com.maneesh.universalai.connector.internal.transport

import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import io.ktor.http.encodedPath

/**
 * One validated and normalized HTTP(S) base URL.
 *
 * Normalized values always end in exactly one slash so endpoint resolution cannot discard an
 * optional path prefix.
 */
internal class ConnectorBaseUrl private constructor(
    private val normalizedUrl: Url,
    private val explicitPort: Int?,
) {
    val value: String = normalizedUrl.renderWithExplicitPort(explicitPort)

    fun resolve(relativeEndpoint: String): String {
        validateRelativeEndpoint(relativeEndpoint)
        val resolved =
            try {
                Url(value + relativeEndpoint)
            } catch (_: Throwable) {
                throw invalidRelativeEndpoint()
            }
        if (
            resolved.protocol != normalizedUrl.protocol ||
            resolved.host != normalizedUrl.host ||
            resolved.port != normalizedUrl.port ||
            !resolved.encodedPath.startsWith(normalizedUrl.encodedPath)
        ) {
            throw invalidRelativeEndpoint()
        }
        return resolved.renderWithExplicitPort(explicitPort)
    }

    companion object {
        fun parse(configuredValue: String): ConnectorBaseUrl {
            if (
                configuredValue.isBlank() ||
                configuredValue != configuredValue.trim() ||
                !configuredValue.hasExplicitSupportedScheme() ||
                configuredValue.hasUnsafeTransportCharacter() ||
                '\\' in configuredValue ||
                '#' in configuredValue
            ) {
                throw invalidBaseUrl()
            }
            val parsed =
                try {
                    Url(configuredValue)
                } catch (_: Throwable) {
                    throw invalidBaseUrl()
                }
            if (
                parsed.protocol.name !in SUPPORTED_SCHEMES ||
                parsed.host.isBlank() ||
                parsed.user != null ||
                parsed.password != null ||
                parsed.fragment.isNotEmpty() ||
                !parsed.parameters.isEmpty() ||
                parsed.trailingQuery ||
                configuredValue.encodedBasePath().hasAmbiguousPath() ||
                parsed.encodedPath.hasAmbiguousPath()
            ) {
                throw invalidBaseUrl()
            }

            val builder = URLBuilder(parsed)
            val prefix = parsed.encodedPath.trimEnd('/')
            builder.encodedPath = if (prefix.isEmpty()) "/" else "$prefix/"
            return ConnectorBaseUrl(
                normalizedUrl = builder.build(),
                explicitPort = configuredValue.explicitPortOrNull(),
            )
        }
    }
}

/**
 * Connect and whole-request timeout policy applied to every request on one transport.
 */
internal data class ConnectorTransportTimeouts(
    val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    init {
        if (
            connectTimeoutMillis !in 1..MAX_TIMEOUT_MILLIS ||
            requestTimeoutMillis !in 1..MAX_TIMEOUT_MILLIS
        ) {
            throw invalidTimeoutConfiguration()
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 10_000
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS: Long = 60_000
        const val MAX_TIMEOUT_MILLIS: Long = 86_400_000
    }
}

/**
 * Case-insensitive header composition with caller, adapter, then transport precedence.
 *
 * Adapter values replace caller values with the same normalized name. Transport values replace
 * both. Repeated fields from the winning source retain their order.
 */
internal object ConnectorHeaderPolicy {
    fun compose(
        callerHeaders: List<ConnectorTransportHeader> = emptyList(),
        adapterHeaders: List<ConnectorTransportHeader> = emptyList(),
        transportHeaders: List<ConnectorTransportHeader> = emptyList(),
    ): List<ConnectorTransportHeader> {
        if (callerHeaders.size + adapterHeaders.size + transportHeaders.size > MAX_HEADER_FIELDS) {
            throw invalidHeaders()
        }
        val normalizedCaller = validateAndNormalize(callerHeaders, rejectProtected = true)
        val normalizedAdapter = validateAndNormalize(adapterHeaders, rejectProtected = true)
        val normalizedTransport = validateAndNormalize(transportHeaders, rejectProtected = false)
        if (
            normalizedTransport
                .filter { it.name in PROTECTED_HEADER_NAMES }
                .groupingBy(ConnectorTransportHeader::name)
                .eachCount()
                .any { (_, count) -> count > 1 }
        ) {
            throw invalidHeaders()
        }

        val result = mutableListOf<ConnectorTransportHeader>()
        result.replaceByNormalizedName(normalizedCaller)
        result.replaceByNormalizedName(normalizedAdapter)
        result.replaceByNormalizedName(normalizedTransport)
        return result
    }

    private fun validateAndNormalize(
        headers: List<ConnectorTransportHeader>,
        rejectProtected: Boolean,
    ): List<ConnectorTransportHeader> =
        headers.map { header ->
            val normalizedName = header.name.lowercase()
            if (
                header.name.length !in 1..MAX_HEADER_NAME_CHARACTERS ||
                !HEADER_NAME_PATTERN.matches(header.name) ||
                header.value.encodeToByteArray().size > MAX_HEADER_VALUE_BYTES ||
                header.value.any(Char::isUnsafeHeaderValueCharacter) ||
                (rejectProtected && normalizedName in PROTECTED_HEADER_NAMES)
            ) {
                throw invalidHeaders()
            }
            ConnectorTransportHeader(
                name = normalizedName,
                value = header.value,
            )
        }

    private fun MutableList<ConnectorTransportHeader>.replaceByNormalizedName(
        replacement: List<ConnectorTransportHeader>,
    ) {
        val replacementNames = replacement.mapTo(mutableSetOf(), ConnectorTransportHeader::name)
        removeAll { existing -> existing.name in replacementNames }
        addAll(replacement)
    }
}

/** Bounded diagnostics that never expose values carried by sensitive header names. */
internal object ConnectorDiagnosticRedactor {
    const val REDACTION_MARKER: String = "[REDACTED]"
    const val MAX_DIAGNOSTIC_CHARACTERS: Int = 1_024

    fun redact(
        message: String,
        headers: List<ConnectorTransportHeader>,
    ): String {
        val sensitiveValues =
            headers
                .filter { header -> header.name.isSensitiveHeaderName() }
                .map(ConnectorTransportHeader::value)
                .filter(String::isNotEmpty)
                .distinct()
                .sortedByDescending(String::length)
        var safeMessage = message
        sensitiveValues.forEach { value ->
            safeMessage = safeMessage.replace(value, REDACTION_MARKER)
        }
        safeMessage = safeMessage.sanitizeDiagnosticText()

        val safeHeaders =
            headers.joinToString(
                prefix = "headers=[",
                postfix = "]",
                separator = ", ",
            ) { header ->
                val safeName =
                    if (HEADER_NAME_PATTERN.matches(header.name)) {
                        header.name.lowercase()
                    } else {
                        "[invalid-header-name]"
                    }
                val safeValue =
                    if (header.name.isSensitiveHeaderName()) {
                        REDACTION_MARKER
                    } else {
                        var value = header.value
                        sensitiveValues.forEach { sensitive ->
                            value = value.replace(sensitive, REDACTION_MARKER)
                        }
                        value.sanitizeDiagnosticText()
                    }
                "$safeName=$safeValue"
            }
        return "$safeMessage; $safeHeaders".boundedDiagnostic()
    }
}

internal const val INVALID_BASE_URL_MESSAGE: String = "The configured HTTP base URL is invalid."
internal const val INVALID_RELATIVE_ENDPOINT_MESSAGE: String =
    "The configured HTTP endpoint is invalid."
internal const val INVALID_HEADERS_MESSAGE: String = "The configured HTTP headers are invalid."
internal const val INVALID_TIMEOUT_CONFIGURATION_MESSAGE: String =
    "The configured HTTP timeouts are invalid."
internal const val CONNECTION_TIMEOUT_MESSAGE: String =
    "The HTTP connection exceeded its configured timeout."
internal const val REQUEST_TIMEOUT_MESSAGE: String =
    "The HTTP request exceeded its configured timeout."
internal const val TRANSPORT_FAILURE_MESSAGE: String = "The HTTP transport failed."
internal const val MALFORMED_RESPONSE_STREAM_MESSAGE: String =
    "The HTTP response stream is malformed."

internal fun connectionTimeoutException(): UniversalAiException =
    transportException(
        code = UniversalAiErrorCode.ConnectionTimeout,
        message = CONNECTION_TIMEOUT_MESSAGE,
    )

internal fun requestTimeoutException(): UniversalAiException =
    transportException(
        code = UniversalAiErrorCode.RequestTimeout,
        message = REQUEST_TIMEOUT_MESSAGE,
    )

internal fun transportFailureException(): UniversalAiException =
    transportException(
        code = UniversalAiErrorCode.TransportFailure,
        message = TRANSPORT_FAILURE_MESSAGE,
    )

internal fun malformedResponseStreamException(): UniversalAiException =
    transportException(
        code = UniversalAiErrorCode.TransportFailure,
        message = MALFORMED_RESPONSE_STREAM_MESSAGE,
    )

private fun transportException(
    code: UniversalAiErrorCode,
    message: String,
): UniversalAiException =
    UniversalAiException(
        UniversalAiError(
            category = UniversalAiErrorCategory.Transport,
            code = code,
            message = message,
        ),
    )

private fun invalidBaseUrl(): UniversalAiException = invalidTransportConfiguration(INVALID_BASE_URL_MESSAGE)

private fun invalidRelativeEndpoint(): UniversalAiException =
    invalidTransportConfiguration(INVALID_RELATIVE_ENDPOINT_MESSAGE)

private fun invalidHeaders(): UniversalAiException = invalidTransportConfiguration(INVALID_HEADERS_MESSAGE)

private fun invalidTimeoutConfiguration(): UniversalAiException =
    invalidTransportConfiguration(INVALID_TIMEOUT_CONFIGURATION_MESSAGE)

private fun invalidTransportConfiguration(message: String): UniversalAiException =
    UniversalAiException(
        UniversalAiError(
            category = UniversalAiErrorCategory.Validation,
            code = UniversalAiErrorCode.InvalidRequest,
            message = message,
        ),
    )

private fun validateRelativeEndpoint(relativeEndpoint: String) {
    val path = relativeEndpoint.substringBefore('?')
    if (
        relativeEndpoint.isBlank() ||
        relativeEndpoint != relativeEndpoint.trim() ||
        relativeEndpoint.hasUnsafeTransportCharacter() ||
        '\\' in relativeEndpoint ||
        '#' in relativeEndpoint ||
        path.startsWith('/') ||
        path.contains("://") ||
        path.hasAmbiguousPath()
    ) {
        throw invalidRelativeEndpoint()
    }
}

private fun String.hasAmbiguousPath(): Boolean {
    if (isEmpty()) {
        return false
    }
    val segments = split('/')
    val firstContentIndex = if (startsWith('/')) 1 else 0
    val lastContentIndex = segments.indexOfLast(String::isNotEmpty)
    if (
        lastContentIndex >= firstContentIndex &&
        (firstContentIndex..lastContentIndex).any { index -> segments[index].isEmpty() }
    ) {
        return true
    }
    return segments.any(String::hasUnsafeDecodedPathSegment)
}

private fun String.hasUnsafeDecodedPathSegment(): Boolean {
    var decoded = this
    repeat(MAX_PATH_DECODE_PASSES) {
        if (decoded == "." || decoded == ".." || '/' in decoded || '\\' in decoded) {
            return true
        }
        val next =
            try {
                decoded.decodeURLPart()
            } catch (_: Throwable) {
                return true
            }
        if (next == decoded) {
            return false
        }
        decoded = next
    }
    return true
}

private fun String.hasExplicitSupportedScheme(): Boolean {
    val separatorIndex = indexOf("://")
    if (separatorIndex <= 0) {
        return false
    }
    val authorityStart = separatorIndex + 3
    val authorityEnd =
        indexOfAny(
            chars = charArrayOf('/', '?', '#'),
            startIndex = authorityStart,
        ).takeIf { it >= 0 } ?: length
    if (
        substring(0, separatorIndex).lowercase() !in SUPPORTED_SCHEMES ||
        authorityStart >= authorityEnd
    ) {
        return false
    }
    return substring(authorityStart, authorityEnd).hasValidAuthorityShape()
}

private fun String.hasValidAuthorityShape(): Boolean {
    if (isEmpty() || '@' in this) {
        return false
    }
    if (startsWith('[')) {
        val closingBracket = indexOf(']')
        if (closingBracket <= 1) {
            return false
        }
        val suffix = substring(closingBracket + 1)
        return suffix.isEmpty() ||
            (suffix.startsWith(':') && suffix.length > 1 && suffix.drop(1).all(Char::isDigit))
    }
    if (count { character -> character == ':' } > 1) {
        return false
    }
    val portSeparator = lastIndexOf(':')
    if (portSeparator < 0) {
        return isNotEmpty()
    }
    return substring(0, portSeparator).isNotEmpty() &&
        substring(portSeparator + 1).let { port -> port.isNotEmpty() && port.all(Char::isDigit) }
}

private fun String.encodedBasePath(): String {
    val authorityStart = indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return this
    val pathStart = indexOf('/', startIndex = authorityStart)
    if (pathStart < 0) {
        return ""
    }
    val queryStart = indexOf('?', startIndex = pathStart).takeIf { it >= 0 } ?: length
    val fragmentStart = indexOf('#', startIndex = pathStart).takeIf { it >= 0 } ?: length
    return substring(pathStart, minOf(queryStart, fragmentStart))
}

private fun String.explicitPortOrNull(): Int? {
    val authorityStart = indexOf("://") + 3
    val authorityEnd =
        indexOfAny(
            chars = charArrayOf('/', '?', '#'),
            startIndex = authorityStart,
        ).takeIf { it >= 0 } ?: length
    val authority = substring(authorityStart, authorityEnd)
    val portText =
        if (authority.startsWith('[')) {
            authority.substringAfter(']', missingDelimiterValue = "").removePrefix(":")
                .takeIf(String::isNotEmpty)
        } else {
            authority.substringAfterLast(':', missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() && ':' in authority }
        }
    return portText?.toIntOrNull() ?: if (portText == null) null else throw invalidBaseUrl()
}

private fun Url.renderWithExplicitPort(explicitPort: Int?): String {
    if (explicitPort == null) {
        return toString()
    }
    val renderedHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
    return "${protocol.name}://$renderedHost:$explicitPort$encodedPathAndQuery"
}

private fun String.hasUnsafeTransportCharacter(): Boolean =
    any { character ->
        character.code <= 0x20 ||
            character.code == 0x7f ||
            character.code in 0x80..0x9f ||
            character == '\u2028' ||
            character == '\u2029' ||
            character.code in 0x202a..0x202e ||
            character.code in 0x2066..0x2069
    }

private fun Char.isUnsafeHeaderValueCharacter(): Boolean =
    code <= 0x1f ||
        code == 0x7f ||
        code in 0x80..0x9f ||
        this == '\u2028' ||
        this == '\u2029' ||
        code in 0x202a..0x202e ||
        code in 0x2066..0x2069

private fun String.isSensitiveHeaderName(): Boolean {
    val compactName =
        trim()
            .lowercase()
            .filter(Char::isLetterOrDigit)
    return SENSITIVE_HEADER_NAME_MARKERS.any(compactName::contains)
}

private fun String.sanitizeDiagnosticText(): String =
    map { character ->
        if (character.isUnsafeHeaderValueCharacter()) {
            '\uFFFD'
        } else {
            character
        }
    }.joinToString("")

private fun String.boundedDiagnostic(): String {
    if (length <= ConnectorDiagnosticRedactor.MAX_DIAGNOSTIC_CHARACTERS) {
        return this
    }
    return take(
        ConnectorDiagnosticRedactor.MAX_DIAGNOSTIC_CHARACTERS -
            DIAGNOSTIC_TRUNCATION_MARKER.length,
    ) + DIAGNOSTIC_TRUNCATION_MARKER
}

private const val MAX_HEADER_FIELDS: Int = 128
private const val MAX_HEADER_NAME_CHARACTERS: Int = 256
private const val MAX_HEADER_VALUE_BYTES: Int = 8_192
private const val MAX_PATH_DECODE_PASSES: Int = 8
private const val DIAGNOSTIC_TRUNCATION_MARKER: String = "[TRUNCATED]"

private val SUPPORTED_SCHEMES: Set<String> = setOf("http", "https")
private val HEADER_NAME_PATTERN: Regex = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
private val SENSITIVE_HEADER_NAME_MARKERS: Set<String> =
    setOf(
        "authorization",
        "auth",
        "cookie",
        "apikey",
        "token",
        "secret",
        "credential",
        "key",
    )
private val PROTECTED_HEADER_NAMES: Set<String> =
    setOf(
        "connection",
        "content-length",
        "expect",
        "host",
        "proxy-authorization",
        "proxy-connection",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    )
