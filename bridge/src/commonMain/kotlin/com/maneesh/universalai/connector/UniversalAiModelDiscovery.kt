@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiModelDescriptor
import kotlin.native.HiddenFromObjC

/** The provider-neutral result of listing models for one configured provider. */
@HiddenFromObjC
sealed class UniversalAiModelListResult {
    abstract val providerId: ProviderId

    /** The provider exposed a compatible model-list API and returned this bounded snapshot. */
    class Supported internal constructor(
        override val providerId: ProviderId,
        models: List<UniversalAiModelDescriptor>,
    ) : UniversalAiModelListResult() {
        private val storedModels = models.toList()

        /** Models sorted by exact model identifier after first-occurrence de-duplication. */
        val models: List<UniversalAiModelDescriptor>
            get() = storedModels.toList()
    }

    /** The provider or endpoint explicitly does not expose compatible model discovery. */
    class Unsupported internal constructor(
        override val providerId: ProviderId,
    ) : UniversalAiModelListResult()
}
