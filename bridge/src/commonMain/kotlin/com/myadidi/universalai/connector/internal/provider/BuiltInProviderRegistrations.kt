package com.myadidi.universalai.connector.internal.provider

import com.myadidi.universalai.connector.UniversalAiProviderConfiguration
import com.myadidi.universalai.connector.contract.ProviderId
import com.myadidi.universalai.connector.contract.UniversalAiCapabilityDeclaration
import com.myadidi.universalai.connector.contract.UniversalAiCapabilityLimitName
import com.myadidi.universalai.connector.contract.UniversalAiCapabilityName
import com.myadidi.universalai.connector.contract.UniversalAiCapabilitySet
import com.myadidi.universalai.connector.contract.UniversalAiCapabilitySupport
import com.myadidi.universalai.connector.contract.UniversalAiError
import com.myadidi.universalai.connector.contract.UniversalAiErrorCategory
import com.myadidi.universalai.connector.contract.UniversalAiErrorCode
import com.myadidi.universalai.connector.contract.UniversalAiException
import com.myadidi.universalai.connector.contract.UniversalAiProviderCapabilityProfile
import com.myadidi.universalai.connector.contract.schema.GovernedJsonSchemaSubset
import com.myadidi.universalai.connector.internal.provider.anthropic.AnthropicMessagesAdapter
import com.myadidi.universalai.connector.internal.provider.openai.OpenAiResponsesAdapter
import com.myadidi.universalai.connector.internal.provider.openai.OpenAiStructuredOutput
import com.myadidi.universalai.connector.internal.provider.openaicompatible.OpenAiCompatibleChatCompletionsAdapter
import com.myadidi.universalai.connector.internal.provider.openrouter.OpenRouterChatCompletionsAdapter

internal val OPENAI_PROVIDER_ID: ProviderId = ProviderId.of("openai")
internal val ANTHROPIC_PROVIDER_ID: ProviderId = ProviderId.of("anthropic")
internal val OPENROUTER_PROVIDER_ID: ProviderId = ProviderId.of("openrouter")
internal val OPENAI_COMPATIBLE_PROVIDER_ID: ProviderId = ProviderId.of("openai-compatible")

internal val OPENAI_PROVIDER_CAPABILITY_PROFILE =
    UniversalAiProviderCapabilityProfile(
        providerId = OPENAI_PROVIDER_ID,
        capabilities =
            UniversalAiCapabilitySet.of(
                UniversalAiCapabilityName.StructuredOutput to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Supported,
                        limits =
                            mapOf(
                                UniversalAiCapabilityLimitName.MaxSchemaBytes to
                                    GovernedJsonSchemaSubset.MAX_SCHEMA_BYTES.toLong(),
                                UniversalAiCapabilityLimitName.MaxSchemaDepth to
                                    OpenAiStructuredOutput.MAX_SCHEMA_DEPTH.toLong(),
                            ),
                    ),
                UniversalAiCapabilityName.Streaming to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Supported,
                    ),
            ),
    )

internal val OPENAI_UNKNOWN_MODEL_CAPABILITIES =
    UniversalAiCapabilitySet.of(
        UniversalAiCapabilityName.StructuredOutput to
            UniversalAiCapabilityDeclaration(
                support = UniversalAiCapabilitySupport.Unknown,
            ),
    )

internal val ANTHROPIC_PROVIDER_CAPABILITY_PROFILE =
    UniversalAiProviderCapabilityProfile(
        providerId = ANTHROPIC_PROVIDER_ID,
        capabilities =
            UniversalAiCapabilitySet.of(
                UniversalAiCapabilityName.StructuredOutput to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Supported,
                        limits =
                            mapOf(
                                UniversalAiCapabilityLimitName.MaxSchemaBytes to
                                    GovernedJsonSchemaSubset.MAX_SCHEMA_BYTES.toLong(),
                            ),
                    ),
                UniversalAiCapabilityName.Streaming to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Supported,
                    ),
            ),
    )

