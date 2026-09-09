import Combine
import Foundation
import Security
import UniversalAiConnector

enum LiveAiProvider: String, CaseIterable, Identifiable, Sendable {
    case openAI = "openai"
    case anthropic
    case openRouter = "openrouter"
    case openAICompatible = "openai-compatible"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .openAI:
            return "OpenAI"
        case .anthropic:
            return "Anthropic"
        case .openRouter:
            return "OpenRouter"
        case .openAICompatible:
            return "OpenAI-compatible Gateway"
        }
    }

    var defaultBaseURL: String {
        switch self {
        case .openAI:
            return "https://api.openai.com/v1"
        case .anthropic:
            return "https://api.anthropic.com/v1"
        case .openRouter:
            return "https://openrouter.ai/api/v1"
        case .openAICompatible:
            return "https://gateway.example.com/v1"
        }
    }
}

struct LiveAiConfiguration: Equatable, Sendable {
    let provider: LiveAiProvider
    let baseURL: String

    var credentialKey: String {
        "\(provider.rawValue)|\(baseURL)"
    }

    init(provider: LiveAiProvider, baseURL: String) throws {
        let candidate = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard candidate.count <= 2_048,
            let components = URLComponents(string: candidate),
            let scheme = components.scheme?.lowercased(),
            let host = components.host?.lowercased(),
            components.user == nil,
            components.password == nil,
            components.query == nil,
            components.fragment == nil,
            !host.isEmpty
        else {
            throw LiveAiSampleError.invalidBaseURL
        }

        let isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        guard scheme == "https" || (scheme == "http" && isLoopback) else {
            throw LiveAiSampleError.invalidBaseURL
        }
        guard candidate.hasSuffix("/v1") || candidate.hasSuffix("/v1/") else {
            throw LiveAiSampleError.invalidBaseURL
        }

        self.provider = provider
        self.baseURL = candidate
    }
}

struct LiveAiProofSeed: Sendable {
    let provider: LiveAiProvider
    let baseURL: String
    let credential: String
    let modelID: String

    static func fromEnvironment(
        _ environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> LiveAiProofSeed? {
        guard environment["UAC_IOS_SAMPLE_LIVE_BOOTSTRAP"] == "1",
            let rawProvider = environment["UAC_IOS_SAMPLE_LIVE_PROVIDER"],
            let provider = LiveAiProvider(rawValue: rawProvider),
            let credential = environment["UAC_IOS_SAMPLE_LIVE_CREDENTIAL"],
            !credential.isEmpty,
            let modelID = environment["UAC_IOS_SAMPLE_LIVE_MODEL"],
            !modelID.isEmpty
        else {
            return nil
        }
        let baseURL =
            environment["UAC_IOS_SAMPLE_LIVE_BASE_URL"]
            ?? provider.defaultBaseURL
        return LiveAiProofSeed(
            provider: provider,
            baseURL: baseURL,
            credential: credential,
            modelID: modelID
        )
    }
}

struct LiveAiModelOption: Identifiable, Equatable, Sendable {
    let id: String
    let displayName: String?

    var title: String {
        guard let displayName, !displayName.isEmpty, displayName != id else {
            return id
        }
        return "\(displayName) (\(id))"
    }
}

enum LiveAiModelDiscoveryResult: Equatable, Sendable {
    case supported([LiveAiModelOption])
    case unsupported
}

enum LiveAiModelDiscoveryState: Equatable, Sendable {
    case idle
    case loading
    case retrying
    case loaded([LiveAiModelOption])
    case empty
    case unsupported
    case failed(String)
    case cancelled
}

enum LiveAiConnectionState: Equatable, Sendable {
    case idle
    case testing
    case connected(modelID: String)
    case failed(String)
    case cancelled
}

enum LiveAiSampleError: Error, LocalizedError, Sendable {
    case invalidBaseURL
    case invalidCredential
    case credentialMissing
    case modelSelectionRequired
    case selectedModelUnavailable
    case modelSubstitution
    case noResponseOutput
    case secureStorage(OSStatus)

