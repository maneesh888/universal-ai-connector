@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract.extension

import com.maneesh.universalai.connector.contract.contractRequire
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline
import kotlin.native.HiddenFromObjC

/**
 * A validated reverse-DNS owner namespace for one extension payload.
 *
 * Namespace ownership is a contract-governance rule. This value validates only the stable wire
 * syntax so connector-owned namespaces can also be decoded and preserved.
 */
@JvmInline
@Serializable(with = ExtensionNamespaceSerializer::class)
@HiddenFromObjC
value class ExtensionNamespace private constructor(
    val rawValue: String,
) {
    companion object {
        /** Creates a namespace or throws [IllegalArgumentException] when [rawValue] is invalid. */
        fun of(rawValue: String): ExtensionNamespace = atPath(rawValue, path = "")

        internal fun atPath(
            rawValue: String,
            path: String,
        ): ExtensionNamespace {
            contractRequire(
                condition = rawValue.length <= ExtensionConstraints.MAX_NAMESPACE_BYTES,
                code = "invalid_extension_namespace",
                path = path,
            ) {
                "Extension namespaces must not exceed " +
                    "${ExtensionConstraints.MAX_NAMESPACE_BYTES} ASCII bytes."
            }
            contractRequire(
                condition = EXTENSION_NAMESPACE_PATTERN.matches(rawValue),
                code = "invalid_extension_namespace",
                path = path,
            ) {
                "Extension namespaces must be lowercase reverse-DNS names with at least two labels."
            }
            return ExtensionNamespace(rawValue)
        }
    }
}

private val EXTENSION_NAMESPACE_PATTERN =
    Regex(
        "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?" +
            "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$",
    )

internal object ExtensionNamespaceSerializer : KSerializer<ExtensionNamespace> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            serialName = "com.maneesh.universalai.connector.contract.extension.ExtensionNamespace",
            kind = PrimitiveKind.STRING,
        )

    override fun serialize(
        encoder: Encoder,
        value: ExtensionNamespace,
    ) {
        encoder.encodeString(value.rawValue)
    }

    override fun deserialize(decoder: Decoder): ExtensionNamespace =
        ExtensionNamespace.of(decoder.decodeString())
}
