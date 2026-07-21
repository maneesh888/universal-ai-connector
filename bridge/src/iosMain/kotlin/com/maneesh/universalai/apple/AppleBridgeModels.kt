package com.maneesh.universalai.apple

/** One ordered stream event exported for the private Swift-package bridge layer. */
class AppleBridgeStreamEvent(
    val sequence: Int,
    val text: String,
)

/** A stable failure exported for mapping into the supported Swift error type. */
class AppleBridgeError(
    val code: String,
    val message: String,
)

/** Test-only cancellation evidence consumed by the Swift integration suite. */
class AppleBridgeInstrumentationSnapshot(
    val responseCancellations: Int,
    val streamCancellations: Int,
)