    var errorDescription: String? {
        switch self {
        case .invalidBaseURL:
            return
                "Enter an HTTPS base URL ending in /v1. Loopback HTTP is allowed for local testing."
        case .invalidCredential:
            return "Enter a non-empty credential without line breaks."
        case .credentialMissing:
            return "Save a credential in Keychain before loading models."
        case .modelSelectionRequired:
            return "Choose an exact discovered model before testing the connection."
        case .selectedModelUnavailable:
            return
                "The exact selected model is no longer present in discovery. No substitute was used."
        case .modelSubstitution:
            return "The response did not retain the exact selected model."
        case .noResponseOutput:
            return "The connection response did not contain an output."
        case .secureStorage(let status):
            return "Keychain operation failed (status \(status))."
        }
    }
}

protocol LiveAiCredentialStore: Sendable {
    func credential(for key: String) throws -> String?
    func saveCredential(_ credential: String, for key: String) throws
    func clearAll() throws
}

final class KeychainLiveAiCredentialStore: LiveAiCredentialStore, @unchecked Sendable {
    private let service: String

    init(
        service: String = "com.maneesh.universalai.connector.sample.live-credentials"
    ) {
        self.service = service
    }

    func credential(for key: String) throws -> String? {
        var query = baseQuery
        query[kSecAttrAccount as String] = key
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        query[kSecReturnData as String] = true

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess,
            let data = result as? Data,
            let credential = String(data: data, encoding: .utf8)
        else {
            throw LiveAiSampleError.secureStorage(status)
        }
        return credential
    }

    func saveCredential(_ credential: String, for key: String) throws {
        guard !credential.isEmpty,
            credential.utf8.count <= 8_192,
            !credential.contains("\n"),
            !credential.contains("\r")
        else {
            throw LiveAiSampleError.invalidCredential
        }

        guard let data = credential.data(using: .utf8) else {
            throw LiveAiSampleError.invalidCredential
        }

        var query = baseQuery
        query[kSecAttrAccount as String] = key
        let update = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(
            query as CFDictionary,
            update as CFDictionary
        )
        if updateStatus == errSecSuccess {
            return
        }
        guard updateStatus == errSecItemNotFound else {
            throw LiveAiSampleError.secureStorage(updateStatus)
        }

        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] =
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let addStatus = SecItemAdd(query as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw LiveAiSampleError.secureStorage(addStatus)
        }
    }

    func clearAll() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw LiveAiSampleError.secureStorage(status)
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
    }
}

protocol LiveAiConnectorClient: AnyObject, Sendable {
    func listModels() async throws -> LiveAiModelDiscoveryResult
    func testConnection(modelID: String) async throws
    func close()
}

protocol LiveAiConnectorClientFactory: Sendable {
    func makeClient(
        configuration: LiveAiConfiguration,
        credentialStore: any LiveAiCredentialStore
    ) throws -> any LiveAiConnectorClient
}

struct DefaultLiveAiConnectorClientFactory: LiveAiConnectorClientFactory {
    func makeClient(
        configuration: LiveAiConfiguration,
        credentialStore: any LiveAiCredentialStore
    ) throws -> any LiveAiConnectorClient {
        let providerID = UniversalAiProviderId(
            rawValue: configuration.provider.rawValue
        )
        let credentialKey = configuration.credentialKey
        let provider = UniversalAiProviderConfiguration(
            providerId: providerID,
            baseURL: configuration.baseURL,
            credentialSupplier: {
                guard
                    let credential = try credentialStore.credential(
                        for: credentialKey
                    )
                else {
                    throw LiveAiSampleError.credentialMissing
                }
                return credential
            }
        )
        let connector = try UniversalAiConnector(
            configuration: UniversalAiConnectorConfiguration(
                providers: [provider]
            )
        )
        return UniversalAiLiveConnectorClient(
            connector: connector,
            providerID: providerID
        )
    }
}

