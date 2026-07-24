@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.native.HiddenFromObjC

/** Provider-neutral selection of one provider-owned model. */
@Serializable
@HiddenFromObjC
data class UniversalAiTarget(
    @SerialName("providerId")
    val providerId: ProviderId,
    @SerialName("modelId")
    val modelId: ModelId,
)
