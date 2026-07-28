package com.maneesh.universalai.connector.internal.transport

import io.ktor.http.URLBuilder
import io.ktor.http.URLParserException
import io.ktor.http.Url

/**
 * Distinct bounded timeout policy for one connector request.
 */
internal data class ConnectorTransportTimeouts(
    val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    init {
        if (connectTimeoutMillis !in 1..MAX_TIMEOUT_MILLIS) {
            throw ConnectorTransportPolicyException(
                ConnectorTransportPolicyViolation.InvalidConnectTimeout,
            )
        }
        if (requestTimeoutMillis !in 1..MAX_TIMEOUT_MILLIS) {
            throw ConnectorTransportPolicyException(
                ConnectorTransportPolicyViolation.InvalidRequestTimeout,
            )
        }
        if (connectTimeoutMillis > requestTimeoutMillis) {
            throw ConnectorTransportPolicyException(
                ConnectorTransportPolicyViolation.InvalidTimeoutOrder,
            )
        }
    }

    internal companion object {
        val Default = ConnectorTransportTimeouts()

        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 10_000
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS: Long = 60_000
        const val MAX_TIMEOUT_MILLIS: Long = 10 * 60 * 1_000
    }
}

/**
 * Normalizes one provider base URL and prepares adapter-relative transport requests.
 */
internal class ConnectorTransportPolicy(
    baseUrl: String,
    private val timeouts: ConnectorTransportTimeouts = ConnectorTransportTimeouts.Default,
) {
    private val normalizedBaseUrl: NormalizedBaseUrl = normalizeBaseUrl(baseUrl)

    internal val normalizedBaseUrlString: String
        get() = normalizedBaseUrl.value

    fun prepareRequest(
        method: String,
        endpoint: String,
        transportHeaders: List<ConnectorTransportHeader> = emptyList(),
        adapterHeaders: List<ConnectorTransportHeader> = emptyList(),
        callerHeaders: List<ConnectorTransportHeader> = emptyList(),
        body: ByteArray? = null,
    ): ConnectorTransportRequest =
        ConnectorTransportRequest(
            method = method,
            url = resolveEndpoint(endpoint),
            headers =
                composeConnectorHeaders(
                    transportHeaders = transportHeaders,
                    adapterHeaders = adapterHeaders,
                    callerHeaders = callerHeaders,
                ),
            body = body,
            timeouts = timeouts,
        )

    internal fun resolveEndpoint(endpoint: String): String {
        validateEndpoint(endpoint)
        val resolved =
            parseUrlOrPolicyFailure(
                value = normalizedBaseUrl.value + endpoint,
                violation = ConnectorTransportPolicyViolation.InvalidEndpoint,
            )
        if (
            resolved.protocol != normalizedBaseUrl.url.protocol ||
            !resolved.host.equals(normalizedBaseUrl.url.host, ignoreCase = true) ||
            resolved.port != normalizedBaseUrl.url.port ||
            resolved.user != null ||
            resolved.password != null ||
            resolved.fragment.isNotEmpty()
        ) {
            throw ConnectorTransportPolicyException(
                ConnectorTransportPolicyViolation.InvalidEndpoint,
            )
        }
        return resolved.toString()
    }
}

internal enum class ConnectorTransportPolicyViolation(
    val safeMessage: String,
) {
    InvalidBaseUrl("The configured base URL is invalid."),
    InvalidEndpoint("The transport endpoint is invalid."),
    InvalidHeaderName("A transport header name is invalid."),
    InvalidHeaderValue("A transport header value is invalid."),
    DuplicateHeader("A transport header source contains a duplicate name."),
    TransportOwnedHeader("A transport-owned header cannot be supplied by this source."),
    AdapterOwnedHeader("An adapter-owned sensitive header cannot be supplied by the caller."),
    TooManyHeaders("The transport request contains too many headers."),
    InvalidConnectTimeout("The connection timeout is outside the supported range."),
    InvalidRequestTimeout("The request timeout is outside the supported range."),
    InvalidTimeoutOrder("The connection timeout cannot exceed the request timeout."),
}

