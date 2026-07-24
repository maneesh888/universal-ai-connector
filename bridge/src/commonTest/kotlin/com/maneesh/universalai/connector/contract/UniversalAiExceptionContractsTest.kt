package com.maneesh.universalai.connector.contract

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UniversalAiExceptionContractsTest {
    @Test
    fun cancellationIsRethrownWithoutChangingItsIdentity() {
        val cancellation = CancellationException("caller cancelled")

        val delivered =
            assertFailsWith<CancellationException> {
                cancellation.toUniversalAiException()
            }

        assertSame(cancellation, delivered)
    }

    @Test
    fun existingCanonicalExceptionRetainsItsIdentityAndUnknownRawValues() {
        val error =
            UniversalAiError(
                category = UniversalAiErrorCategory.of("future_category"),
                code = UniversalAiErrorCode.of("future_code"),
                message = "A safe future failure.",
            )
        val existing = UniversalAiException(error)

        val delivered = existing.toUniversalAiException()

        assertSame(existing, delivered)
        assertSame(error, delivered.error)
        assertEquals("future_category", delivered.error.category.rawValue)
        assertEquals("future_code", delivered.error.code.rawValue)
        assertEquals("A safe future failure.", delivered.message)
        assertNull(delivered.cause)
    }

    @Test
    fun unexpectedThrowableMapsToFixedSafeCanonicalFailureWithoutLeakage() {
        val sensitiveSourceMessage = "secret-token-value must never escape"
        val source =
            IllegalStateException(
                sensitiveSourceMessage,
                IllegalArgumentException("nested sensitive provider payload"),
            )

        val delivered = source.toUniversalAiException()

        assertEquals(UniversalAiErrorCategory.Internal, delivered.error.category)
        assertEquals(UniversalAiErrorCode.ConnectorFailure, delivered.error.code)
        assertEquals(UNEXPECTED_CONNECTOR_FAILURE_MESSAGE, delivered.error.message)
        assertEquals(UNEXPECTED_CONNECTOR_FAILURE_MESSAGE, delivered.message)
        assertNull(delivered.error.metadata)
        assertTrue(delivered.error.extensions.isEmpty)
        assertNull(delivered.cause)
        assertFalse(delivered.message.orEmpty().contains(sensitiveSourceMessage))
        assertFalse(delivered.toString().contains(sensitiveSourceMessage))
        assertFalse(delivered.toString().contains("nested sensitive provider payload"))
    }
}
