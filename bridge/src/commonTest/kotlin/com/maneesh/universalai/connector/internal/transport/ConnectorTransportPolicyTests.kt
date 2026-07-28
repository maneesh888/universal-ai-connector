package com.maneesh.universalai.connector.internal.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectorTransportPolicyTests {
    @Test
    fun normalizesBaseUrlAndPreservesPathPrefixAndPort() {
        val policy = ConnectorTransportPolicy("https://Example.INVALID:8443/api/v2")

        assertEquals(
            "https://example.invalid:8443/api/v2/",
            policy.normalizedBaseUrlString,
        )
        assertEquals(
            "https://example.invalid:8443/api/v2/responses?stream=true",
            policy.resolveEndpoint("responses?stream=true"),
        )
    }

    @Test
    fun acceptsHttpAndRootBasePath() {
        val policy = ConnectorTransportPolicy("http://localhost:8080")

        assertEquals(
            "http://localhost:8080/health",
            policy.resolveEndpoint("health"),
        )
    }

    @Test
    fun rejectsUnsafeBaseUrlsWithoutEchoingInput() {
        val secret = "base-url-secret"
        val invalidUrls =
            listOf(
                "ftp://example.invalid/v1",
                "https://user:$secret@example.invalid/v1",
                "https://example.invalid/v1?token=$secret",
                "https://example.invalid/v1#fragment",
                "https://example.invalid/a/../v1",
                "https://example.invalid/a/%2e%2e/v1",
                "https://example.invalid/a/%2F/v1",
                "https://example.invalid/a/%252e%252e/v1",
                " https://example.invalid/v1",
            )

        invalidUrls.forEach { invalidUrl ->
            val failure =
                assertFailsWith<ConnectorTransportPolicyException> {
                    ConnectorTransportPolicy(invalidUrl)
                }
            assertEquals(
                ConnectorTransportPolicyViolation.InvalidBaseUrl,
                failure.violation,
            )
            assertFalse(failure.message.orEmpty().contains(secret))
            assertFalse(failure.message.orEmpty().contains(invalidUrl))
        }
    }

    @Test
    fun rejectsAuthorityReplacementAndTraversalEndpoints() {
        val policy = ConnectorTransportPolicy("https://example.invalid/base/")
        val invalidEndpoints =
            listOf(
                "/outside",
                "//attacker.invalid/outside",
                "https://attacker.invalid/outside",
                "../outside",
                "%2e%2e/outside",
                "nested/%2E%2E/outside",
                "nested/%2foutside",
                "nested/%252e%252e/outside",
                "nested\\outside",
                "outside#fragment",
            )

        invalidEndpoints.forEach { endpoint ->
            val failure =
                assertFailsWith<ConnectorTransportPolicyException> {
                    policy.resolveEndpoint(endpoint)
                }
            assertEquals(
                ConnectorTransportPolicyViolation.InvalidEndpoint,
                failure.violation,
            )
            assertFalse(failure.message.orEmpty().contains(endpoint))
        }
    }

    @Test
    fun composesHeadersCaseInsensitivelyWithDeterministicPrecedence() {
        val headers =
            composeConnectorHeaders(
                transportHeaders =
                    listOf(
                        ConnectorTransportHeader("Host", "example.invalid"),
                        ConnectorTransportHeader("User-Agent", "transport"),
                        ConnectorTransportHeader("X-Trace", "transport"),
                    ),
                adapterHeaders =
                    listOf(
                        ConnectorTransportHeader("user-agent", "adapter"),
                        ConnectorTransportHeader("x-trace", "adapter"),
                        ConnectorTransportHeader("Authorization", "Bearer adapter-secret"),
                    ),
                callerHeaders =
                    listOf(
                        ConnectorTransportHeader("USER-AGENT", "caller"),
                        ConnectorTransportHeader("X-Trace", "caller"),
                    ),
            )

        assertEquals(
            listOf(
                ConnectorTransportHeader("Host", "example.invalid"),
                ConnectorTransportHeader("USER-AGENT", "caller"),
                ConnectorTransportHeader("X-Trace", "caller"),
                ConnectorTransportHeader("Authorization", "Bearer adapter-secret"),
            ),
            headers,
        )
    }

    @Test
    fun rejectsTransportOwnedAndCallerCredentialHeaders() {
        assertHeaderViolation(
            expected = ConnectorTransportPolicyViolation.TransportOwnedHeader,
        ) {
            composeConnectorHeaders(
                transportHeaders = emptyList(),
                adapterHeaders = listOf(ConnectorTransportHeader("Content-Length", "1")),
                callerHeaders = emptyList(),
            )
        }
        assertHeaderViolation(
            expected = ConnectorTransportPolicyViolation.TransportOwnedHeader,
        ) {
            composeConnectorHeaders(
                transportHeaders = emptyList(),
                adapterHeaders = emptyList(),
                callerHeaders = listOf(ConnectorTransportHeader("host", "attacker.invalid")),
            )
        }
        assertHeaderViolation(
            expected = ConnectorTransportPolicyViolation.AdapterOwnedHeader,
        ) {
            composeConnectorHeaders(
                transportHeaders = emptyList(),
                adapterHeaders = emptyList(),
                callerHeaders = listOf(ConnectorTransportHeader("X-API-Key", "caller-secret")),
            )
        }
        assertHeaderViolation(
            expected = ConnectorTransportPolicyViolation.AdapterOwnedHeader,
        ) {
            composeConnectorHeaders(
                transportHeaders = emptyList(),
                adapterHeaders = emptyList(),
                callerHeaders = listOf(ConnectorTransportHeader("X-Session-Token", "caller-secret")),
            )
        }
    }

    @Test
    fun rejectsCaseInsensitiveDuplicatesAndHeaderInjection() {
        assertHeaderViolation(
            expected = ConnectorTransportPolicyViolation.DuplicateHeader,
        ) {
            composeConnectorHeaders(
                transportHeaders = emptyList(),
                adapterHeaders =
                    listOf(
                        ConnectorTransportHeader("X-Test", "first"),
                        ConnectorTransportHeader("x-test", "second"),
                    ),
                callerHeaders = emptyList(),
            )
        }
        listOf(
            ConnectorTransportHeader("Bad Name", "value"),
            ConnectorTransportHeader("X-Test\r\nInjected", "value"),
            ConnectorTransportHeader("X-Test", "value\r\nInjected: true"),
            ConnectorTransportHeader("X-Test", "\u2028injected"),
            ConnectorTransportHeader("X-Test", " leading-space"),
        ).forEach { invalidHeader ->
            assertFailsWith<ConnectorTransportPolicyException> {
                composeConnectorHeaders(
                    transportHeaders = listOf(invalidHeader),
                    adapterHeaders = emptyList(),
                    callerHeaders = emptyList(),
                )
            }
        }
    }

    @Test
    fun boundsTheFinalComposedHeaderSet() {
        val adapterHeaders =
            List(40) { index ->
                ConnectorTransportHeader("X-Adapter-$index", "value")
            }
        val callerHeaders =
            List(40) { index ->
                ConnectorTransportHeader("X-Caller-$index", "value")
            }

        val failure =
            assertFailsWith<ConnectorTransportPolicyException> {
                composeConnectorHeaders(
                    transportHeaders = emptyList(),
                    adapterHeaders = adapterHeaders,
                    callerHeaders = callerHeaders,
                )
            }

        assertEquals(
            ConnectorTransportPolicyViolation.TooManyHeaders,
            failure.violation,
        )
    }

    @Test
    fun preparesResolvedRequestWithConfiguredTimeouts() {
        val timeouts =
            ConnectorTransportTimeouts(
                connectTimeoutMillis = 250,
                requestTimeoutMillis = 2_000,
            )
        val policy =
            ConnectorTransportPolicy(
                baseUrl = "https://example.invalid/v1",
                timeouts = timeouts,
            )

        val request =
            policy.prepareRequest(
                method = "POST",
                endpoint = "responses",
                adapterHeaders =
                    listOf(
                        ConnectorTransportHeader("Authorization", "Bearer adapter-secret"),
                    ),
                body = "body".encodeToByteArray(),
            )

        assertEquals("https://example.invalid/v1/responses", request.url)
        assertEquals(timeouts, request.timeouts)
    }

    @Test
    fun rejectsUnboundedOrInvertedTimeouts() {
        assertTimeoutViolation(ConnectorTransportPolicyViolation.InvalidConnectTimeout) {
            ConnectorTransportTimeouts(connectTimeoutMillis = 0)
        }
        assertTimeoutViolation(ConnectorTransportPolicyViolation.InvalidRequestTimeout) {
            ConnectorTransportTimeouts(
                requestTimeoutMillis = ConnectorTransportTimeouts.MAX_TIMEOUT_MILLIS + 1,
            )
        }
        assertTimeoutViolation(ConnectorTransportPolicyViolation.InvalidTimeoutOrder) {
            ConnectorTransportTimeouts(
                connectTimeoutMillis = 2_000,
                requestTimeoutMillis = 1_000,
            )
        }
    }

    @Test
    fun redactsSensitiveHeadersAndBoundsDiagnostics() {
        val secrets =
            listOf(
                "authorization-secret",
                "api-key-secret",
                "cookie-secret",
                "proxy-secret",
                "token-secret",
            )
        val headers =
            listOf(
                ConnectorTransportHeader("Authorization", secrets[0]),
                ConnectorTransportHeader("X-API-Key", secrets[1]),
                ConnectorTransportHeader("Cookie", secrets[2]),
                ConnectorTransportHeader("Proxy-Authorization", secrets[3]),
                ConnectorTransportHeader("X-Session-Token", secrets[4]),
                ConnectorTransportHeader(
                    "X-${"a".repeat(70)}-Secret",
                    "long-name-secret",
                ),
                ConnectorTransportHeader(
                    "Authorization\r\nInjected",
                    "invalid-name-secret",
                ),
                ConnectorTransportHeader("X-Safe", "visible"),
            ) +
                List(80) { index ->
                    ConnectorTransportHeader("X-Filler-$index", "v".repeat(400))
                }
        val request =
            ConnectorTransportRequest(
                method = "POST\r\nInjected",
                url = "https://user:url-secret@example.invalid/path?api_key=query-secret",
                headers = headers,
            )

        val diagnostic = request.toRedactedDiagnostic()

        secrets.forEach { secret ->
            assertFalse(diagnostic.contains(secret))
        }
        assertFalse(diagnostic.contains("long-name-secret"))
        assertFalse(diagnostic.contains("invalid-name-secret"))
        assertFalse(diagnostic.contains("url-secret"))
        assertFalse(diagnostic.contains("query-secret"))
        assertFalse(diagnostic.contains("\r"))
        assertFalse(diagnostic.contains("\n"))
        assertTrue(diagnostic.contains("X-Safe=visible"))
        assertTrue(diagnostic.length <= 2_048)
    }

    private fun assertHeaderViolation(
        expected: ConnectorTransportPolicyViolation,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<ConnectorTransportPolicyException>(block = block)
        assertEquals(expected, failure.violation)
        assertFalse(failure.message.orEmpty().contains("secret"))
    }

    private fun assertTimeoutViolation(
        expected: ConnectorTransportPolicyViolation,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<ConnectorTransportPolicyException>(block = block)
        assertEquals(expected, failure.violation)
    }
}
