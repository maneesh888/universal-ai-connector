package com.maneesh.universalai.connector.contract.extension

import com.maneesh.universalai.connector.contract.contractRequire
import com.maneesh.universalai.connector.contract.contractUtf8Size
import com.maneesh.universalai.connector.contract.isContractControlCharacter
import com.maneesh.universalai.connector.contract.isWellFormedContractUnicode

internal object ExtensionConstraints {
    const val MAX_NAMESPACES: Int = 16
    const val MAX_COMPACT_BAG_BYTES: Int = 65_536
    const val MAX_CONTAINER_DEPTH: Int = 16
    const val MAX_VALUE_NODES: Int = 1_024
    const val MAX_OBJECT_MEMBERS: Int = 256
    const val MAX_ARRAY_ELEMENTS: Int = 256
    const val MAX_MEMBER_NAME_BYTES: Int = 256
    const val MAX_STRING_VALUE_BYTES: Int = 16_384
    const val MAX_NUMBER_TOKEN_BYTES: Int = 128
    const val MAX_NAMESPACE_BYTES: Int = 253
}

internal fun requireValidExtensionMemberName(
    name: String,
    path: String,
) {
    contractRequire(
        condition = name.isNotEmpty(),
        code = "invalid_extension_member_name",
        path = path,
    ) {
        "Extension object member names must not be empty."
    }
    contractRequire(
        condition = name.isWellFormedContractUnicode(),
        code = "invalid_extension_member_name",
        path = path,
    ) {
        "Extension object member names must contain well-formed Unicode."
    }
    contractRequire(
        condition = name.none(Char::isContractControlCharacter),
        code = "invalid_extension_member_name",
        path = path,
    ) {
        "Extension object member names must not contain control characters."
    }
    contractRequire(
        condition = name.utf8Size() <= ExtensionConstraints.MAX_MEMBER_NAME_BYTES,
        code = "extension_member_name_too_long",
        path = path,
    ) {
        "Extension object member names must not exceed " +
            "${ExtensionConstraints.MAX_MEMBER_NAME_BYTES} UTF-8 bytes."
    }
}

internal fun requireValidExtensionString(
    value: String,
    path: String,
) {
    contractRequire(
        condition = value.isWellFormedContractUnicode(),
        code = "invalid_extension_string",
        path = path,
    ) {
        "Extension string values must contain well-formed Unicode."
    }
    contractRequire(
        condition = value.utf8Size() <= ExtensionConstraints.MAX_STRING_VALUE_BYTES,
        code = "extension_string_too_long",
        path = path,
    ) {
        "Extension string values must not exceed " +
            "${ExtensionConstraints.MAX_STRING_VALUE_BYTES} UTF-8 bytes."
    }
}

internal fun String.utf8Size(): Int = contractUtf8Size()

internal fun String.escapeExtensionPointerToken(): String =
    replace("~", "~0").replace("/", "~1")

internal fun extensionChildPath(
    parent: String,
    token: String,
): String = "$parent/${token.escapeExtensionPointerToken()}"