private final class UniversalAiLiveConnectorClient:
    LiveAiConnectorClient,
    @unchecked Sendable
{
    private let connector: UniversalAiConnector
    private let providerID: UniversalAiProviderId

    init(
        connector: UniversalAiConnector,
        providerID: UniversalAiProviderId
    ) {
        self.connector = connector
        self.providerID = providerID
    }

    func listModels() async throws -> LiveAiModelDiscoveryResult {
        switch try await connector.listModels(providerId: providerID) {
        case .supported(_, let models):
            return .supported(
                models.map { model in
                    LiveAiModelOption(
                        id: model.target.modelId.rawValue,
                        displayName: model.displayName
                    )
                }
            )
        case .unsupported:
            return .unsupported
        }
    }

    func testConnection(modelID: String) async throws {
        let request = UniversalAiRequest(
            target: UniversalAiTarget(
                providerId: providerID,
                modelId: UniversalAiModelId(rawValue: modelID)
            ),
            input: [
                UniversalAiTextInput(
                    role: .user,
                    content: "Reply with OK."
                )
            ],
            generation: UniversalAiGenerationParameters(
                maxOutputTokens: 16
            )
        )
        let response = try await connector.respond(to: request)
        guard response.target.providerId == providerID,
            response.target.modelId.rawValue == modelID
        else {
            throw LiveAiSampleError.modelSubstitution
        }
        guard !response.outputs.isEmpty else {
            throw LiveAiSampleError.noResponseOutput
        }
    }

    func close() {
        connector.close()
    }
}

@MainActor
final class LiveAiConfigurationViewModel: ObservableObject {
    @Published private(set) var provider: LiveAiProvider
    @Published private(set) var baseURL: String
    @Published var credentialInput = ""
    @Published var manualModelID = ""
    @Published private(set) var credentialStored = false
    @Published private(set) var configurationStatus = "No live credential saved."
    @Published private(set) var discoveryState: LiveAiModelDiscoveryState = .idle
    @Published private(set) var connectionState: LiveAiConnectionState = .idle
    @Published private(set) var selectedModelID: String?

    private let credentialStore: any LiveAiCredentialStore
    private let clientFactory: any LiveAiConnectorClientFactory
    private let preferredProofModelID: String?
    private var configuredClient:
        (configuration: LiveAiConfiguration, client: any LiveAiConnectorClient)?
    private var activeTask: Task<Void, Never>?
    private var activeTaskID: UUID?

    init(
        provider: LiveAiProvider = .openAI,
        proofSeed: LiveAiProofSeed? = nil,
        credentialStore: any LiveAiCredentialStore =
            KeychainLiveAiCredentialStore(),
        clientFactory: any LiveAiConnectorClientFactory =
            DefaultLiveAiConnectorClientFactory()
    ) {
        self.provider = proofSeed?.provider ?? provider
        self.baseURL = proofSeed?.baseURL ?? provider.defaultBaseURL
        self.credentialStore = credentialStore
        self.clientFactory = clientFactory
        self.preferredProofModelID = proofSeed?.modelID
        if let proofSeed {
            importProofCredential(proofSeed)
        } else {
            refreshCredentialStatus()
        }
    }

    deinit {
        activeTask?.cancel()
        configuredClient?.client.close()
    }

    var models: [LiveAiModelOption] {
        guard case .loaded(let models) = discoveryState else {
            return []
        }
        return models
    }

    var isManualModelEntryEnabled: Bool {
        if case .unsupported = discoveryState {
            return true
        }
        return false
    }

    var isBusy: Bool {
        activeTask != nil
    }

    var canRetryDiscovery: Bool {
        switch discoveryState {
        case .empty, .failed, .cancelled:
            return !isBusy
        default:
            return false
        }
    }

    var canTestConnection: Bool {
        guard credentialStored, !isBusy else {
            return false
        }
        switch discoveryState {
        case .loaded:
            return selectedModelID != nil
        case .unsupported:
            return !manualModelID.trimmingCharacters(
                in: .whitespacesAndNewlines
            ).isEmpty
        default:
            return false
        }
    }

    func setProvider(_ provider: LiveAiProvider) {
        guard provider != self.provider else {
            return
        }
        cancelAndDiscardClient()
        self.provider = provider
        baseURL = provider.defaultBaseURL
        resetInteractionState()
        refreshCredentialStatus()
    }