internal val OPENROUTER_PROVIDER_CAPABILITY_PROFILE =
    UniversalAiProviderCapabilityProfile(
        providerId = OPENROUTER_PROVIDER_ID,
        capabilities =
            UniversalAiCapabilitySet.of(
                UniversalAiCapabilityName.StructuredOutput to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Supported,
                        limits =
                            mapOf(
                                UniversalAiCapabilityLimitName.MaxSchemaBytes to
                                    GovernedJsonSchemaSubset.MAX_SCHEMA_BYTES.toLong(),
                                UniversalAiCapabilityLimitName.MaxSchemaDepth to
                                    OpenAiStructuredOutput.MAX_SCHEMA_DEPTH.toLong(),
                            ),
                    ),
                UniversalAiCapabilityName.Streaming to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Supported,
                    ),
            ),
    )

internal val OPENROUTER_UNKNOWN_MODEL_CAPABILITIES =
    UniversalAiCapabilitySet.of(
        UniversalAiCapabilityName.StructuredOutput to
            UniversalAiCapabilityDeclaration(
                support = UniversalAiCapabilitySupport.Unknown,
            ),
    )

internal val OPENAI_COMPATIBLE_PROVIDER_CAPABILITY_PROFILE =
    UniversalAiProviderCapabilityProfile(
        providerId = OPENAI_COMPATIBLE_PROVIDER_ID,
        capabilities =
            UniversalAiCapabilitySet.of(
                UniversalAiCapabilityName.StructuredOutput to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Unknown,
                    ),
                UniversalAiCapabilityName.Streaming to
                    UniversalAiCapabilityDeclaration(
                        support = UniversalAiCapabilitySupport.Unknown,
                    ),
            ),
    )

internal val OPENAI_COMPATIBLE_UNKNOWN_MODEL_CAPABILITIES =
    UniversalAiCapabilitySet.of(
        UniversalAiCapabilityName.StructuredOutput to
            UniversalAiCapabilityDeclaration(
                support = UniversalAiCapabilitySupport.Unknown,
            ),
    )

internal val ANTHROPIC_UNKNOWN_MODEL_CAPABILITIES =
    UniversalAiCapabilitySet.of(
        UniversalAiCapabilityName.StructuredOutput to
            UniversalAiCapabilityDeclaration(
                support = UniversalAiCapabilitySupport.Unknown,
            ),
    )

internal fun builtInProviderRegistration(
    configuration: UniversalAiProviderConfiguration,
): ProviderRegistration =
    when (configuration.providerId) {
        OPENAI_PROVIDER_ID ->
            ProviderRegistration(
                providerId = configuration.providerId,
                capabilityProfile = OPENAI_PROVIDER_CAPABILITY_PROFILE,
                modelCapabilityOverrides = { OPENAI_UNKNOWN_MODEL_CAPABILITIES },
                adapterFactory = { transport ->
                    OpenAiResponsesAdapter(
                        configuration = configuration,
                        transport = transport,
                    )
                },
            )

        ANTHROPIC_PROVIDER_ID ->
            ProviderRegistration(
                providerId = configuration.providerId,
                capabilityProfile = ANTHROPIC_PROVIDER_CAPABILITY_PROFILE,
                modelCapabilityOverrides = { ANTHROPIC_UNKNOWN_MODEL_CAPABILITIES },
                adapterFactory = { transport ->
                    AnthropicMessagesAdapter(
                        configuration = configuration,
                        transport = transport,
                    )
                },
            )

        OPENROUTER_PROVIDER_ID ->
            ProviderRegistration(
                providerId = configuration.providerId,
                capabilityProfile = OPENROUTER_PROVIDER_CAPABILITY_PROFILE,
                modelCapabilityOverrides = { OPENROUTER_UNKNOWN_MODEL_CAPABILITIES },
                adapterFactory = { transport ->
                    OpenRouterChatCompletionsAdapter(
                        configuration = configuration,
                        transport = transport,
                    )
                },
            )

        OPENAI_COMPATIBLE_PROVIDER_ID ->
            ProviderRegistration(
                providerId = configuration.providerId,
                capabilityProfile = OPENAI_COMPATIBLE_PROVIDER_CAPABILITY_PROFILE,
                modelCapabilityOverrides = { OPENAI_COMPATIBLE_UNKNOWN_MODEL_CAPABILITIES },
                adapterFactory = { transport ->
                    OpenAiCompatibleChatCompletionsAdapter(
                        configuration = configuration,
                        transport = transport,
                    )
                },
            )

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
