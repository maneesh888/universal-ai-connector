package com.maneesh.universalai.connector.contract.json

import com.maneesh.universalai.connector.contract.extension.Extensions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CanonicalJsonTests {
    @Test
    fun configurationAndContractVersionAreStable() {
        assertEquals("1", CURRENT_CONTRACT_VERSION)
        assertEquals("type", CanonicalJson.format.configuration.classDiscriminator)
        assertEquals(true, CanonicalJson.format.configuration.ignoreUnknownKeys)
        assertEquals(false, CanonicalJson.format.configuration.encodeDefaults)
        assertEquals(true, CanonicalJson.format.configuration.explicitNulls)
        assertEquals(false, CanonicalJson.format.configuration.isLenient)
        assertEquals(false, CanonicalJson.format.configuration.coerceInputValues)
        assertEquals(false, CanonicalJson.format.configuration.allowSpecialFloatingPointValues)
        assertEquals(false, CanonicalJson.format.configuration.allowStructuredMapKeys)
        assertEquals(false, CanonicalJson.format.configuration.prettyPrint)
        assertEquals(false, CanonicalJson.format.configuration.useAlternativeNames)
        assertEquals(false, CanonicalJson.format.configuration.decodeEnumsCaseInsensitive)
        assertEquals(false, CanonicalJson.format.configuration.useArrayPolymorphism)
        assertEquals(false, CanonicalJson.format.configuration.allowTrailingComma)
        assertEquals(false, CanonicalJson.format.configuration.allowComments)
    }

    @Test
    fun preflightAcceptsNestedJsonAndDecodedUnicodeNames() {
        val value =
            CanonicalJson.parseToElement(
                """{"na\u006de":{"nested":[true,false,null,-1.25e+2]}}""",
            )

        assertIs<JsonObject>(value)
        assertEquals(true, "name" in value)
    }

    @Test
    fun preflightRejectsDuplicateNamesAfterEscapeDecoding() {
        val failure =
            assertFailsWith<CanonicalJsonPreflightException> {
                CanonicalJson.parseToElement("""{"name":1,"na\u006de":2}""")
            }

        assertEquals(CanonicalJsonPreflightReason.DUPLICATE_OBJECT_MEMBER, failure.reason)
        assertEquals("/name", failure.path)
    }

    @Test
    fun canonicalDecodeRejectsDuplicateExtensionNamespacesAndNestedMembers() {
        listOf(
            """
            {
              "com.example.provider": {},
              "com.example.provider": {}
            }
            """.trimIndent() to "/com.example.provider",
            """
            {
              "com.example.provider": {
                "enabled": true,
                "\u0065nabled": false
              }
            }
            """.trimIndent() to "/com.example.provider/enabled",
            """
            {
              "com.example.provider": {
                "nested": [
                  {
                    "a/b~c": true,
                    "a\/b\u007ec": false
                  }
                ]
              }
            }
            """.trimIndent() to "/com.example.provider/nested/0/a~1b~0c",
        ).forEach { (input, expectedPath) ->
            val failure =
                assertFailsWith<CanonicalJsonPreflightException> {
                    CanonicalJson.decode(Extensions.serializer(), input)
                }

            assertEquals(
                CanonicalJsonPreflightReason.DUPLICATE_OBJECT_MEMBER,
                failure.reason,
            )
            assertEquals(expectedPath, failure.path)
        }
    }

    @Test
    fun canonicalCodecIgnoresAdditiveMembersWithoutCoercingNulls() {
        val decoded =
            CanonicalJson.decode(
                SeedEnvelope.serializer(),
                """{"contractVersion":"1","futureMember":{"enabled":true}}""",
            )

        assertEquals(SeedEnvelope(contractVersion = "1"), decoded)
        assertFailsWith<SerializationException> {
            CanonicalJson.decode(
                SeedEnvelope.serializer(),
                """{"contractVersion":null}""",
            )
        }
    }

    @Test
    fun canonicalCodecOmitsDefaultValues() {
        assertEquals(
            "{}",
            CanonicalJson.encode(SeedDefaults.serializer(), SeedDefaults()),
        )
    }

    @Test
    fun preflightRejectsMalformedUnicode() {
        val malformed = buildString {
            append('"')
            append('\uD800')
            append('"')
        }

        val failure =
            assertFailsWith<CanonicalJsonPreflightException> {
                CanonicalJson.parseToElement(malformed)
            }

        assertEquals(CanonicalJsonPreflightReason.INVALID_UNICODE, failure.reason)
    }

    @Test
    fun preflightRejectsTrailingCommaAndInvalidNumber() {
        assertEquals(
            CanonicalJsonPreflightReason.INVALID_JSON,
            assertFailsWith<CanonicalJsonPreflightException> {
                CanonicalJson.parseToElement("""{"value":1,}""")
            }.reason,
        )
        assertEquals(
            CanonicalJsonPreflightReason.INVALID_JSON,
            assertFailsWith<CanonicalJsonPreflightException> {
                CanonicalJson.parseToElement("""{"value":01}""")
            }.reason,
        )
    }

    @Test
    fun preflightEnforcesByteDepthAndNodeLimits() {
        assertLimit("""{"value":"too long"}""", CanonicalJsonLimits(8, 8, 8))
        assertLimit("""[[[]]]""", CanonicalJsonLimits(64, 2, 8))
        assertLimit("""[1,2,3]""", CanonicalJsonLimits(64, 8, 3))
    }

    @Test
    fun aggregateDocumentLimitRejectsIndividuallyValidMaximumExtensionStrings() {
        val maximumExtensionString = "x".repeat(16_384)
        val individual = JsonPrimitive(maximumExtensionString)
        assertEquals(
            maximumExtensionString,
            CanonicalJson
                .parseToElement(
                    CanonicalJson.encode(JsonElement.serializer(), individual),
                ).let { element -> assertIs<JsonPrimitive>(element).content },
        )

        val aggregate =
            JsonObject(
                (0 until 64).associate { index ->
                    "value$index" to individual
                },
            )
        val failure =
            assertFailsWith<CanonicalJsonPreflightException> {
                CanonicalJson.encode(JsonElement.serializer(), aggregate)
            }

        assertEquals(
            CanonicalJsonPreflightReason.DOCUMENT_LIMIT_EXCEEDED,
            failure.reason,
        )
        assertTrue(aggregate.values.all { value -> value == individual })
    }

    @Test
    fun exactNumberSemanticsDoNotRoundSchemaBoundaries() {
        assertEquals(0, JsonNumberSemantics.compare("1.0", "1e0"))
        assertEquals(0, JsonNumberSemantics.compare("-0.0", "0"))
        assertTrue(
            checkNotNull(
                JsonNumberSemantics.compare(
                    "1.0000000000000000000000001",
                    "1",
                ),
            ) > 0,
        )
        assertTrue(
            checkNotNull(JsonNumberSemantics.compare("-1e100", "1")) < 0,
        )
        assertEquals(1, JsonNumberSemantics.toExactIntOrNull("1.000e0"))
        assertEquals(null, JsonNumberSemantics.toExactIntOrNull("1e-1"))
    }

    private fun assertLimit(
        input: String,
        limits: CanonicalJsonLimits,
    ) {
        val failure =
            assertFailsWith<CanonicalJsonPreflightException> {
                CanonicalJson.parseToElement(input, limits)
            }

        assertEquals(CanonicalJsonPreflightReason.DOCUMENT_LIMIT_EXCEEDED, failure.reason)
    }

    @Serializable
    private data class SeedEnvelope(
        @SerialName("contractVersion")
        val contractVersion: String,
    )

    @Serializable
    private data class SeedDefaults(
        @SerialName("enabled")
        val enabled: Boolean = false,
        @SerialName("label")
        val label: String? = null,
    )
}
