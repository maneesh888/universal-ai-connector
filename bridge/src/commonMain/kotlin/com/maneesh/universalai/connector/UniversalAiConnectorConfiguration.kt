@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.maneesh.universalai.connector

import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.internal.transport.ConnectorBaseUrl
import kotlin.native.HiddenFromObjC

/**
 * Immutable host-owned configuration for one canonical provider.
 *
 * The credential supplier is synchronous and is invoked once for each network request. The
 * connector retains the supplier, never a credential returned by it.
 */
@HiddenFromObjC
class UniversalAiProviderConfiguration(
    val providerId: ProviderId,
    baseUrl: String,
    credentialSupplier: () -> String,
) {
    internal val validatedBaseUrl: ConnectorBaseUrl = ConnectorBaseUrl.parse(baseUrl)
    internal val credentialSupplier: () -> String = credentialSupplier

    /**
     * The validated, normalized provider base URL.
     *
     * Provider traffic requires HTTPS. Plaintext HTTP is accepted only for exact loopback hosts
     * used by local development and test servers.
     */
    val baseUrl: String
        get() = validatedBaseUrl.value
}

/**
 * Immutable per-client provider configuration.
 *
 * Provider identifiers are unique. The empty configuration preserves the deterministic,
 * credential-free connector behavior.
 */
@HiddenFromObjC
class UniversalAiConnectorConfiguration(
    providers: List<UniversalAiProviderConfiguration>,
) {
    private val storedProviders: List<UniversalAiProviderConfiguration>

    init {
        val ordered = providers.sortedBy { provider -> provider.providerId.rawValue }
        val duplicate =
            ordered.zipWithNext().firstOrNull { (first, second) ->
                first.providerId == second.providerId
            }?.first
        require(duplicate == null) {
            "Provider '${duplicate?.providerId?.rawValue}' is configured more than once."
        }
        storedProviders = ordered.toList()
    }

    /** Returns a defensive snapshot in deterministic provider-identifier order. */
    val providers: List<UniversalAiProviderConfiguration>
        get() = storedProviders.toList()

    internal fun providersForRegistration(): List<UniversalAiProviderConfiguration> =
        storedProviders

    companion object {
        val Empty: UniversalAiConnectorConfiguration =
            UniversalAiConnectorConfiguration(emptyList())
    }
}
