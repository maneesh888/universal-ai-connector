@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import kotlinx.coroutines.CancellationException
import kotlin.native.HiddenFromObjC

/** A host-native Kotlin failure carrying one immutable canonical error. */
@HiddenFromObjC
class UniversalAiException(
    val error: UniversalAiError,
) : Exception(error.message)

/**
 * Maps one operation failure at the canonical host boundary.
 *
 * Caller cancellation remains coroutine control flow, existing canonical exceptions retain their
 * identity, and unexpected failures receive a fixed safe representation without their source
 * message, cause, or metadata.
 */
internal fun Throwable.toUniversalAiException(): UniversalAiException =
    when (this) {
        is CancellationException -> throw this
        is UniversalAiException -> this
        else ->
            UniversalAiException(
                UniversalAiError(
                    category = UniversalAiErrorCategory.Internal,
                    code = UniversalAiErrorCode.ConnectorFailure,
                    message = UNEXPECTED_CONNECTOR_FAILURE_MESSAGE,
                ),
            )
    }

internal const val UNEXPECTED_CONNECTOR_FAILURE_MESSAGE: String =
    "The Universal AI Connector failed unexpectedly."
