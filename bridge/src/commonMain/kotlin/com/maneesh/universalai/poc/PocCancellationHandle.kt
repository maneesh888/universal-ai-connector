package com.maneesh.universalai.poc

import kotlinx.coroutines.Job

class PocCancellationHandle internal constructor(
    private val job: Job,
) {
    fun cancel() {
        job.cancel()
    }
}
