package com.maneesh.universalai.connector.contract.extension

import com.maneesh.universalai.connector.contract.contractSemanticExceptionOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class ExtensionContractsTest {
    private val json = Json

    @Test
    fun namespacesRequireBoundedLowercaseReverseDnsNames() {
        val boundaryNamespace =
            listOf(63, 63, 63, 61).joinToString(".") { length -> "a".repeat(length) }
        val oversizedNamespace =
            listOf(63, 63, 63, 62).joinToString(".") { length -> "a".repeat(length) }

        assertEquals(
            "com.example.provider",
            ExtensionNamespace.of("com.example.provider").rawValue,
        )
        assertEquals(
            "io.a",
            ExtensionNamespace.of("io.a").rawValue,
        )
        assertEquals(
            ExtensionConstraints.MAX_NAMESPACE_BYTES,
            ExtensionNamespace.of(boundaryNamespace).rawValue.utf8Size(),
        )

        listOf(
            "",
            "single",
            "Com.example",
            "com..example",
            "com.-example",
            "com.example-",
            "com.example_thing",
            "com.exämple",
            "${"a".repeat(64)}.example",
            oversizedNamespace,
        ).forEach { invalid ->
            assertSemanticFailure(
                expectedCode = "invalid_extension_namespace",
                expectedPath = "",
                message = invalid,
            ) {
                ExtensionNamespace.of(invalid)
            }
        }

        val failure =
            assertFailsWith<SerializationException> {
                json.decodeFromString<Extensions>(
                    """{"Bad.Namespace":{}}""",
                )
            }
        assertSemanticIssue(
            failure = failure,
            expectedCode = "invalid_extension_namespace",
            expectedPath = "/Bad.Namespace",
        )
    }

    @Test
    fun numbersPreserveExactJsonTokensWithoutCoercion() {
        val tokens =
            listOf(
                "0",
                "-0",
                "-0.0",
                "1.2300e-4",
                "123456789012345678901234567890.00100E+99",
            )

        tokens.forEach { token ->
            val decoded = json.decodeFromString<ExtensionNumber>(token)
            assertEquals(token, decoded.rawValue)
            assertEquals(token, json.encodeToString(decoded))
        }

        assertEquals(42L, ExtensionNumber.of("42").toLongOrNull())
        assertNull(ExtensionNumber.of("42.0").toLongOrNull())
        assertNull(ExtensionNumber.of("1e999").toFiniteDoubleOrNull())

        listOf(
            "",
            "+1",
            "01",
            "-01",
            ".1",
            "1.",
            "NaN",
            "Infinity",
            "1e",
        ).forEach { invalid ->
            assertSemanticFailure(
                expectedCode = "invalid_extension_number",
                expectedPath = "",
                message = invalid,
            ) {
                ExtensionNumber.of(invalid)
            }
        }
        assertSemanticFailure(
            expectedCode = "extension_number_token_too_long",
            expectedPath = "",
        ) {
            ExtensionNumber.of("1".repeat(ExtensionConstraints.MAX_NUMBER_TOKEN_BYTES + 1))
        }
        assertSemanticFailure(
            expectedCode = "invalid_extension_number",
            expectedPath = "",
        ) {
            ExtensionNumber.of(Double.NaN)
        }
    }

    @Test
    fun nestedNumberLimitFailureRetainsItsStableCodeAndJsonPointer() {
        val oversizedNumber = "1".repeat(ExtensionConstraints.MAX_NUMBER_TOKEN_BYTES + 1)
        val failure =
            assertFailsWith<SerializationException> {
                json.decodeFromString<Extensions>(
                    """{"com.example.connector":{"nested":{"number":$oversizedNumber}}}""",
                )
            }

        val issue = failure.contractSemanticExceptionOrNull()
        assertEquals("extension_number_token_too_long", issue?.code)
        assertEquals("/com.example.connector/nested/number", issue?.path)
    }

    @Test
    fun decodedTreeFailuresRetainNestedJsonPointers() {
        val oversizedString =
            "é".repeat(ExtensionConstraints.MAX_STRING_VALUE_BYTES / 2 + 1)
        val stringFailure =
            assertFailsWith<SerializationException> {
                json.decodeFromString<Extensions>(
                    """{"com.example.connector":{"nested":{"value":"$oversizedString"}}}""",
                )
            }
        assertSemanticIssue(
            failure = stringFailure,
            expectedCode = "extension_string_too_long",
            expectedPath = "/com.example.connector/nested/value",
        )

        val oversizedMember =
            "é".repeat(ExtensionConstraints.MAX_MEMBER_NAME_BYTES / 2 + 1)
        val memberFailure =
            assertFailsWith<SerializationException> {
                json.decodeFromString<Extensions>(
                    """{"com.example.connector":{"nested":{"$oversizedMember":null}}}""",
                )
            }
        assertSemanticIssue(
            failure = memberFailure,
            expectedCode = "extension_member_name_too_long",
            expectedPath = "/com.example.connector/nested/$oversizedMember",
        )
    }

    @Test
    fun recursiveValuesRoundTripWithDeterministicObjectOrdering() {
        val value =
            ExtensionValue.objectValue(
                "z" to ExtensionValue.Null,
                "a" to ExtensionValue.boolean(true),
                "nested" to
                    ExtensionValue.array(
                        ExtensionValue.string("text"),
                        ExtensionValue.number("1.2300e-4"),
                        ExtensionValue.objectValue(
                            "later" to ExtensionValue.string("value"),
                            "earlier" to ExtensionValue.boolean(false),
                        ),
                    ),
            )

        val encoded = json.encodeToString<ExtensionValue>(value)

        assertEquals(
            """{"a":true,"nested":["text",1.2300e-4,{"earlier":false,"later":"value"}],"z":null}""",
            encoded,
        )
        assertEquals(value, json.decodeFromString<ExtensionValue>(encoded))
    }

    @Test
    fun extensionsRequireObjectPayloadsAndSortNamespaces() {
        val alpha = ExtensionNamespace.of("com.example.alpha")
        val beta = ExtensionNamespace.of("com.example.beta")
        val extensions =
            Extensions.of(
                beta to
                    ExtensionValue.objectValue(
                        "enabled" to ExtensionValue.boolean(true),
                    ),
                alpha to
                    ExtensionValue.objectValue(
                        "count" to ExtensionValue.number("2"),
                    ),
            )

        assertEquals(
            """{"com.example.alpha":{"count":2},"com.example.beta":{"enabled":true}}""",
            json.encodeToString(extensions),
        )
        assertEquals(
            extensions,
            json.decodeFromString<Extensions>(json.encodeToString(extensions)),
        )
        val scalarRootFailure =
            assertFailsWith<SerializationException> {
            json.decodeFromString<Extensions>(
                """{"com.example.alpha":["not","an","object"]}""",
            )
        }
        assertSemanticIssue(
            failure = scalarRootFailure,
            expectedCode = "invalid_extension_payload",
            expectedPath = "/com.example.alpha",
        )
        assertFailsWith<SerializationException> {
            json.decodeFromString<Extensions>("null")
        }
    }

    @Test
    fun programmaticBuildersRejectDuplicateNamesInsteadOfCollapsingThem() {
        assertSemanticFailure(
            expectedCode = "duplicate_extension_member",
            expectedPath = "/duplicate",
        ) {
            ExtensionValue.objectValue(
                "duplicate" to ExtensionValue.string("first"),
                "duplicate" to ExtensionValue.string("second"),
            )
        }

        val namespace = ExtensionNamespace.of("com.example.duplicate")
        val payload = ExtensionValue.objectValue("value" to ExtensionValue.Null)
        assertSemanticFailure(
            expectedCode = "duplicate_extension_namespace",
            expectedPath = "/com.example.duplicate",
        ) {
            Extensions.of(
                namespace to payload,
                namespace to payload,
            )
        }
    }

    @Test
    fun emptyBagHasStableIdentityAndEncoding() {
        assertTrue(Extensions.Empty.isEmpty)
        assertEquals(0, Extensions.Empty.size)
        assertEquals("{}", json.encodeToString(Extensions.Empty))
        assertSame(Extensions.Empty, Extensions.of(emptyMap()))
        assertEquals(Extensions.Empty, json.decodeFromString<Extensions>("{}"))
    }

    @Test
    fun constructionDefensivelyCopiesCollections() {
        val sourceArray = mutableListOf<ExtensionValue>(ExtensionValue.string("before"))
        val array = ExtensionValue.array(sourceArray)
        sourceArray += ExtensionValue.string("after")
        assertEquals(1, array.values.size)

        val sourceObject =
            mutableMapOf<String, ExtensionValue>(
                "before" to ExtensionValue.boolean(true),
            )
        val payload = ExtensionValue.objectValue(sourceObject)
        sourceObject["after"] = ExtensionValue.boolean(false)
        assertEquals(setOf("before"), payload.members.keys)

        val sourceBag =
            mutableMapOf(
                ExtensionNamespace.of("com.example.provider") to payload,
            )
        val extensions = Extensions.of(sourceBag)
        sourceBag.clear()
        assertEquals(1, extensions.size)

        val returnedSnapshot = extensions.entries.toMutableMap()
        returnedSnapshot.clear()
        assertEquals(1, extensions.size)
    }

    @Test
    fun collectionAndScalarLimitsAreEnforced() {
        ExtensionValue.array(
            List(ExtensionConstraints.MAX_ARRAY_ELEMENTS) { ExtensionValue.Null },
        )
        assertSemanticFailure(
            expectedCode = "extension_array_element_limit_exceeded",
            expectedPath = "",
        ) {
            ExtensionValue.array(
                List(ExtensionConstraints.MAX_ARRAY_ELEMENTS + 1) { ExtensionValue.Null },
            )
        }

        ExtensionValue.objectValue(
            (0 until ExtensionConstraints.MAX_OBJECT_MEMBERS).associate { index ->
                "member_$index" to ExtensionValue.Null
            },
        )
        assertSemanticFailure(
            expectedCode = "extension_object_member_limit_exceeded",
            expectedPath = "",
        ) {
            ExtensionValue.objectValue(
                (0..ExtensionConstraints.MAX_OBJECT_MEMBERS).associate { index ->
                    "member_$index" to ExtensionValue.Null
                },
            )
        }

        ExtensionValue.string("é".repeat(ExtensionConstraints.MAX_STRING_VALUE_BYTES / 2))
        assertSemanticFailure(
            expectedCode = "extension_string_too_long",
            expectedPath = "",
        ) {
            ExtensionValue.string("é".repeat(ExtensionConstraints.MAX_STRING_VALUE_BYTES / 2 + 1))
        }

        ExtensionValue.objectValue(
            "é".repeat(ExtensionConstraints.MAX_MEMBER_NAME_BYTES / 2) to ExtensionValue.Null,
        )
        val longMemberName =
            "é".repeat(ExtensionConstraints.MAX_MEMBER_NAME_BYTES / 2 + 1)
        assertSemanticFailure(
            expectedCode = "extension_member_name_too_long",
            expectedPath = "/$longMemberName",
        ) {
            ExtensionValue.objectValue(
                longMemberName to ExtensionValue.Null,
            )
        }
        assertSemanticFailure(
            expectedCode = "invalid_extension_member_name",
            expectedPath = "/line\nbreak",
        ) {
            ExtensionValue.objectValue("line\nbreak" to ExtensionValue.Null)
        }
        assertSemanticFailure(
            expectedCode = "invalid_extension_member_name",
            expectedPath = "/",
        ) {
            ExtensionValue.objectValue("" to ExtensionValue.Null)
        }

        val malformedUnicode = "\uD800"
        assertSemanticFailure(
            expectedCode = "invalid_extension_string",
            expectedPath = "",
        ) {
            ExtensionValue.string(malformedUnicode)
        }
        assertSemanticFailure(
            expectedCode = "invalid_extension_member_name",
            expectedPath = "/$malformedUnicode",
        ) {
            ExtensionValue.objectValue(malformedUnicode to ExtensionValue.Null)
        }
    }

    @Test
    fun depthAndNodeBudgetsAreEnforcedAcrossEachBag() {
        val validDepthPayload =
            ExtensionValue.objectValue(
                "value" to nestedContainers(ExtensionConstraints.MAX_CONTAINER_DEPTH - 1),
            )
        Extensions.of(
            ExtensionNamespace.of("com.example.valid-depth") to validDepthPayload,
        )

        assertSemanticFailure(
            expectedCode = "extension_depth_limit_exceeded",
            expectedPath =
                "/value/" +
                    List(ExtensionConstraints.MAX_CONTAINER_DEPTH - 1) { "0" }.joinToString("/"),
        ) {
            val excessiveDepthPayload =
                ExtensionValue.objectValue(
                    "value" to nestedContainers(ExtensionConstraints.MAX_CONTAINER_DEPTH),
                )
            Extensions.of(
                ExtensionNamespace.of("com.example.excessive-depth") to excessiveDepthPayload,
            )
        }

        val firstPayload =
            ExtensionValue.objectValue(
                "items" to
                    ExtensionValue.array(
                        List(ExtensionConstraints.MAX_ARRAY_ELEMENTS) {
                            ExtensionValue.Null
                        },
                    ),
            )
        val repeatedEntries =
            (0 until 4).associate { index ->
                ExtensionNamespace.of("com.example.nodes$index") to firstPayload
            }
        assertSemanticFailure(
            expectedCode = "extension_node_limit_exceeded",
            expectedPath = "/com.example.nodes3/items/248",
        ) {
            Extensions.of(repeatedEntries)
        }

        ExtensionValue.objectValue(nodeBoundaryMembers(extraScalars = 3))
        assertSemanticFailure(
            expectedCode = "extension_node_limit_exceeded",
            expectedPath = "/extra_3",
        ) {
            ExtensionValue.objectValue(nodeBoundaryMembers(extraScalars = 4))
        }
    }

    @Test
    fun namespaceCountAndCompactBagSizeAreEnforced() {
        val payload = ExtensionValue.objectValue("ok" to ExtensionValue.boolean(true))
        Extensions.of(
            (0 until ExtensionConstraints.MAX_NAMESPACES).associate { index ->
                ExtensionNamespace.of("com.example.namespace$index") to payload
            },
        )
        assertSemanticFailure(
            expectedCode = "extension_namespace_limit_exceeded",
            expectedPath = "",
        ) {
            Extensions.of(
                (0..ExtensionConstraints.MAX_NAMESPACES).associate { index ->
                    ExtensionNamespace.of("com.example.namespace$index") to payload
                },
            )
        }

        val boundaryBag = extensionsWithCompactSize(ExtensionConstraints.MAX_COMPACT_BAG_BYTES)
        assertEquals(
            ExtensionConstraints.MAX_COMPACT_BAG_BYTES,
            boundaryBag.compactEncodedSizeBytes,
        )
        assertSemanticFailure(
            expectedCode = "extension_size_limit_exceeded",
            expectedPath = "",
        ) {
            extensionsWithCompactSize(ExtensionConstraints.MAX_COMPACT_BAG_BYTES + 1)
        }
    }

    @Test
    fun decodedValuesRetainNativeKinds() {
        val decoded =
            json.decodeFromString<ExtensionValue>(
                """[null,true,"true",1,{"value":false}]""",
            )
        val array = assertIs<ExtensionValue.ArrayValue>(decoded)
        assertSame(ExtensionValue.Null, array.values[0])
        assertIs<ExtensionValue.BooleanValue>(array.values[1])
        assertIs<ExtensionValue.StringValue>(array.values[2])
        assertIs<ExtensionValue.NumberValue>(array.values[3])
        val objectValue = assertIs<ExtensionValue.ObjectValue>(array.values[4])
        assertFalse(objectValue.members.isEmpty())
        assertEquals(false, objectValue.boolean("value"))
        assertNull(objectValue.string("value"))
    }

    private fun nestedContainers(count: Int): ExtensionValue {
        var value: ExtensionValue = ExtensionValue.Null
        repeat(count) {
            value = ExtensionValue.array(value)
        }
        return value
    }

    private fun nodeBoundaryMembers(extraScalars: Int): Map<String, ExtensionValue> =
        buildMap {
            repeat(4) { arrayIndex ->
                put(
                    "array_$arrayIndex",
                    ExtensionValue.array(
                        List(254) { ExtensionValue.Null },
                    ),
                )
            }
            repeat(extraScalars) { scalarIndex ->
                put("extra_$scalarIndex", ExtensionValue.Null)
            }
        }

    private fun extensionsWithCompactSize(targetBytes: Int): Extensions {
        val namespace = ExtensionNamespace.of("com.example.size")
        val memberNames = listOf("a", "b", "c", "d", "e")
        val fixedBytes =
            """{"com.example.size":{"a":"","b":"","c":"","d":"","e":""}}"""
                .encodeToByteArray()
                .size
        var remaining = targetBytes - fixedBytes
        val members =
            memberNames.associateWith {
                val size = minOf(remaining, ExtensionConstraints.MAX_STRING_VALUE_BYTES)
                remaining -= size
                ExtensionValue.string("x".repeat(size))
            }
        check(remaining == 0)
        return Extensions.of(
            namespace to ExtensionValue.objectValue(members),
        )
    }

    private fun assertSemanticFailure(
        expectedCode: String,
        expectedPath: String,
        message: String? = null,
        block: () -> Any?,
    ) {
        val failure =
            runCatching(block).exceptionOrNull()
                ?: fail(message ?: "Expected semantic failure $expectedCode at $expectedPath.")
        assertSemanticIssue(failure, expectedCode, expectedPath)
    }

    private fun assertSemanticIssue(
        failure: Throwable,
        expectedCode: String,
        expectedPath: String,
    ) {
        val issue =
            failure.contractSemanticExceptionOrNull()
                ?: fail("Expected semantic failure but received ${failure::class.simpleName}.")
        assertEquals(expectedCode, issue.code)
        assertEquals(expectedPath, issue.path)
    }
}