internal class ConnectorTransportPolicyException(
    val violation: ConnectorTransportPolicyViolation,
) : IllegalArgumentException(violation.safeMessage)

/**
 * Composes case-insensitive header sources with deterministic source precedence.
 *
 * Transport-owned names can originate only from the transport. Adapter values replace
 * transport defaults for other names. Caller values replace safe adapter defaults, while
 * adapter-owned credential headers cannot be supplied or replaced by callers.
 */
internal fun composeConnectorHeaders(
    transportHeaders: List<ConnectorTransportHeader>,
    adapterHeaders: List<ConnectorTransportHeader>,
    callerHeaders: List<ConnectorTransportHeader>,
): List<ConnectorTransportHeader> {
    if (
        transportHeaders.size > MAX_HEADERS_PER_SOURCE ||
        adapterHeaders.size > MAX_HEADERS_PER_SOURCE ||
        callerHeaders.size > MAX_HEADERS_PER_SOURCE
    ) {
        throw ConnectorTransportPolicyException(
            ConnectorTransportPolicyViolation.TooManyHeaders,
        )
    }

    val composed = linkedMapOf<String, ConnectorTransportHeader>()
    addHeaderSource(
        destination = composed,
        headers = transportHeaders,
        source = ConnectorHeaderSource.Transport,
    )
    addHeaderSource(
        destination = composed,
        headers = adapterHeaders,
        source = ConnectorHeaderSource.Adapter,
    )
    addHeaderSource(
        destination = composed,
        headers = callerHeaders,
        source = ConnectorHeaderSource.Caller,
    )
    if (composed.size > MAX_HEADERS_PER_SOURCE) {
        throw ConnectorTransportPolicyException(
            ConnectorTransportPolicyViolation.TooManyHeaders,
        )
    }
    return composed.values.toList()
}

internal fun validateConnectorTransportHeaders(headers: List<ConnectorTransportHeader>) {
    if (headers.size > MAX_HEADERS_PER_SOURCE) {
        throw ConnectorTransportPolicyException(
            ConnectorTransportPolicyViolation.TooManyHeaders,
        )
    }
    headers.forEach(::validateHeader)
}

/**
 * Produces a bounded request diagnostic. URLs and sensitive header values are never included.
 */
internal fun ConnectorTransportRequest.toRedactedDiagnostic(): String {
    val safeMethod =
        method
            .filter { character -> character.code in ASCII_VISIBLE_RANGE }
            .take(MAX_DIAGNOSTIC_METHOD_CHARACTERS)
            .ifEmpty { "<invalid>" }
    val renderedHeaders =
        headers
            .take(MAX_DIAGNOSTIC_HEADERS)
            .joinToString(separator = ",") { header ->
                val normalizedName = header.name.lowercase()
                val mustRedact =
                    !HEADER_NAME_PATTERN.matches(header.name) ||
                        isSensitiveHeader(normalizedName)
                val safeName =
                    header.name
                        .filter { character -> character.code in ASCII_VISIBLE_RANGE }
                        .take(MAX_DIAGNOSTIC_HEADER_NAME_CHARACTERS)
                        .ifEmpty { "<invalid>" }
                val safeValue =
                    if (mustRedact) {
                        REDACTED_VALUE
                    } else {
                        header.value
                            .filter(::isSafeDiagnosticCharacter)
                            .take(MAX_DIAGNOSTIC_HEADER_VALUE_CHARACTERS)
                    }
                "$safeName=$safeValue"
            }
    val omittedCount = (headers.size - MAX_DIAGNOSTIC_HEADERS).coerceAtLeast(0)
    val suffix = if (omittedCount == 0) "" else ",…+$omittedCount"
    return "method=$safeMethod url=<redacted> headers=[$renderedHeaders$suffix]"
        .take(MAX_DIAGNOSTIC_CHARACTERS)
}

