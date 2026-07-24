package com.maneesh.universalai.connector.contract.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal const val CURRENT_CONTRACT_VERSION: String = "1"

@OptIn(ExperimentalSerializationApi::class)
internal object CanonicalJson {
    val format: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = true
            isLenient = false
            coerceInputValues = false
            allowSpecialFloatingPointValues = false
            allowStructuredMapKeys = false
            prettyPrint = false
            useAlternativeNames = false
            decodeEnumsCaseInsensitive = false
            useArrayPolymorphism = false
            allowTrailingComma = false
            allowComments = false
            classDiscriminator = "type"
            exceptionsWithDebugInfo = false
        }

    fun parseToElement(
        input: String,
        limits: CanonicalJsonLimits = CanonicalJsonLimits.DEFAULT,
    ): JsonElement {
        CanonicalJsonPreflight.validate(input, limits)
        return format.parseToJsonElement(input)
    }

    fun <T> decode(
        serializer: KSerializer<T>,
        input: String,
        limits: CanonicalJsonLimits = CanonicalJsonLimits.DEFAULT,
    ): T {
        CanonicalJsonPreflight.validate(input, limits)
        return format.decodeFromString(serializer, input)
    }

    fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
        limits: CanonicalJsonLimits = CanonicalJsonLimits.DEFAULT,
    ): String {
        val encoded = format.encodeToString(serializer, value)
        CanonicalJsonPreflight.validate(encoded, limits)
        return encoded
    }
}

internal data class CanonicalJsonLimits(
    val maxUtf8Bytes: Int,
    val maxDepth: Int,
    val maxNodes: Int,
) {
    init {
        require(maxUtf8Bytes > 0)
        require(maxDepth > 0)
        require(maxNodes > 0)
    }

    companion object {
        val DEFAULT =
            CanonicalJsonLimits(
                maxUtf8Bytes = 1_048_576,
                maxDepth = 64,
                maxNodes = 100_000,
            )
    }
}

internal enum class CanonicalJsonPreflightReason {
    INVALID_JSON,
    INVALID_UNICODE,
    DUPLICATE_OBJECT_MEMBER,
    DOCUMENT_LIMIT_EXCEEDED,
}

internal class CanonicalJsonPreflightException(
    val reason: CanonicalJsonPreflightReason,
    val path: String = "",
) : IllegalArgumentException(
        when (reason) {
            CanonicalJsonPreflightReason.INVALID_JSON -> "Canonical JSON is malformed."
            CanonicalJsonPreflightReason.INVALID_UNICODE -> "Canonical JSON contains malformed Unicode."
            CanonicalJsonPreflightReason.DUPLICATE_OBJECT_MEMBER ->
                "Canonical JSON contains a duplicate object member."
            CanonicalJsonPreflightReason.DOCUMENT_LIMIT_EXCEEDED ->
                "Canonical JSON exceeds a configured limit."
        },
    )

private object CanonicalJsonPreflight {
    fun validate(
        input: String,
        limits: CanonicalJsonLimits,
    ) {
        if (input.encodeToByteArray().size > limits.maxUtf8Bytes) {
            throw CanonicalJsonPreflightException(
                CanonicalJsonPreflightReason.DOCUMENT_LIMIT_EXCEEDED,
            )
        }
        Parser(input, limits).validate()
    }

