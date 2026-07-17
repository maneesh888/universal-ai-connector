package com.maneesh.universalai.poc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal object PocInstrumentation {
    private val unaryCancellations = MutableStateFlow(0)
    private val streamCancellations = MutableStateFlow(0)

    fun reset() {
        unaryCancellations.value = 0
        streamCancellations.value = 0
    }

    fun recordUnaryCancellation() {
        unaryCancellations.update { it + 1 }
    }

    fun recordStreamCancellation() {
        streamCancellations.update { it + 1 }
    }

    fun snapshot(): PocInstrumentationSnapshot =
        PocInstrumentationSnapshot(
            unaryCancellations = unaryCancellations.value,
            streamCancellations = streamCancellations.value,
        )
}
