package com.maneesh.universalai.apple

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class AppleBridgeInstrumentation {
    private val responseCancellations = MutableStateFlow(0)
    private val streamCancellations = MutableStateFlow(0)

    fun reset() {
        responseCancellations.value = 0
        streamCancellations.value = 0
    }

    fun recordResponseCancellation() {
        responseCancellations.update { it + 1 }
    }

    fun recordStreamCancellation() {
        streamCancellations.update { it + 1 }
    }

    fun snapshot(): AppleBridgeInstrumentationSnapshot =
        AppleBridgeInstrumentationSnapshot(
            responseCancellations = responseCancellations.value,
            streamCancellations = streamCancellations.value,
        )
}
