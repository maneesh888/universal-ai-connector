package com.maneesh.universalai.connector.contract.json

/**
 * Exact, platform-neutral helpers for JSON numbers.
 *
 * JSON Schema defines `integer` mathematically rather than lexically, and the canonical contract
 * must not silently change a number by routing it through binary floating point. These helpers
 * operate on the original JSON token and therefore avoid overflow, underflow, and precision loss
 * while making those decisions.
 */
internal object JsonNumberSemantics {
    private val numberPattern =
        Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun isNumber(rawValue: String): Boolean = numberPattern.matches(rawValue)

    /** Compares two JSON numbers by exact mathematical value without floating-point coercion. */
    fun compare(
        leftRawValue: String,
        rightRawValue: String,
    ): Int? {
        val left = ExactNumber.parse(leftRawValue) ?: return null
        val right = ExactNumber.parse(rightRawValue) ?: return null
        if (left.sign != right.sign) {
            return left.sign.compareTo(right.sign)
        }
        if (left.sign == 0) {
            return 0
        }

        val magnitudeComparison = left.compareMagnitude(right)
        return if (left.sign < 0) {
            -magnitudeComparison
        } else {
            magnitudeComparison
        }
    }

    fun isMathematicalInteger(rawValue: String): Boolean {
        val normalized = normalize(rawValue) ?: return false
        if (normalized == "0") {
            return true
        }
        return exponentOf(normalized)
            ?.let { exponent -> exponent.sign >= 0 }
            ?: false
    }

    /**
     * Converts a mathematical JSON integer only when its exact value fits [Int].
     *
     * Tokens such as `1.0` and `1e0` are accepted because JSON Schema treats them as integers.
     */
    fun toExactIntOrNull(rawValue: String): Int? {
        val normalized = normalize(rawValue) ?: return null
        if (normalized == "0") {
            return 0
        }

        val exponentMarker = normalized.lastIndexOf('e')
        val signedDigits = normalized.substring(0, exponentMarker)
        val exponent = exponentOf(normalized) ?: return null
        if (exponent.sign < 0) {
            return null
        }
        val exponentValue = exponent.toNonNegativeIntOrNull() ?: return null
        val negative = signedDigits.startsWith('-')
        val digits = signedDigits.removePrefix("-")
        if (
            digits.length > MAX_INT_DECIMAL_DIGITS ||
            exponentValue > MAX_INT_DECIMAL_DIGITS - digits.length
        ) {
            return null
        }
        val expandedDigitCount = digits.length + exponentValue
        val expanded =
            buildString(expandedDigitCount + if (negative) 1 else 0) {
                if (negative) {
                    append('-')
                }
                append(digits)
                repeat(exponentValue) {
                    append('0')
                }
            }
        return expanded.toIntOrNull()
    }

    /**
     * Converts only finite binary64 values whose normal JSON rendering has the same mathematical
     * value as the input token.
     *
     * This admits ordinary equivalent spellings such as `1e0` and `0.10`, while rejecting
     * underflow (`1e-400`) and decimal values that would be rounded to a different JSON number.
     */
    fun toSemanticallyRoundTrippableDoubleOrNull(rawValue: String): Double? {
        val normalized = normalize(rawValue) ?: return null
        val value =
            rawValue
                .toDoubleOrNull()
                ?.takeIf(Double::isFinite)
                ?: return null
        return value.takeIf {
            normalize(value.toString()) == normalized
        }
    }

    /** Returns a stable exact form of the mathematical JSON number. */
    fun normalize(rawValue: String): String? {
        if (!isNumber(rawValue)) {
            return null
        }

        var token = rawValue
        val negative = token.startsWith('-')
        if (negative) {
            token = token.drop(1)
        }

        val exponentMarker =
            token.indexOfFirst { character ->
                character == 'e' || character == 'E'
            }
        val mantissa =
            if (exponentMarker < 0) {
                token
            } else {
                token.substring(0, exponentMarker)
            }
        val rawExponent =
            if (exponentMarker < 0) {
                "0"
            } else {
                token.substring(exponentMarker + 1)
            }
        val decimalMarker = mantissa.indexOf('.')
        val fractionDigits =
            if (decimalMarker < 0) {
                0
            } else {
                mantissa.length - decimalMarker - 1
            }
        val coefficient =
            mantissa
                .replace(".", "")
                .trimStart('0')
        if (coefficient.isEmpty()) {
            return "0"
        }

        val trailingZeroes = coefficient.length - coefficient.trimEnd('0').length
        val significantDigits = coefficient.dropLast(trailingZeroes)
        val exponent =
            SignedMagnitude
                .parse(rawExponent)
                .plus(SignedMagnitude.fromInt(trailingZeroes - fractionDigits))
        return buildString {
            if (negative) {
                append('-')
            }
            append(significantDigits)
            append('e')
            append(exponent)
        }
    }

    private fun exponentOf(normalized: String): SignedMagnitude? {
        val marker = normalized.lastIndexOf('e')
        if (marker < 0 || marker == normalized.lastIndex) {
            return null
        }
        return SignedMagnitude.parse(normalized.substring(marker + 1))
    }

