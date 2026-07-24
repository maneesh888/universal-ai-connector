@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.native.HiddenFromObjC

/** An opaque caller-owned request identifier. Its exact Unicode value is retained. */
@JvmInline
@Serializable(with = RequestIdSerializer::class)
@HiddenFromObjC
value class RequestId private constructor(
    val rawValue: String,
) {
    companion object {
        fun of(rawValue: String): RequestId =
            ofAtPath(
                rawValue = rawValue,
                path = "/requestId",
            )

        internal fun ofAtPath(
            rawValue: String,
            path: String,
        ): RequestId {
            validateOperationIdentifier(
                rawValue = rawValue,
                code = "invalid_request_id",
                path = path,
                subject = "Request IDs",
            )
            return RequestId(rawValue)
        }
    }
}

/** An opaque connector-owned response identifier. Its exact Unicode value is retained. */
@JvmInline
@Serializable(with = ResponseIdSerializer::class)
@HiddenFromObjC
value class ResponseId private constructor(
    val rawValue: String,
) {
    companion object {
        fun of(rawValue: String): ResponseId =
            ofAtPath(
                rawValue = rawValue,
                path = "/id",
            )

        internal fun ofAtPath(
            rawValue: String,
            path: String,
        ): ResponseId {
            validateOperationIdentifier(
                rawValue = rawValue,
                code = "invalid_response_id",
                path = path,
                subject = "Response IDs",
            )
            return ResponseId(rawValue)
        }
    }
}

/** An opaque connector-owned output identifier. Its exact Unicode value is retained. */
@JvmInline
@Serializable(with = OutputIdSerializer::class)
@HiddenFromObjC
value class OutputId private constructor(
    val rawValue: String,
) {
    companion object {
        fun of(rawValue: String): OutputId =
            ofAtPath(
                rawValue = rawValue,
                path = "/id",
            )

        internal fun ofAtPath(
            rawValue: String,
            path: String,
        ): OutputId {
            validateOperationIdentifier(
                rawValue = rawValue,
                code = "invalid_output_id",
                path = path,
                subject = "Output IDs",
            )
            return OutputId(rawValue)
        }
    }
}

private const val MAX_OPERATION_ID_BYTES: Int = 256

private fun validateOperationIdentifier(
    rawValue: String,
    code: String,
    path: String,
    subject: String,
) {
    contractRequire(
        condition = rawValue.isNotEmpty(),
        code = code,
        path = path,
    ) {
        "$subject must not be empty."
    }
    contractRequire(
        condition = rawValue.isWellFormedContractUnicode(),
        code = code,
        path = path,
    ) {
        "$subject must contain well-formed Unicode."
    }
    contractRequire(
        condition =
            rawValue.none { character ->
                character.isWhitespace() || character.isContractControlCharacter()
            },
        code = code,
        path = path,
    ) {
        "$subject must not contain whitespace or control characters."
    }
    contractRequire(
        condition = rawValue.contractUtf8Size() <= MAX_OPERATION_ID_BYTES,
        code = code,
        path = path,
    ) {
        "$subject must not exceed $MAX_OPERATION_ID_BYTES UTF-8 bytes."
    }
}

internal object RequestIdSerializer : ValidatedStringSerializer<RequestId>(
    serialName = "com.maneesh.universalai.connector.contract.RequestId",
    create = RequestId::of,
    rawValue = RequestId::rawValue,
)

internal object ResponseIdSerializer : ValidatedStringSerializer<ResponseId>(
    serialName = "com.maneesh.universalai.connector.contract.ResponseId",
    create = ResponseId::of,
    rawValue = ResponseId::rawValue,
)

internal object OutputIdSerializer : ValidatedStringSerializer<OutputId>(
    serialName = "com.maneesh.universalai.connector.contract.OutputId",
    create = OutputId::of,
    rawValue = OutputId::rawValue,
)