private data class NormalizedBaseUrl(
    val url: Url,
    val value: String,
)

private enum class ConnectorHeaderSource {
    Transport,
    Adapter,
    Caller,
}

private fun normalizeBaseUrl(value: String): NormalizedBaseUrl {
    if (
        value.isBlank() ||
        value != value.trim() ||
        value.any(::isUnsafeTransportCharacter)
    ) {
        throw ConnectorTransportPolicyException(
            ConnectorTransportPolicyViolation.InvalidBaseUrl,
        )
    }
    val parsed = parseUrlOrPolicyFailure(value)
    if (
        parsed.protocol.name.lowercase() !in SUPPORTED_PROTOCOLS ||
        parsed.host.isBlank() ||
        parsed.user != null ||
        parsed.password != null ||
        parsed.fragment.isNotEmpty() ||
        !parsed.parameters.isEmpty() ||
        parsed.trailingQuery
    ) {
        throw ConnectorTransportPolicyException(
            ConnectorTransportPolicyViolation.InvalidBaseUrl,
        )
    }
    validateEncodedPath(
        encodedPath = parsed.encodedPath,
        violation = ConnectorTransportPolicyViolation.InvalidBaseUrl,
    )
    val canonical =
        URLBuilder(parsed)
            .apply {
                host = parsed.host.lowercase()
            }.build()
    val normalized = canonical.toString().trimEnd('/') + "/"
    return NormalizedBaseUrl(
        url = parseUrlOrPolicyFailure(normalized),
        value = normalized,
    )
}

private fun validateEndpoint(endpoint: String) {
    if (
        endpoint.isBlank() ||
        endpoint != endpoint.trim() ||
        endpoint.startsWith("/") ||
        endpoint.startsWith("\\") ||
        endpoint.startsWith("//") ||
        endpoint.contains('#') ||
        endpoint.any(::isUnsafeTransportCharacter) ||
        SCHEME_PREFIX.matches(endpoint.substringBefore('/'))
    ) {
        throw ConnectorTransportPolicyException(
            ConnectorTransportPolicyViolation.InvalidEndpoint,
        )
    }
    val path = endpoint.substringBefore('?')
    if (path.isEmpty()) {
        throw ConnectorTransportPolicyException(
            ConnectorTransportPolicyViolation.InvalidEndpoint,
        )
    }
    validateEncodedPath(
        encodedPath = path,
        violation = ConnectorTransportPolicyViolation.InvalidEndpoint,
    )
}

private fun validateEncodedPath(
    encodedPath: String,
    violation: ConnectorTransportPolicyViolation,
) {
    val segments = encodedPath.split('/')
    segments.forEachIndexed { index, segment ->
        val isBoundaryEmpty =
            segment.isEmpty() && (index == 0 || index == segments.lastIndex)
        if (
            (!isBoundaryEmpty && segment.isEmpty()) ||
            segment.contains('\\') ||
            segment.contains("%2f", ignoreCase = true) ||
            segment.contains("%5c", ignoreCase = true) ||
            segment.contains("%25", ignoreCase = true) ||
            segment
                .replace("%2e", ".", ignoreCase = true)
                .let { decodedDots -> decodedDots == "." || decodedDots == ".." }
        ) {
            throw ConnectorTransportPolicyException(violation)
        }
    }
}

private fun parseUrlOrPolicyFailure(
    value: String,
    violation: ConnectorTransportPolicyViolation =
        ConnectorTransportPolicyViolation.InvalidBaseUrl,
): Url =
    try {
        Url(value)
    } catch (_: URLParserException) {
        throw ConnectorTransportPolicyException(violation)
    } catch (_: IllegalArgumentException) {
        throw ConnectorTransportPolicyException(violation)
    }

