@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract.extension

import com.maneesh.universalai.connector.contract.contractRequire
import com.maneesh.universalai.connector.contract.json.CanonicalJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlin.native.HiddenFromObjC

/**
 * Immutable extension payloads keyed by validated owner namespace.
 *
 * Each namespace owns one JSON object payload. Empty bags encode as `{}`; containing contract
 * models are responsible for omitting their `extensions` field when this bag is empty.
 *
 * Duplicate JSON object names must be rejected by the canonical codec before deserialization.
 * A materialized kotlinx.serialization `JsonObject` cannot report duplicates after parsing.
 */
@Serializable(with = ExtensionsSerializer::class)
@HiddenFromObjC
class Extensions private constructor(
    entries: Map<ExtensionNamespace, ExtensionValue.ObjectValue>,
    internal val compactEncodedSizeBytes: Int,
) {
    private val storedEntries = entries.toMap()

    val isEmpty: Boolean
        get() = storedEntries.isEmpty()

    val size: Int
        get() = storedEntries.size

    /** Returns a defensive snapshot of the namespace payloads. */
    val entries: Map<ExtensionNamespace, ExtensionValue.ObjectValue>
        get() = storedEntries.toMap()

    operator fun get(namespace: ExtensionNamespace): ExtensionValue.ObjectValue? =
        storedEntries[namespace]

    fun with(
        namespace: ExtensionNamespace,
        payload: ExtensionValue.ObjectValue,
    ): Extensions = of(storedEntries + (namespace to payload))

    fun without(namespace: ExtensionNamespace): Extensions =
        if (namespace !in storedEntries) {
            this
        } else {
            of(storedEntries - namespace)
        }

    internal fun entriesForSerialization():
        Map<ExtensionNamespace, ExtensionValue.ObjectValue> = storedEntries

    override fun equals(other: Any?): Boolean =
        other is Extensions && storedEntries == other.storedEntries

    override fun hashCode(): Int = storedEntries.hashCode()

    companion object {
        val Empty: Extensions =
            Extensions(
                entries = emptyMap(),
                compactEncodedSizeBytes = EMPTY_JSON_OBJECT_BYTES,
            )

        fun of(
            entries: Map<ExtensionNamespace, ExtensionValue.ObjectValue>,
        ): Extensions {
            if (entries.isEmpty()) {
                return Empty
            }

            contractRequire(
                condition = entries.size <= ExtensionConstraints.MAX_NAMESPACES,
                code = "extension_namespace_limit_exceeded",
                path = "",
            ) {
                "Extension bags must not exceed " +
                    "${ExtensionConstraints.MAX_NAMESPACES} namespaces."
            }

            val stableEntries =
                entries.entries
                    .sortedBy { entry -> entry.key.rawValue }
                    .associate { entry -> entry.key to entry.value }
            val validator = ExtensionTreeValidator()
            stableEntries.forEach { (namespace, payload) ->
                validator.validatePayload(
                    value = payload,
                    path = extensionChildPath("", namespace.rawValue),
                )
            }

            val compactEncodedSizeBytes =
                CanonicalJson.format
                    .encodeToString(
                        JsonElement.serializer(),
                        extensionEntriesToJsonElement(stableEntries),
                    )
                    .utf8Size()

            contractRequire(
                condition =
                    compactEncodedSizeBytes <= ExtensionConstraints.MAX_COMPACT_BAG_BYTES,
                code = "extension_size_limit_exceeded",
                path = "",
            ) {
                "Extension bags must not exceed " +
                    "${ExtensionConstraints.MAX_COMPACT_BAG_BYTES} compact JSON bytes."
            }

            return Extensions(
                entries = stableEntries,
                compactEncodedSizeBytes = compactEncodedSizeBytes,
            )
        }

        fun of(
            vararg entries: Pair<ExtensionNamespace, ExtensionValue.ObjectValue>,
        ): Extensions {
            val namespaces = mutableSetOf<ExtensionNamespace>()
            val duplicateNamespace =
                entries.firstOrNull { entry -> !namespaces.add(entry.first) }?.first
            contractRequire(
                condition = duplicateNamespace == null,
                code = "duplicate_extension_namespace",
                path =
                    duplicateNamespace?.let { namespace ->
                        extensionChildPath("", namespace.rawValue)
                    } ?: "",
            ) {
                "Extension bags must not contain duplicate namespaces."
            }
            return of(entries.toMap())
        }
    }
}

private const val EMPTY_JSON_OBJECT_BYTES: Int = 2
