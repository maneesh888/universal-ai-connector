package com.maneesh.universalai.apple

import kotlinx.coroutines.Job

/** Cancels one response or stream operation without affecting any other operation. */
class AppleCancellationHandle internal constructor(
    private val job: Job,
) {
    fun cancel() {
        job.cancel()
    }
}