    private data class ExactNumber(
        val sign: Int,
        val digits: String,
        val exponent: SignedMagnitude,
    ) {
        fun compareMagnitude(other: ExactNumber): Int {
            val order =
                exponent.plus(
                    SignedMagnitude.fromInt(digits.length),
                )
            val otherOrder =
                other.exponent.plus(
                    SignedMagnitude.fromInt(other.digits.length),
                )
            val orderComparison = order.compareTo(otherOrder)
            if (orderComparison != 0) {
                return orderComparison
            }

            val width = maxOf(digits.length, other.digits.length)
            repeat(width) { index ->
                val leftDigit = digits.getOrNull(index) ?: '0'
                val rightDigit = other.digits.getOrNull(index) ?: '0'
                if (leftDigit != rightDigit) {
                    return leftDigit.compareTo(rightDigit)
                }
            }
            return 0
        }

        companion object {
            fun parse(rawValue: String): ExactNumber? {
                val normalized = normalize(rawValue) ?: return null
                if (normalized == "0") {
                    return ExactNumber(
                        sign = 0,
                        digits = "0",
                        exponent = SignedMagnitude.ZERO,
                    )
                }
                val marker = normalized.lastIndexOf('e')
                if (marker <= 0 || marker == normalized.lastIndex) {
                    return null
                }
                val signedDigits = normalized.substring(0, marker)
                return ExactNumber(
                    sign = if (signedDigits.startsWith('-')) -1 else 1,
                    digits = signedDigits.removePrefix("-"),
                    exponent = SignedMagnitude.parse(normalized.substring(marker + 1)),
                )
            }
        }
    }

    private data class SignedMagnitude(
        val sign: Int,
        val magnitude: String,
    ) : Comparable<SignedMagnitude> {
        fun plus(other: SignedMagnitude): SignedMagnitude {
            if (sign == 0) return other
            if (other.sign == 0) return this
            if (sign == other.sign) {
                return SignedMagnitude(sign, addMagnitudes(magnitude, other.magnitude))
            }

            val comparison = compareMagnitudes(magnitude, other.magnitude)
            return when {
                comparison == 0 -> ZERO
                comparison > 0 ->
                    SignedMagnitude(sign, subtractMagnitudes(magnitude, other.magnitude))
                else ->
                    SignedMagnitude(other.sign, subtractMagnitudes(other.magnitude, magnitude))
            }
        }

        fun toNonNegativeIntOrNull(): Int? =
            when {
                sign < 0 -> null
                sign == 0 -> 0
                else -> magnitude.toIntOrNull()
            }

        override fun compareTo(other: SignedMagnitude): Int {
            if (sign != other.sign) {
                return sign.compareTo(other.sign)
            }
            if (sign == 0) {
                return 0
            }
            val magnitudeComparison = compareMagnitudes(magnitude, other.magnitude)
            return if (sign < 0) {
                -magnitudeComparison
            } else {
                magnitudeComparison
            }
        }

        override fun toString(): String =
            when (sign) {
                -1 -> "-$magnitude"
                0 -> "0"
                else -> magnitude
            }

        companion object {
            val ZERO = SignedMagnitude(0, "0")

            fun parse(rawValue: String): SignedMagnitude {
                val negative = rawValue.startsWith('-')
                val unsigned =
                    rawValue
                        .removePrefix("-")
                        .removePrefix("+")
                        .trimStart('0')
                if (unsigned.isEmpty()) {
                    return ZERO
                }
                return SignedMagnitude(
                    sign = if (negative) -1 else 1,
                    magnitude = unsigned,
                )
            }

            fun fromInt(value: Int): SignedMagnitude =
                when {
                    value == 0 -> ZERO
                    value < 0 -> SignedMagnitude(-1, value.toString().removePrefix("-"))
                    else -> SignedMagnitude(1, value.toString())
                }
        }
    }

    private fun compareMagnitudes(
        left: String,
        right: String,
    ): Int =
        when {
            left.length != right.length -> left.length.compareTo(right.length)
            else -> left.compareTo(right)
        }

    private fun addMagnitudes(
        left: String,
        right: String,
    ): String {
        val result = StringBuilder(maxOf(left.length, right.length))
        var leftIndex = left.lastIndex
        var rightIndex = right.lastIndex
        var carry = 0
        while (leftIndex >= 0 || rightIndex >= 0 || carry != 0) {
            val sum =
                carry +
                    left.getOrNull(leftIndex)?.digitToInt().orZero() +
                    right.getOrNull(rightIndex)?.digitToInt().orZero()
            result.append(sum % 10)
            carry = sum / 10
            leftIndex -= 1
            rightIndex -= 1
        }
        return result.reverse().toString()
    }

    private fun subtractMagnitudes(
        larger: String,
        smaller: String,
    ): String {
        val result = StringBuilder(larger.length)
        var largerIndex = larger.lastIndex
        var smallerIndex = smaller.lastIndex
        var borrow = 0
        while (largerIndex >= 0) {
            var difference =
                larger[largerIndex].digitToInt() -
                    smaller.getOrNull(smallerIndex)?.digitToInt().orZero() -
                    borrow
            if (difference < 0) {
                difference += 10
                borrow = 1
            } else {
                borrow = 0
            }
            result.append(difference)
            largerIndex -= 1
            smallerIndex -= 1
        }
        return result.reverse().toString().trimStart('0').ifEmpty { "0" }
    }

    private fun Int?.orZero(): Int = this ?: 0

    private const val MAX_INT_DECIMAL_DIGITS: Int = 10
}