    func setBaseURL(_ value: String) {
        guard value != baseURL else {
            return
        }
        cancelAndDiscardClient()
        baseURL = value
        credentialStored = false
        configurationStatus = "Save a credential for this base URL."
        resetInteractionState()
    }

    func setManualModelID(_ value: String) {
        manualModelID = value
        connectionState = .idle
    }

    func saveCredential() {
        do {
            let configuration = try currentConfiguration()
            try credentialStore.saveCredential(
                credentialInput,
                for: configuration.credentialKey
            )
            credentialInput = ""
            credentialStored = true
            configurationStatus = "Credential saved in Keychain."
            cancelAndDiscardClient()
            resetInteractionState()
        } catch {
            credentialStored = false
            configurationStatus = safeMessage(for: error)
        }
    }

    func clearConfiguration() {
        cancelAndDiscardClient()
        do {
            try credentialStore.clearAll()
            credentialInput = ""
            manualModelID = ""
            credentialStored = false
            configurationStatus = "Live configuration and Keychain credentials cleared."
            resetInteractionState()
        } catch {
            configurationStatus = safeMessage(for: error)
        }
    }

    func loadModels() {
        startDiscovery(retrying: false)
    }

    func retryModelDiscovery() {
        startDiscovery(retrying: true)
    }

    func selectModel(_ modelID: String) {
        guard case .loaded(let models) = discoveryState,
            models.contains(where: { $0.id == modelID })
        else {
            selectedModelID = nil
            connectionState = .idle
            return
        }
        selectedModelID = modelID
        connectionState = .idle
    }

    func testConnection() {
        guard activeTask == nil else {
            return
        }

        let selectedCandidate = selectedModelID
        let manualCandidate = manualModelID.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        let taskID = UUID()
        connectionState = .testing
        discoveryState = .loading
        activeTaskID = taskID
        activeTask = Task { [weak self] in
            guard let self else {
                return
            }
            defer {
                finishTask(taskID)
            }

            do {
                guard isTaskActive(taskID) else {
                    return
                }
                let client = try configuredLiveClient()
                let result = try await client.listModels()
                guard isTaskActive(taskID) else {
                    return
                }
                let exactModelID: String
                switch result {
                case .supported(let models):
                    applySupportedModels(models)
                    guard !models.isEmpty else {
                        connectionState = .failed(
                            "Discovery returned an empty supported list; no request was sent."
                        )
                        return
                    }
                    guard let selectedCandidate else {
                        connectionState = .failed(
                            LiveAiSampleError.modelSelectionRequired.localizedDescription
                        )
                        return
                    }
                    guard models.contains(where: { $0.id == selectedCandidate }) else {
                        connectionState = .failed(
                            LiveAiSampleError.selectedModelUnavailable.localizedDescription
                        )
                        return
                    }
                    selectedModelID = selectedCandidate
                    exactModelID = selectedCandidate
                case .unsupported:
                    discoveryState = .unsupported
                    selectedModelID = nil
                    if let preferredProofModelID {
                        manualModelID = preferredProofModelID
                    }
                    guard !manualCandidate.isEmpty else {
                        connectionState = .failed(
                            LiveAiSampleError.modelSelectionRequired.localizedDescription
                        )
                        return
                    }
                    exactModelID = manualCandidate
                }

                try Task.checkCancellation()
                try await client.testConnection(modelID: exactModelID)
                guard isTaskActive(taskID) else {
                    return
                }
                connectionState = .connected(modelID: exactModelID)
            } catch is CancellationError {
                guard isTaskActive(taskID) else {
                    return
                }
                discoveryState = .cancelled
                connectionState = .cancelled
            } catch {
                guard isTaskActive(taskID) else {
                    return
                }
                connectionState = .failed(safeMessage(for: error))
                if case .loading = discoveryState {
                    discoveryState = .failed(safeMessage(for: error))
                }
            }
        }
    }

    func cancelCurrentOperation() {
        activeTask?.cancel()
    }

    func cancelAll() {
        cancelAndDiscardClient()
    }

    func waitForCurrentOperationForTesting() async {
        let task = activeTask
        await task?.value
    }

