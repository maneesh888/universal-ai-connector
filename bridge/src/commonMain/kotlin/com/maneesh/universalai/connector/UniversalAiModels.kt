@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector

import kotlin.native.HiddenFromObjC

/** Stable error categories exposed by the supported Kotlin API. */
@HiddenFromObjC
enum class UniversalAiErrorCode(
    val stableValue: String,
) {
    INVALID_INPUT("invalid_input"),
    SIMULATED_FAILURE("simulated_failure"),
    CONNECTOR_FAILURE("connector_failure"),
}

/** A typed failure from [UniversalAiConnector]. */
@HiddenFromObjC
class UniversalAiConnectorException(
    val code: UniversalAiErrorCode,
    override val message: String,
) : Exception(message)

/** One ordered event emitted by [UniversalAiConnector.stream]. */
@HiddenFromObjC
data class UniversalAiStreamEvent(
    val sequence: Int,
    val text: String,
)
