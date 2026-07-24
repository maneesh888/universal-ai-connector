package com.maneesh.universalai.connector.contract

internal fun String.contractUtf8Size(): Int = encodeToByteArray().size

internal fun String.isWellFormedContractUnicode(): Boolean {
    var index = 0
    while (index < length) {
        val codeUnit = this[index].code
        when (codeUnit) {
            in HIGH_SURROGATE_START..HIGH_SURROGATE_END -> {
                if (index + 1 >= length) {
                    return false
                }
                val followingCodeUnit = this[index + 1].code
                if (followingCodeUnit !in LOW_SURROGATE_START..LOW_SURROGATE_END) {
                    return false
                }
                index += 2
            }

            in LOW_SURROGATE_START..LOW_SURROGATE_END -> return false
            else -> index += 1
        }
    }
    return true
}

internal fun Char.isContractControlCharacter(): Boolean =
    code in 0x00..0x1F || code == 0x7F

internal fun Char.isUnsafeErrorMessageCharacter(): Boolean =
    isContractControlCharacter() ||
        code in 0x80..0x9F ||
        code == 0x061C ||
        code in 0x200E..0x200F ||
        code in 0x2028..0x202E ||
        code in 0x2066..0x2069

private const val HIGH_SURROGATE_START: Int = 0xD800
private const val HIGH_SURROGATE_END: Int = 0xDBFF
private const val LOW_SURROGATE_START: Int = 0xDC00
private const val LOW_SURROGATE_END: Int = 0xDFFF
