package com.maneesh.universalai.poc

class PocStreamEvent(
    val sequence: Int,
    val text: String,
)

class PocBridgeError(
    val code: String,
    val message: String,
)

class PocInstrumentationSnapshot(
    val unaryCancellations: Int,
    val streamCancellations: Int,
)

internal class PocFailure(
    val code: String,
    override val message: String,
) : IllegalStateException(message)
