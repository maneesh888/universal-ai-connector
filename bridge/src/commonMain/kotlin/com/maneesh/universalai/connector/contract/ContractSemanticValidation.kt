package com.maneesh.universalai.connector.contract

import kotlinx.serialization.SerializationException

/**
 * Structured semantic failure used inside the canonical codec.
 *
 * It is intentionally internal: P2-G owns the supported canonical error model. Keeping a stable
 * code and JSON Pointer here lets the fixture harness prove that an invalid document failed for
 * the documented semantic reason instead of merely observing an arbitrary exception.
 */
internal class ContractSemanticException(
    val code: String,
    val path: String,
    message: String,
) : IllegalArgumentException(message)

internal fun contractRequire(
    condition: Boolean,
    code: String,
    path: String,
    message: () -> String,
) {
    if (!condition) {
        throw ContractSemanticException(
            code = code,
            path = path,
            message = message(),
        )
    }
}

internal fun semanticSerializationException(
    code: String,
    path: String,
    message: String,
): SerializationException {
    val cause =
        ContractSemanticException(
            code = code,
            path = path,
            message = message,
        )
    return SerializationException(message, cause)
}

internal fun Throwable.contractSemanticExceptionOrNull(): ContractSemanticException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is ContractSemanticException) {
            return current
        }
        current = current.cause
    }
    return null
}

internal fun ContractSemanticException.withPathPrefix(prefix: String): ContractSemanticException {
    if (prefix.isEmpty()) {
        return this
    }
    val combinedPath =
        when {
            path.isEmpty() -> prefix
            else -> "$prefix$path"
        }
    return ContractSemanticException(
        code = code,
        path = combinedPath,
        message = message ?: code,
    )
}

internal inline fun <T> decodeSemanticComponent(
    pathPrefix: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: SerializationException) {
        val issue = failure.contractSemanticExceptionOrNull() ?: throw failure
        val contextual = issue.withPathPrefix(pathPrefix)
        throw SerializationException(
            message = contextual.message ?: contextual.code,
            cause = contextual,
        )
    } catch (failure: ContractSemanticException) {
        val contextual = failure.withPathPrefix(pathPrefix)
        throw SerializationException(
            message = contextual.message ?: contextual.code,
            cause = contextual,
        )
    }
