package com.maneesh.universalai.connector.internal.transport

import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransportPolicyTests {
    @Test
    fun baseUrlNormalizesRootPrefixAndExplicitPort() {
        assertEquals(
            "https://example.invalid/",
            ConnectorBaseUrl.parse("https://example.invalid").value,
        )
        assertEquals(
            "http://example.invalid:8443/api/v1/",
            ConnectorBaseUrl.parse("http://example.invalid:8443/api/v1///").value,
        )
        assertEquals(
            "https://example.invalid/prefix%20value/",
            ConnectorBaseUrl.parse("https://example.invalid/prefix%20value/").value,
        )
    }

    @Test
    fun baseUrlRejectsUnsupportedOrAmbiguousValuesWithCanonicalValidationError() {
        val invalidValues =
            listOf(
                "",
                " https://example.invalid",
                "https://example.invalid ",
                "ftp://example.invalid/api",
                "https://user@example.invalid/api",
                "https://user:password@example.invalid/api",
                "https://example.invalid/api?mode=unsafe",
                "https://example.invalid/api#fragment",
                "https://example.invalid/api/../v1",
                "https://example.invalid/api/%2e%2e/v1",
                "https://example.invalid/api/%252e%252e/v1",
                "https://example.invalid/api%2fv1",
                "https://example.invalid/api//v1",
                "https://example.invalid\\api",
                "https://example.invalid/\u0000",
            )

        invalidValues.forEach { invalidValue ->
            val failure =
                assertFailsWith<UniversalAiException> {
                    ConnectorBaseUrl.parse(invalidValue)
                }
            assertValidationFailure(failure, INVALID_BASE_URL_MESSAGE)
            if (invalidValue.isNotEmpty()) {
                assertFalse(failure.toString().contains(invalidValue))
            }
        }
    }

    @Test
    fun relativeEndpointResolvesUnderBasePrefixAndPreservesQuery() {
        val baseUrl = ConnectorBaseUrl.parse("https://example.invalid:9443/api/v1")

        assertEquals(
            "https://example.invalid:9443/api/v1/responses",
            baseUrl.resolve("responses"),
        )
        assertEquals(
            "https://example.invalid:9443/api/v1/models/list?limit=2&mode=fake",
            baseUrl.resolve("models/list?limit=2&mode=fake"),
        )
    }

    @Test
    fun relativeEndpointRejectsAuthorityReplacementTraversalAndFragments() {
        val baseUrl = ConnectorBaseUrl.parse("https://example.invalid/api/v1")
        val invalidEndpoints =
            listOf(
                "",
                "/responses",
                "//other.invalid/responses",
                "https://other.invalid/responses",
                "../responses",
                "models/../../responses",
                "%2e%2e/responses",
                "%252e%252e/responses",
                "models%2fprivate",
                "models//list",
                "models\\list",
                "models#fragment",
                "models\r\nX-Injected: yes",
            )

        invalidEndpoints.forEach { invalidEndpoint ->
            val failure =
                assertFailsWith<UniversalAiException> {
                    baseUrl.resolve(invalidEndpoint)
                }
            assertValidationFailure(failure, INVALID_RELATIVE_ENDPOINT_MESSAGE)
            if (invalidEndpoint.isNotEmpty()) {
                assertFalse(failure.toString().contains(invalidEndpoint))
            }
        }
    }

    @Test
    fun headersComposeCaseInsensitivelyWithDeterministicPrecedence() {
        val composed =
            ConnectorHeaderPolicy.compose(
                callerHeaders =
                    listOf(
                        ConnectorTransportHeader("X-Shared", "caller"),
                        ConnectorTransportHeader("X-Caller", "one"),
                    ),
                adapterHeaders =
                    listOf(
                        ConnectorTransportHeader("x-shared", "adapter-first"),
                        ConnectorTransportHeader("X-SHARED", "adapter-second"),
                        ConnectorTransportHeader("X-Adapter", "two"),
                    ),
                transportHeaders =
                    listOf(
                        ConnectorTransportHeader("x-caller", "transport"),
                        ConnectorTransportHeader("Host", "example.invalid"),
                    ),
            )

        assertEquals(
            listOf(
                ConnectorTransportHeader("x-shared", "adapter-first"),
                ConnectorTransportHeader("x-shared", "adapter-second"),
                ConnectorTransportHeader("x-adapter", "two"),
                ConnectorTransportHeader("x-caller", "transport"),
                ConnectorTransportHeader("host", "example.invalid"),
            ),
            composed,
        )
    }

    @Test
    fun protectedHeadersAreRejectedCaseInsensitivelyOutsideTransportOwnership() {
        listOf(
            "Host",
            "HOST",
            "content-length",
            "Transfer-Encoding",
            "Connection",
            "Proxy-Connection",
            "TE",
            "Trailer",
            "Upgrade",
            "Expect",
        ).forEach { protectedName ->
            val callerFailure =
                assertFailsWith<UniversalAiException> {
                    ConnectorHeaderPolicy.compose(
                        callerHeaders =
                            listOf(ConnectorTransportHeader(protectedName, "unsafe")),
                    )
                }
            assertValidationFailure(callerFailure, INVALID_HEADERS_MESSAGE)

            val adapterFailure =
                assertFailsWith<UniversalAiException> {
                    ConnectorHeaderPolicy.compose(
                        adapterHeaders =
                            listOf(ConnectorTransportHeader(protectedName, "unsafe")),
                    )
                }
            assertValidationFailure(adapterFailure, INVALID_HEADERS_MESSAGE)
        }
    }

    @Test
    fun headersRejectNamesValuesDuplicatesAndInjectionWithoutEchoingInput() {
        val invalidHeaders =
            listOf(
                ConnectorTransportHeader("Bad Header", "value"),
                ConnectorTransportHeader("Bad:Header", "value"),
                ConnectorTransportHeader("X-Test", "first\r\nX-Injected: second"),
                ConnectorTransportHeader("X-Test", "value\u0000suffix"),
                ConnectorTransportHeader("X-Test", "value\u0009suffix"),
                ConnectorTransportHeader("X-Test", "value\u2028suffix"),
                ConnectorTransportHeader("X-Test", "value\u202Esuffix"),
                ConnectorTransportHeader("X-Test", "x".repeat(8_193)),
            )

        invalidHeaders.forEach { invalidHeader ->
            val failure =
                assertFailsWith<UniversalAiException> {
                    ConnectorHeaderPolicy.compose(adapterHeaders = listOf(invalidHeader))
                }
            assertValidationFailure(failure, INVALID_HEADERS_MESSAGE)
            assertFalse(failure.toString().contains(invalidHeader.value))
        }

        val duplicateTransportFailure =
            assertFailsWith<UniversalAiException> {
                ConnectorHeaderPolicy.compose(
                    transportHeaders =
                        listOf(
                            ConnectorTransportHeader("Host", "one.invalid"),
                            ConnectorTransportHeader("host", "two.invalid"),
                        ),
                )
            }
        assertValidationFailure(duplicateTransportFailure, INVALID_HEADERS_MESSAGE)
    }

    @Test
    fun timeoutConfigurationAcceptsBoundedValuesAndRejectsInvalidValuesCanonically() {
        assertEquals(
            ConnectorTransportTimeouts(
                connectTimeoutMillis = 1,
                requestTimeoutMillis = 86_400_000,
            ),
            ConnectorTransportTimeouts(
                connectTimeoutMillis = 1,
                requestTimeoutMillis = 86_400_000,
            ),
        )

        listOf(
            0L to 1L,
            -1L to 1L,
            1L to 0L,
            1L to -1L,
            86_400_001L to 1L,
            1L to 86_400_001L,
        ).forEach { (connect, request) ->
            val failure =
                assertFailsWith<UniversalAiException> {
                    ConnectorTransportTimeouts(connect, request)
                }
            assertValidationFailure(failure, INVALID_TIMEOUT_CONFIGURATION_MESSAGE)
        }
    }

    @Test
    fun diagnosticsRedactSensitiveValuesFromHeadersAndMessageAndStayBounded() {
        val authorizationValue = "fake-authorization-sensitive-value"
        val apiKeyValue = "fake-api-key-sensitive-value"
        val cookieValue = "fake-cookie-sensitive-value"
        val diagnostic =
            ConnectorDiagnosticRedactor.redact(
                message =
                    "request failed for $authorizationValue / $apiKeyValue / $cookieValue" +
                        "x".repeat(2_000),
                headers =
                    listOf(
                        ConnectorTransportHeader("AUTHORIZATION", authorizationValue),
                        ConnectorTransportHeader("x-Api-Key", apiKeyValue),
                        ConnectorTransportHeader("Cookie", cookieValue),
                        ConnectorTransportHeader(
                            "X-Correlation",
                            "prefix-$authorizationValue-suffix",
                        ),
                        ConnectorTransportHeader(
                            "Authorization\rX-Injected",
                            "fake-malformed-sensitive-value",
                        ),
                    ),
            )

        listOf(
            authorizationValue,
            apiKeyValue,
            cookieValue,
            "fake-malformed-sensitive-value",
        ).forEach { sensitiveValue ->
            assertFalse(diagnostic.contains(sensitiveValue))
        }
        assertTrue(diagnostic.contains(ConnectorDiagnosticRedactor.REDACTION_MARKER))
        assertTrue(diagnostic.length <= ConnectorDiagnosticRedactor.MAX_DIAGNOSTIC_CHARACTERS)
        assertFalse(diagnostic.contains('\r'))
        assertFalse(diagnostic.contains('\n'))
    }

    @Test
    fun transportErrorCodesAreKnownCanonicalValues() {
        assertTrue(UniversalAiErrorCode.ConnectionTimeout.isKnown)
        assertTrue(UniversalAiErrorCode.RequestTimeout.isKnown)
        assertTrue(UniversalAiErrorCode.TransportFailure.isKnown)
    }

    private fun assertValidationFailure(
        failure: UniversalAiException,
        message: String,
    ) {
        assertEquals(UniversalAiErrorCategory.Validation, failure.error.category)
        assertEquals(UniversalAiErrorCode.InvalidRequest, failure.error.code)
        assertEquals(message, failure.error.message)
        assertEquals(message, failure.message)
        assertEquals(null, failure.cause)
    }
}
