package com.maneesh.universalai.connector.internal.provider

import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiCapabilitySet
import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiProviderCapabilityProfile
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.internal.ConnectorEngine
import com.maneesh.universalai.connector.internal.DETERMINISTIC_PROVIDER_ID
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * One internal provider registration.
 *
 * Registrations contain canonical provider identity and capabilities plus internal model
 * refinement and adapter factories. Factories are bound once to the connector transport after
 * duplicate validation. Provider configuration and wire DTOs remain outside the registry.
 */
internal class ProviderRegistration(
    val providerId: ProviderId,
    val capabilityProfile: UniversalAiProviderCapabilityProfile =
        UniversalAiProviderCapabilityProfile(providerId),
    private val modelCapabilityOverrides: (UniversalAiTarget) -> UniversalAiCapabilitySet = {
        UniversalAiCapabilitySet.Empty
    },
    private val adapterFactory: (ConnectorTransport) -> ConnectorEngine,
) {
    init {
        require(capabilityProfile.providerId == providerId) {
            "Provider capability profile must match its registration identity."
        }
    }

    constructor(
        providerId: ProviderId,
        adapter: ConnectorEngine,
    ) : this(
        providerId = providerId,
        capabilityProfile = UniversalAiProviderCapabilityProfile(providerId),
        adapterFactory = { adapter },
    )

    fun createAdapter(transport: ConnectorTransport): ConnectorEngine =
        adapterFactory(transport)

    fun capabilitiesFor(target: UniversalAiTarget): UniversalAiCapabilitySet =
        UniversalAiCapabilitySet.resolve(
            providerProfile = capabilityProfile,
            modelTarget = target,
            modelOverrides = modelCapabilityOverrides(target),
        )
}

/**
 * Immutable per-client provider registry.
 *
 * Canonical [ProviderId] values are already validated registry keys whose raw values are
 * deliberately never normalized, so the registry does not case-fold or rewrite them. Entries are
 * sorted once at construction, duplicate identities are rejected before any adapter factory runs,
 * adapters are created exactly once against the connector transport, provider/model capability
 * lookup is data-only, and request-time lookup performs no mutation.
 */
internal class ProviderRegistry(
    registrations: List<ProviderRegistration>,
    transport: ConnectorTransport,
) {
    private val adaptersById: Map<ProviderId, ConnectorEngine>
    private val registrationsById: Map<ProviderId, ProviderRegistration>

    init {
        val ordered = registrations.sortedBy { registration -> registration.providerId.rawValue }
        ordered.zipWithNext().firstOrNull { (first, second) ->
            first.providerId == second.providerId
        }?.let { (duplicate, _) ->
            throw IllegalArgumentException(
                "Provider '${duplicate.providerId.rawValue}' is registered more than once.",
            )
        }
        adaptersById =
            ordered.associateTo(linkedMapOf()) { registration ->
                registration.providerId to registration.createAdapter(transport)
            }
        registrationsById =
            ordered.associateTo(linkedMapOf()) { registration ->
                registration.providerId to registration
            }
    }

    /** Returns a deterministic snapshot for internal discovery and verification. */
    val providerIds: List<ProviderId>
        get() = adaptersById.keys.toList()

    fun adapterOrNull(providerId: ProviderId): ConnectorEngine? =
        adaptersById[providerId]

    fun capabilityProfileOrNull(
        providerId: ProviderId,
    ): UniversalAiProviderCapabilityProfile? =
        registrationsById[providerId]?.capabilityProfile

    fun capabilitiesOrNull(
        target: UniversalAiTarget,
    ): UniversalAiCapabilitySet? =
        registrationsById[target.providerId]?.capabilitiesFor(target)
}

/**
 * Routes provider requests through a per-client registry while preserving the accepted local
 * deterministic mode until a real provider adapter milestone is completed.
 */
internal class ProviderRoutingConnectorEngine(
    private val registry: ProviderRegistry,
    private val deterministicEngine: ConnectorEngine,
) : ConnectorEngine {
    override suspend fun respond(request: UniversalAiRequest): UniversalAiResponse =
        adapterFor(request.target.providerId).respond(request)

    override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> =
        flow {
            emitAll(adapterFor(request.target.providerId).stream(request))
        }

    private fun adapterFor(providerId: ProviderId): ConnectorEngine =
        registry.adapterOrNull(providerId)
            ?: deterministicEngine.takeIf { providerId == DETERMINISTIC_PROVIDER_ID }
            ?: throw unregisteredProvider()
}

private fun unregisteredProvider(): UniversalAiException =
    UniversalAiException(
        UniversalAiError(
            category = UniversalAiErrorCategory.Validation,
            code = UniversalAiErrorCode.InvalidRequest,
            message = UNREGISTERED_PROVIDER_MESSAGE,
        ),
    )

internal const val UNREGISTERED_PROVIDER_MESSAGE: String =
    "The requested provider is not registered."
