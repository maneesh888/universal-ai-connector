package com.myadidi.universalai.samples.host

import com.myadidi.universalai.connector.UniversalAiConnector
import com.myadidi.universalai.connector.UniversalAiConnectorConfiguration
import com.myadidi.universalai.connector.UniversalAiModelListResult
import com.myadidi.universalai.connector.UniversalAiProviderConfiguration
import com.myadidi.universalai.connector.contract.ModelId
import com.myadidi.universalai.connector.contract.ProviderId
import com.myadidi.universalai.connector.contract.UniversalAiGenerationParameters
import com.myadidi.universalai.connector.contract.UniversalAiInputRole
import com.myadidi.universalai.connector.contract.UniversalAiRequest
import com.myadidi.universalai.connector.contract.UniversalAiTarget
import com.myadidi.universalai.connector.contract.UniversalAiTextInput
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LiveProvider(val id: String, val title: String, val defaultUrl: String) {
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1"),
    ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com/v1"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
    GATEWAY("openai-compatible", "OpenAI-compatible Gateway", ""),
}

class LiveConfiguration(val provider: LiveProvider, baseUrl: String) {
    val baseUrl: String = baseUrl.ifEmpty { provider.defaultUrl }.removeSuffix("/")
    val credentialKey: String get() = "${provider.id}|${this.baseUrl}"

    init {
        val uri = runCatching { URI(this.baseUrl) }.getOrNull()
        require(
            this.baseUrl.length <= 2048 && uri != null && !uri.host.isNullOrEmpty() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.port in -1..65535 && uri.port != 0 && uri.path.endsWith("/v1") &&
                (uri.scheme == "https" ||
                    (uri.scheme == "http" && uri.host in listOf("localhost", "127.0.0.1", "[::1]"))),
        ) { "Enter an HTTPS base URL ending in /v1; only loopback HTTP is allowed." }
    }

    override fun toString() = "LiveConfiguration(redacted)"
}

fun validateLiveCredential(value: String) {
    require(value.isNotBlank() && value.toByteArray().size <= 8192 && value.none { it.isISOControl() }) {
        "Enter a non-empty credential without control characters."
    }
}

interface LiveCredentialStore {
    fun read(key: String): String?
    fun save(key: String, credential: String)
    fun clearAll()
    fun close() {}
}

sealed interface LiveModels {
    data class Supported(val ids: List<String>) : LiveModels
    data object Unsupported : LiveModels
}

interface LiveClient : AutoCloseable {
    suspend fun listModels(): LiveModels
    suspend fun testConnection(exactModel: String)
}

fun interface LiveClientFactory {
    fun create(configuration: LiveConfiguration, store: LiveCredentialStore): LiveClient
}

object PublicLiveClientFactory : LiveClientFactory {
    override fun create(configuration: LiveConfiguration, store: LiveCredentialStore): LiveClient {
        val provider = ProviderId.of(configuration.provider.id)
        val connector = UniversalAiConnector(
            UniversalAiConnectorConfiguration(
                providers = listOf(
                    UniversalAiProviderConfiguration(
                        providerId = provider,
                        baseUrl = configuration.baseUrl,
                        credentialSupplier = { checkNotNull(store.read(configuration.credentialKey)) },
                    ),
                ),
            ),
        )
        return object : LiveClient {
            override suspend fun listModels(): LiveModels = when (val result = connector.listModels(provider)) {
                is UniversalAiModelListResult.Supported -> LiveModels.Supported(result.models.map { it.target.modelId.rawValue })
                is UniversalAiModelListResult.Unsupported -> LiveModels.Unsupported
            }

            override suspend fun testConnection(exactModel: String) {
                val target = UniversalAiTarget(providerId = provider, modelId = ModelId.of(exactModel))
                val response = connector.respond(
                    UniversalAiRequest(
                        target = target,
                        input = listOf(UniversalAiTextInput(UniversalAiInputRole.User, "Reply with OK.")),
                        generation = UniversalAiGenerationParameters(maxOutputTokens = 16),
                    ),
                )
                check(response.target == target && response.outputs.isNotEmpty())
                // The response body is deliberately never published to sample state or diagnostics.
            }

            override fun close() = connector.close()
        }
    }
}

