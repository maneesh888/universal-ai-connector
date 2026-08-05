package com.maneesh.universalai.connector.internal.provider

import com.maneesh.universalai.connector.UniversalAiProviderConfiguration
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.internal.provider.openai.OpenAiResponsesAdapter

internal val OPENAI_PROVIDER_ID: ProviderId = ProviderId.of("openai")

internal fun builtInProviderRegistration(
    configuration: UniversalAiProviderConfiguration,
): ProviderRegistration =
    when (configuration.providerId) {
        OPENAI_PROVIDER_ID ->
            ProviderRegistration(configuration.providerId) { transport ->
                OpenAiResponsesAdapter(
                    configuration = configuration,
                    transport = transport,
                )
            }

        else -> throw unsupportedProviderConfiguration()
    }

private fun unsupportedProviderConfiguration(): UniversalAiException =
    UniversalAiException(
        UniversalAiError(
            category = UniversalAiErrorCategory.Validation,
            code = UniversalAiErrorCode.InvalidRequest,
            message = UNSUPPORTED_PROVIDER_CONFIGURATION_MESSAGE,
        ),
    )

internal const val UNSUPPORTED_PROVIDER_CONFIGURATION_MESSAGE: String =
    "The configured provider is not supported by this connector version."