    private class Parser(
        private val input: String,
        private val limits: CanonicalJsonLimits,
    ) {
        private var index = 0
        private var nodes = 0

        fun validate() {
            skipWhitespace()
            parseValue(depth = 0, path = "")
            skipWhitespace()
            if (index != input.length) {
                fail(CanonicalJsonPreflightReason.INVALID_JSON)
            }
        }

        private fun parseValue(
            depth: Int,
            path: String,
        ) {
            nodes += 1
            if (nodes > limits.maxNodes) {
                fail(CanonicalJsonPreflightReason.DOCUMENT_LIMIT_EXCEEDED)
            }
            when (peek()) {
                '{' -> parseObject(depth + 1, path)
                '[' -> parseArray(depth + 1, path)
                '"' -> parseString()
                't' -> consumeLiteral("true")
                'f' -> consumeLiteral("false")
                'n' -> consumeLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> fail(CanonicalJsonPreflightReason.INVALID_JSON)
            }
        }

        private fun parseObject(
            depth: Int,
            path: String,
        ) {
            requireDepth(depth)
            expect('{')
            skipWhitespace()
            if (consumeIf('}')) {
                return
            }

            val names = mutableSetOf<String>()
            while (true) {
                if (peek() != '"') {
                    fail(CanonicalJsonPreflightReason.INVALID_JSON)
                }
                val name = parseString()
                val memberPath = "$path/${name.escapeJsonPointerToken()}"
                if (!names.add(name)) {
                    fail(
                        reason = CanonicalJsonPreflightReason.DUPLICATE_OBJECT_MEMBER,
                        path = memberPath,
                    )
                }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                parseValue(depth, memberPath)
                skipWhitespace()
                when {
                    consumeIf('}') -> return
                    consumeIf(',') -> {
                        skipWhitespace()
                        if (peek() == '}') {
                            fail(CanonicalJsonPreflightReason.INVALID_JSON)
                        }
                    }
                    else -> fail(CanonicalJsonPreflightReason.INVALID_JSON)
                }
            }
        }

        private fun parseArray(
            depth: Int,
            path: String,
        ) {
            requireDepth(depth)
            expect('[')
            skipWhitespace()
            if (consumeIf(']')) {
                return
            }

            var elementIndex = 0
            while (true) {
                parseValue(depth, "$path/$elementIndex")
                elementIndex += 1
                skipWhitespace()
                when {
                    consumeIf(']') -> return
                    consumeIf(',') -> {
                        skipWhitespace()
                        if (peek() == ']') {
                            fail(CanonicalJsonPreflightReason.INVALID_JSON)
                        }
                    }
                    else -> fail(CanonicalJsonPreflightReason.INVALID_JSON)
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < input.length) {
                val character = input[index++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> parseEscape(result)
                    character.code < 0x20 -> fail(CanonicalJsonPreflightReason.INVALID_JSON)
                    character.isHighSurrogate() -> {
                        if (index >= input.length || !input[index].isLowSurrogate()) {
                            fail(CanonicalJsonPreflightReason.INVALID_UNICODE)
                        }
                        result.append(character)
                        result.append(input[index++])
                    }
                    character.isLowSurrogate() -> fail(CanonicalJsonPreflightReason.INVALID_UNICODE)
                    else -> result.append(character)
                }
            }
            fail(CanonicalJsonPreflightReason.INVALID_JSON)
        }

        private fun parseEscape(result: StringBuilder) {
            if (index >= input.length) {
                fail(CanonicalJsonPreflightReason.INVALID_JSON)
            }
            when (val escaped = input[index++]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val first = parseUnicodeEscape()
                    when {
                        first.isHighSurrogate() -> {
                            if (
                                index + 1 >= input.length ||
                                input[index] != '\\' ||
                                input[index + 1] != 'u'
                            ) {
                                fail(CanonicalJsonPreflightReason.INVALID_UNICODE)
                            }
                            index += 2
                            val second = parseUnicodeEscape()
                            if (!second.isLowSurrogate()) {
                                fail(CanonicalJsonPreflightReason.INVALID_UNICODE)
                            }
                            result.append(first)
                            result.append(second)
                        }
                        first.isLowSurrogate() ->
                            fail(CanonicalJsonPreflightReason.INVALID_UNICODE)
                        else -> result.append(first)
                    }
                }
                else -> fail(CanonicalJsonPreflightReason.INVALID_JSON)
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (index + 4 > input.length) {
                fail(CanonicalJsonPreflightReason.INVALID_JSON)
            }
            var value = 0
            repeat(4) {
                value =
                    value * 16 +
                    when (val digit = input[index++]) {
                        in '0'..'9' -> digit.code - '0'.code
                        in 'a'..'f' -> digit.code - 'a'.code + 10
                        in 'A'..'F' -> digit.code - 'A'.code + 10
                        else -> fail(CanonicalJsonPreflightReason.INVALID_JSON)
                    }
            }
            return value.toChar()
        }

        private fun parseNumber() {
            consumeIf('-')
            when (peek()) {
                '0' -> {
                    index += 1
                    if (peek() in '0'..'9') {
                        fail(CanonicalJsonPreflightReason.INVALID_JSON)
                    }
                }
                in '1'..'9' -> consumeDigits()
                else -> fail(CanonicalJsonPreflightReason.INVALID_JSON)
            }

            if (consumeIf('.')) {
                if (peek() !in '0'..'9') {
                    fail(CanonicalJsonPreflightReason.INVALID_JSON)
                }
                consumeDigits()
            }

            if (peek() == 'e' || peek() == 'E') {
                index += 1
                if (peek() == '+' || peek() == '-') {
                    index += 1
                }
                if (peek() !in '0'..'9') {
                    fail(CanonicalJsonPreflightReason.INVALID_JSON)
                }
                consumeDigits()
            }
        }

        private fun consumeDigits() {
            while (peek() in '0'..'9') {
                index += 1
            }
        }

        private fun consumeLiteral(literal: String) {
            if (!input.startsWith(literal, index)) {
                fail(CanonicalJsonPreflightReason.INVALID_JSON)
            }
            index += literal.length
        }

        private fun requireDepth(depth: Int) {
            if (depth > limits.maxDepth) {
                fail(CanonicalJsonPreflightReason.DOCUMENT_LIMIT_EXCEEDED)
            }
        }

        private fun skipWhitespace() {
            while (
                index < input.length &&
                when (input[index]) {
                    ' ', '\t', '\n', '\r' -> true
                    else -> false
                }
            ) {
                index += 1
            }
        }

        private fun expect(character: Char) {
            if (!consumeIf(character)) {
                fail(CanonicalJsonPreflightReason.INVALID_JSON)
            }
        }

        private fun consumeIf(character: Char): Boolean {
            if (peek() != character) {
                return false
            }
            index += 1
            return true
        }

        private fun peek(): Char? = input.getOrNull(index)

        private fun fail(
            reason: CanonicalJsonPreflightReason,
            path: String = "",
        ): Nothing = throw CanonicalJsonPreflightException(reason, path)
    }
}

private fun String.escapeJsonPointerToken(): String =
    replace("~", "~0").replace("/", "~1")
