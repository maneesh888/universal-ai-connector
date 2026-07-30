package com.maneesh.universalai.connector.internal.provider

import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiError
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.internal.ConnectorEngine
import com.maneesh.universalai.connector.internal.DETERMINISTIC_PROVIDER_ID
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * One internal provider registration.
 *
 * P3 registrations contain only a canonical provider identity and an internal adapter factory.
 * The factory is bound once to its connector's transport after duplicate validation. Provider
 * configuration and wire DTOs remain outside the registry.
 */
internal class ProviderRegistration(
    val providerId: ProviderId,
    private val adapterFactory: (ConnectorTransport) -> ConnectorEngine,
) {
    constructor(
        providerId: ProviderId,
        adapter: ConnectorEngine,
    ) : this(
        providerId = providerId,
        adapterFactory = { adapter },
    )

    fun createAdapter(transport: ConnectorTransport): ConnectorEngine =
        adapterFactory(transport)
}

/**
 * Immutable per-client provider registry.
 *
 * Canonical [ProviderId] values are already validated registry keys whose raw values are
 * deliberately never normalized, so the registry does not case-fold or rewrite them. Entries are
 * sorted once at construction, duplicate identities are rejected before any adapter factory runs,
 * adapters are created exactly once against the connector transport, and request-time lookup
 * performs no mutation.
 */
internal class ProviderRegistry(
    registrations: List<ProviderRegistration>,
    transport: ConnectorTransport,
) {
    private val adaptersById: Map<ProviderId, ConnectorEngine>

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
    }

    /** Returns a deterministic snapshot for internal discovery and verification. */
    val providerIds: List<ProviderId>
        get() = adaptersById.keys.toList()

    fun adapterOrNull(providerId: ProviderId): ConnectorEngine? =
        adaptersById[providerId]
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
