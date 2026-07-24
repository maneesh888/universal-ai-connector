package com.maneesh.universalai.connector.contract.testing

/**
 * Counts Unicode scalar positions the way JSON Schema `minLength`/`maxLength` do.
 *
 * Kotlin strings expose UTF-16 code units through [String.length], so a supplementary character
 * must count once rather than as its surrogate pair.
 */
internal fun String.jsonSchemaCodePointCount(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        val first = this[index].code
        val hasSurrogatePair =
            first in HIGH_SURROGATE_RANGE &&
                index + 1 < length &&
                this[index + 1].code in LOW_SURROGATE_RANGE
        index += if (hasSurrogatePair) 2 else 1
        count += 1
    }
    return count
}

private val HIGH_SURROGATE_RANGE: IntRange = 0xD800..0xDBFF
private val LOW_SURROGATE_RANGE: IntRange = 0xDC00..0xDFFF
