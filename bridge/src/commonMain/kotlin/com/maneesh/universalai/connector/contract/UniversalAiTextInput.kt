@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.native.HiddenFromObjC

/** A raw-backed input role. Unknown future roles remain distinguishable and round-trip unchanged. */
@JvmInline
@Serializable(with = UniversalAiInputRoleSerializer::class)
@HiddenFromObjC
value class UniversalAiInputRole private constructor(
    val rawValue: String,
) {
    val isKnown: Boolean
        get() = this == System || this == Developer || this == User || this == Assistant

    companion object {
        val System: UniversalAiInputRole = UniversalAiInputRole("system")
        val Developer: UniversalAiInputRole = UniversalAiInputRole("developer")
        val User: UniversalAiInputRole = UniversalAiInputRole("user")
        val Assistant: UniversalAiInputRole = UniversalAiInputRole("assistant")

        fun of(rawValue: String): UniversalAiInputRole {
            contractRequire(
                condition = INPUT_ROLE_PATTERN.matches(rawValue),
                code = "invalid_input_role",
                path = "/role",
            ) {
                "Input roles must be 1-$MAX_INPUT_ROLE_CHARACTERS lowercase ASCII characters."
            }
            return UniversalAiInputRole(rawValue)
        }
    }
}

/** One ordered text item in a canonical request. Content is validated but never normalized. */
@Serializable
@HiddenFromObjC
data class UniversalAiTextInput(
    @SerialName("role")
    val role: UniversalAiInputRole,
    @SerialName("content")
    val content: String,
) {
    init {
        contractRequire(
            condition = content.isWellFormedContractUnicode(),
            code = "invalid_input_content",
            path = "/content",
        ) {
            "Input content must contain well-formed Unicode."
        }
        contractRequire(
            condition = content.isNotBlank(),
            code = "blank_input_content",
            path = "/content",
        ) {
            "Input content must contain at least one non-whitespace character."
        }
        contractRequire(
            condition = content.contractUtf8Size() <= MAX_INPUT_CONTENT_BYTES,
            code = "input_content_too_large",
            path = "/content",
        ) {
            "Input content must not exceed $MAX_INPUT_CONTENT_BYTES UTF-8 bytes."
        }
    }
}

internal object UniversalAiInputRoleSerializer : ValidatedStringSerializer<UniversalAiInputRole>(
    serialName = "com.maneesh.universalai.connector.contract.UniversalAiInputRole",
    create = UniversalAiInputRole::of,
    rawValue = UniversalAiInputRole::rawValue,
)

internal const val MAX_INPUT_ITEMS: Int = 128
internal const val MAX_INPUT_CONTENT_BYTES: Int = 262_144
internal const val MAX_TOTAL_INPUT_CONTENT_BYTES: Int = 524_288

private const val MAX_INPUT_ROLE_CHARACTERS: Int = 64
private val INPUT_ROLE_PATTERN = Regex("^[a-z][a-z0-9._-]{0,63}$")