    private func startDiscovery(retrying: Bool) {
        guard activeTask == nil else {
            return
        }
        discoveryState = retrying ? .retrying : .loading
        connectionState = .idle
        let taskID = UUID()
        activeTaskID = taskID
        activeTask = Task { [weak self] in
            guard let self else {
                return
            }
            defer {
                finishTask(taskID)
            }
            do {
                guard isTaskActive(taskID) else {
                    return
                }
                let result = try await configuredLiveClient().listModels()
                guard isTaskActive(taskID) else {
                    return
                }
                switch result {
                case .supported(let models):
                    applySupportedModels(models)
                case .unsupported:
                    discoveryState = .unsupported
                    selectedModelID = nil
                    if let preferredProofModelID {
                        manualModelID = preferredProofModelID
                    }
                }
            } catch is CancellationError {
                guard isTaskActive(taskID) else {
                    return
                }
                discoveryState = .cancelled
            } catch {
                guard isTaskActive(taskID) else {
                    return
                }
                discoveryState = .failed(safeMessage(for: error))
            }
        }
    }

    private func applySupportedModels(_ models: [LiveAiModelOption]) {
        manualModelID = ""
        if models.isEmpty {
            discoveryState = .empty
            selectedModelID = nil
            return
        }
        discoveryState = .loaded(models)
        if selectedModelID == nil,
            let preferredProofModelID,
            models.contains(where: { $0.id == preferredProofModelID })
        {
            selectedModelID = preferredProofModelID
        }
        if let selectedModelID,
            !models.contains(where: { $0.id == selectedModelID })
        {
            self.selectedModelID = nil
        }
    }

    private func configuredLiveClient() throws -> any LiveAiConnectorClient {
        guard credentialStored else {
            throw LiveAiSampleError.credentialMissing
        }
        let configuration = try currentConfiguration()
        if let configuredClient,
            configuredClient.configuration == configuration
        {
            return configuredClient.client
        }
        configuredClient?.client.close()
        let client = try clientFactory.makeClient(
            configuration: configuration,
            credentialStore: credentialStore
        )
        configuredClient = (configuration, client)
        return client
    }

    private func currentConfiguration() throws -> LiveAiConfiguration {
        try LiveAiConfiguration(provider: provider, baseURL: baseURL)
    }

    private func resetInteractionState() {
        discoveryState = .idle
        connectionState = .idle
        selectedModelID = nil
        manualModelID = ""
    }

    private func cancelAndDiscardClient() {
        activeTask?.cancel()
        activeTask = nil
        activeTaskID = nil
        configuredClient?.client.close()
        configuredClient = nil
    }

    private func finishTask(_ taskID: UUID) {
        guard activeTaskID == taskID else {
            return
        }
        activeTask = nil
        activeTaskID = nil
    }

    private func isTaskActive(_ taskID: UUID) -> Bool {
        activeTaskID == taskID
    }

    private func refreshCredentialStatus() {
        do {
            let configuration = try currentConfiguration()
            credentialStored =
                try credentialStore.credential(
                    for: configuration.credentialKey
                ) != nil
            configurationStatus =
                credentialStored
                ? "Credential available in Keychain."
                : "No live credential saved."
        } catch {
            credentialStored = false
            configurationStatus = safeMessage(for: error)
        }
    }

    private func importProofCredential(_ proofSeed: LiveAiProofSeed) {
        do {
            let configuration = try currentConfiguration()
            try credentialStore.saveCredential(
                proofSeed.credential,
                for: configuration.credentialKey
            )
            credentialStored = true
            configurationStatus = "Credential imported into Keychain for Simulator proof."
        } catch {
            credentialStored = false
            configurationStatus = safeMessage(for: error)
        }
    }

    private func safeMessage(for error: Error) -> String {
        if let sampleError = error as? LiveAiSampleError {
            return sampleError.localizedDescription
        }
        if let connectorError = error as? UniversalAiConnectorError {
            return "\(connectorError.category.rawValue)/"
                + "\(connectorError.code.rawValue): Live request failed."
        }
        if let validationError = error as? UniversalAiContractValidationError {
            return "\(validationError.code): \(validationError.message)"
        }
        return "The live operation failed without exposing provider details."
    }
}
