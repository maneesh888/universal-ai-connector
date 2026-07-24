package com.maneesh.universalai.connector.contract.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonNumberSemanticsTests {
    @Test
    fun exactIntConversionRejectsHugeExponentsWithoutCapacityArithmeticOverflow() {
        listOf(
            "1e2147483647",
            "-1e2147483647",
            "1e2147483648",
            "-1e2147483648",
            "1e-2147483648",
            "-1e-2147483648",
        ).forEach { token ->
            assertNull(
                JsonNumberSemantics.toExactIntOrNull(token),
                token,
            )
        }
    }

    @Test
    fun exactIntConversionRetainsSignedBoundariesAndEquivalentSpellings() {
        assertEquals(Int.MAX_VALUE, JsonNumberSemantics.toExactIntOrNull("2147483647"))
        assertEquals(Int.MIN_VALUE, JsonNumberSemantics.toExactIntOrNull("-2147483648"))
        assertEquals(1_000_000_000, JsonNumberSemantics.toExactIntOrNull("1e9"))
        assertEquals(-1_000_000_000, JsonNumberSemantics.toExactIntOrNull("-1.000e9"))
        assertEquals(0, JsonNumberSemantics.toExactIntOrNull("-0e2147483647"))
    }
}