enum class Discovery { IDLE, LOADING, RETRYING, LOADED, EMPTY, UNSUPPORTED, FAILED, CANCELLED }
enum class Connection { IDLE, TESTING, CONNECTED, FAILED, CANCELLED }

data class LiveUiState(
    val provider: LiveProvider = LiveProvider.OPENAI,
    val baseUrl: String = "",
    val credentialStored: Boolean = false,
    val status: String = "No live credential saved.",
    val discovery: Discovery = Discovery.IDLE,
    val models: List<String> = emptyList(),
    val selectedModel: String? = null,
    val manualModel: String = "",
    val connection: Connection = Connection.IDLE,
    val connectedModel: String? = null,
    val busy: Boolean = false,
) {
    val canTest: Boolean get() = credentialStored && !busy &&
        ((discovery == Discovery.LOADED && selectedModel != null) ||
            (discovery == Discovery.UNSUPPORTED && runCatching { ModelId.of(manualModel) }.isSuccess))
}

/** Main-thread host controller. It owns operation identity, configuration, and client lifetimes. */
class LiveAiController(
    private val scope: CoroutineScope,
    private val store: LiveCredentialStore,
    private val factory: LiveClientFactory = PublicLiveClientFactory,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(LiveUiState())
    val state = mutableState.asStateFlow()
    private var activeJob: Job? = null
    private var activeClient: LiveClient? = null
    private var operationId = 0L
    private var closed = false

    init { refreshCredential() }

    fun configure(provider: LiveProvider, baseUrl: String) {
        if (closed) return
        discardOperation()
        mutableState.value = LiveUiState(provider = provider, baseUrl = baseUrl)
        refreshCredential()
    }

    fun saveCredential(value: String) {
        if (closed) return
        discardOperation()
        resetInteraction()
        try {
            validateLiveCredential(value)
            resetInteraction()
            store.save(configuration().credentialKey, value)
            mutableState.value = state.value.copy(credentialStored = true, status = "Credential available for this session.")
        } catch (_: Exception) {
            mutableState.value = state.value.copy(credentialStored = false, status = "Credential could not be saved. Check configuration and secure storage.")
        }
    }

    fun clearConfiguration() {
        if (closed) return
        discardOperation()
        mutableState.value = LiveUiState(status = "Clearing live configuration…")
        try {
            store.clearAll()
            mutableState.value = LiveUiState(status = "Live configuration and credentials cleared.")
        } catch (_: Exception) {
            mutableState.value = LiveUiState(status = "Secure storage could not be cleared. Retry Clear configuration.")
        }
    }

    fun selectModel(id: String) {
        if (closed || state.value.busy) return
        mutableState.value = state.value.copy(
            selectedModel = id.takeIf { state.value.discovery == Discovery.LOADED && it in state.value.models },
            connection = Connection.IDLE, connectedModel = null,
        )
    }

    fun setManualModel(id: String) {
        if (closed || state.value.busy || state.value.discovery != Discovery.UNSUPPORTED) return
        mutableState.value = state.value.copy(manualModel = id, connection = Connection.IDLE, connectedModel = null)
    }

    fun loadModels(retry: Boolean = false) = startOperation(testing = false, retry = retry)
    fun testConnection() = startOperation(testing = true, retry = false)

    fun cancel() {
        if (closed || !state.value.busy) return
        val testing = state.value.connection == Connection.TESTING
        discardOperation()
        mutableState.value = state.value.copy(
            discovery = Discovery.CANCELLED,
            connection = if (testing) Connection.CANCELLED else Connection.IDLE,
            connectedModel = null, status = "Operation cancelled.",
        )
    }

    fun deactivate() {
        if (closed) return
        if (state.value.busy) cancel() else discardOperation()
    }

    override fun close() {
        deactivate()
        closed = true
        store.close()
    }

    private fun startOperation(testing: Boolean, retry: Boolean) {
        if (closed || state.value.busy || !state.value.credentialStored) return
        val selected = state.value.selectedModel
        val manual = state.value.manualModel.takeIf { state.value.discovery == Discovery.UNSUPPORTED }
        val id = ++operationId
        mutableState.value = state.value.copy(
            busy = true, discovery = if (retry) Discovery.RETRYING else Discovery.LOADING,
            connection = if (testing) Connection.TESTING else Connection.IDLE,
            connectedModel = null, status = if (testing) "Discovering before connection test…" else "Loading models…",
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var client: LiveClient? = null
            try {
                client = factory.create(configuration(), store)
                activeClient = client
                val result = client.listModels()
                currentCoroutineContext().ensureActive()
                if (id != operationId) return@launch
                applyModels(result)
                if (testing) {
                    val exact = when (result) {
                        is LiveModels.Supported -> {
                            check(result.ids.isNotEmpty())
                            check(selected != null && selected in result.ids)
                            selected
                        }
                        LiveModels.Unsupported -> checkNotNull(manual).also { ModelId.of(it) }
                    }
                    mutableState.value = state.value.copy(status = "Testing the exact selected model…")
                    client.testConnection(exact)
                    currentCoroutineContext().ensureActive()
                    if (id != operationId) return@launch
                    mutableState.value = state.value.copy(connection = Connection.CONNECTED, connectedModel = exact, status = "Connected using the exact selected model.")
                }
            } catch (cancelled: CancellationException) {
                if (id == operationId) {
                    mutableState.value = state.value.copy(discovery = Discovery.CANCELLED, connection = if (testing) Connection.CANCELLED else Connection.IDLE, status = "Operation cancelled.")
                }
            } catch (_: Exception) {
                if (id == operationId) {
                    mutableState.value = state.value.copy(
                        discovery = if (state.value.discovery in listOf(Discovery.LOADING, Discovery.RETRYING)) Discovery.FAILED else state.value.discovery,
                        connection = if (testing) Connection.FAILED else Connection.IDLE,
                        status = "Live operation failed. Check configuration, discovery, and exact selection. No substitute was used.",
                    )
                }
            } finally {
                if (id == operationId) {
                    runCatching { client?.close() }
                    activeClient = null
                    activeJob = null
                    mutableState.value = state.value.copy(busy = false)
                }
            }
        }
        activeJob = job
        job.start()
    }

    private fun applyModels(result: LiveModels) {
        mutableState.value = when (result) {
            is LiveModels.Supported -> state.value.copy(
                discovery = if (result.ids.isEmpty()) Discovery.EMPTY else Discovery.LOADED,
                models = result.ids.toList(), manualModel = "",
                selectedModel = state.value.selectedModel?.takeIf { it in result.ids },
                status = if (result.ids.isEmpty()) "Supported discovery returned no models. Connection is blocked." else "Choose an exact discovered model.",
            )
            LiveModels.Unsupported -> state.value.copy(
                discovery = Discovery.UNSUPPORTED, models = emptyList(), selectedModel = null,
                manualModel = state.value.manualModel,
                status = "Discovery is explicitly unsupported. Enter an exact model ID.",
            )
        }
    }

    private fun configuration() = LiveConfiguration(state.value.provider, state.value.baseUrl)

    private fun refreshCredential() {
        try {
            val stored = store.read(configuration().credentialKey) != null
            mutableState.value = state.value.copy(credentialStored = stored, status = if (stored) "Credential available." else "No live credential saved.")
        } catch (_: Exception) {
            mutableState.value = state.value.copy(credentialStored = false, status = "Enter a valid base URL and save a credential.")
        }
    }

    private fun resetInteraction() {
        mutableState.value = state.value.copy(discovery = Discovery.IDLE, connection = Connection.IDLE, models = emptyList(), selectedModel = null, manualModel = "", connectedModel = null, busy = false)
    }

    private fun discardOperation() {
        operationId++
        activeJob?.cancel()
        activeJob = null
        runCatching { activeClient?.close() }
        activeClient = null
        mutableState.value = state.value.copy(busy = false)
    }
}