private fun addHeaderSource(
    destination: MutableMap<String, ConnectorTransportHeader>,
    headers: List<ConnectorTransportHeader>,
    source: ConnectorHeaderSource,
) {
    val sourceNames = mutableSetOf<String>()
    headers.forEach { header ->
        validateHeader(header)
        val normalizedName = header.name.lowercase()
        if (!sourceNames.add(normalizedName)) {
            throw ConnectorTransportPolicyException(
                ConnectorTransportPolicyViolation.DuplicateHeader,
            )
        }
        if (source != ConnectorHeaderSource.Transport && normalizedName in TRANSPORT_OWNED_HEADERS) {
            throw ConnectorTransportPolicyException(
                ConnectorTransportPolicyViolation.TransportOwnedHeader,
            )
        }
        if (source == ConnectorHeaderSource.Caller && isSensitiveHeader(normalizedName)) {
            throw ConnectorTransportPolicyException(
                ConnectorTransportPolicyViolation.AdapterOwnedHeader,
            )
        }
        destination[normalizedName] = header
    }
}

private fun validateHeader(header: ConnectorTransportHeader) {
    if (
        header.name.length > MAX_HEADER_NAME_CHARACTERS ||
        !HEADER_NAME_PATTERN.matches(header.name)
    ) {
        throw ConnectorTransportPolicyException(
            ConnectorTransportPolicyViolation.InvalidHeaderName,
        )
    }
    if (
        header.value.length > MAX_HEADER_VALUE_CHARACTERS ||
        header.value != header.value.trim() ||
        header.value.any(::isUnsafeTransportCharacter)
    ) {
        throw ConnectorTransportPolicyException(
            ConnectorTransportPolicyViolation.InvalidHeaderValue,
        )
    }
}

private fun isSensitiveHeader(normalizedName: String): Boolean =
    normalizedName in SENSITIVE_HEADERS ||
        normalizedName.contains("api-key") ||
        normalizedName.contains("credential") ||
        normalizedName.endsWith("-key") ||
        normalizedName.endsWith("-token") ||
        normalizedName.endsWith("-secret")

private fun isUnsafeTransportCharacter(character: Char): Boolean =
    character.code < ASCII_SPACE ||
        character.code == ASCII_DELETE ||
        character == '\u2028' ||
        character == '\u2029' ||
        character.code in BIDI_CONTROL_RANGE

private fun isSafeDiagnosticCharacter(character: Char): Boolean =
    !isUnsafeTransportCharacter(character)

private val SUPPORTED_PROTOCOLS = setOf("http", "https")
private val TRANSPORT_OWNED_HEADERS =
    setOf(
        "connection",
        "content-length",
        "host",
        "transfer-encoding",
        "upgrade",
    )
private val SENSITIVE_HEADERS =
    setOf(
        "authorization",
        "cookie",
        "proxy-authorization",
        "set-cookie",
    )

private val HEADER_NAME_PATTERN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*$")

private const val MAX_HEADERS_PER_SOURCE: Int = 64
private const val MAX_HEADER_NAME_CHARACTERS: Int = 128
private const val MAX_HEADER_VALUE_CHARACTERS: Int = 8 * 1_024
private const val MAX_DIAGNOSTIC_HEADERS: Int = 32
private const val MAX_DIAGNOSTIC_METHOD_CHARACTERS: Int = 16
private const val MAX_DIAGNOSTIC_HEADER_NAME_CHARACTERS: Int = 64
private const val MAX_DIAGNOSTIC_HEADER_VALUE_CHARACTERS: Int = 128
private const val MAX_DIAGNOSTIC_CHARACTERS: Int = 2_048
private const val REDACTED_VALUE: String = "<redacted>"
private const val ASCII_SPACE: Int = 0x20
private const val ASCII_DELETE: Int = 0x7f
private val ASCII_VISIBLE_RANGE = ASCII_SPACE..0x7e
private val BIDI_CONTROL_RANGE = 0x202a..0x202e
