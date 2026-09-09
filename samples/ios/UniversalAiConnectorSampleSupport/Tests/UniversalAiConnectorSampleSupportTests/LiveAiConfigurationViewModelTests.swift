import Foundation
import XCTest

@testable import UniversalAiConnectorSampleSupport

@MainActor
final class LiveAiConfigurationViewModelTests: XCTestCase {
    func testSupportedDiscoveryRequiresExplicitSelectionAndReusesExactModel() async {
        let models = [
            LiveAiModelOption(id: "model-a", displayName: "Model A"),
            LiveAiModelOption(id: "model-b", displayName: "Model B"),
        ]
        let client = FakeLiveAiConnectorClient(
            discoverySteps: [
                .result(.supported(models)),
                .result(.supported(models)),
            ]
        )
        let (viewModel, _) = configuredViewModel(client: client)

        viewModel.loadModels()
        await viewModel.waitForCurrentOperationForTesting()

        XCTAssertEqual(viewModel.discoveryState, .loaded(models))
        XCTAssertNil(viewModel.selectedModelID)
        XCTAssertFalse(viewModel.canTestConnection)

        viewModel.selectModel("model-b")
        XCTAssertTrue(viewModel.canTestConnection)
        viewModel.testConnection()
        await viewModel.waitForCurrentOperationForTesting()

        XCTAssertEqual(viewModel.connectionState, .connected(modelID: "model-b"))
        let listCallCount = await client.listCallCount()
        let testedModelIDs = await client.testedModelIDs()
        XCTAssertEqual(listCallCount, 2)
        XCTAssertEqual(testedModelIDs, ["model-b"])
    }

    func testConnectionDoesNotSubstituteWhenSelectedModelDisappears() async {
        let initial = [
            LiveAiModelOption(id: "model-a", displayName: nil),
            LiveAiModelOption(id: "model-b", displayName: nil),
        ]
        let client = FakeLiveAiConnectorClient(
            discoverySteps: [
                .result(.supported(initial)),
                .result(.supported([initial[0]])),
            ]
        )
        let (viewModel, _) = configuredViewModel(client: client)

        viewModel.loadModels()
        await viewModel.waitForCurrentOperationForTesting()
        viewModel.selectModel("model-b")
        viewModel.testConnection()
        await viewModel.waitForCurrentOperationForTesting()

        XCTAssertNil(viewModel.selectedModelID)
        XCTAssertEqual(
            viewModel.connectionState,
            .failed(
                "The exact selected model is no longer present in discovery. No substitute was used."
            )
        )
        let testedModelIDs = await client.testedModelIDs()
        XCTAssertEqual(testedModelIDs, [])
    }

    func testSupportedEmptyDiscoveryBlocksConnectionBeforeRespond() async {
        let client = FakeLiveAiConnectorClient(
            discoverySteps: [
                .result(.supported([])),
                .result(.supported([])),
            ]
        )
        let (viewModel, _) = configuredViewModel(client: client)

        viewModel.loadModels()
        await viewModel.waitForCurrentOperationForTesting()

        XCTAssertEqual(viewModel.discoveryState, .empty)
        XCTAssertFalse(viewModel.canTestConnection)

        viewModel.testConnection()
        await viewModel.waitForCurrentOperationForTesting()

        XCTAssertEqual(viewModel.discoveryState, .empty)
        XCTAssertEqual(
            viewModel.connectionState,
            .failed(
                "Discovery returned an empty supported list; no request was sent."
            )
        )
        let testedModelIDs = await client.testedModelIDs()
        XCTAssertEqual(testedModelIDs, [])
    }

    func testManualModelEntryIsEnabledOnlyAfterExplicitUnsupportedResult() async {
        let client = FakeLiveAiConnectorClient(
            discoverySteps: [
                .result(.unsupported),
                .result(.unsupported),
            ]
        )
        let (viewModel, _) = configuredViewModel(client: client)

        XCTAssertFalse(viewModel.isManualModelEntryEnabled)
        viewModel.loadModels()
        await viewModel.waitForCurrentOperationForTesting()

        XCTAssertEqual(viewModel.discoveryState, .unsupported)
        XCTAssertTrue(viewModel.isManualModelEntryEnabled)
        viewModel.setManualModelID("exact-manual-model")
        XCTAssertTrue(viewModel.canTestConnection)

        viewModel.testConnection()
        await viewModel.waitForCurrentOperationForTesting()

        XCTAssertEqual(
            viewModel.connectionState,
            .connected(modelID: "exact-manual-model")
        )
        let testedModelIDs = await client.testedModelIDs()
        XCTAssertEqual(testedModelIDs, ["exact-manual-model"])
    }

    func testSupportedDiscoveryNeverUsesManualModelEntry() async {
        let models = [LiveAiModelOption(id: "listed-model", displayName: nil)]
        let client = FakeLiveAiConnectorClient(
            discoverySteps: [
                .result(.unsupported),
                .result(.supported(models)),
            ]
        )
        let (viewModel, _) = configuredViewModel(client: client)

        viewModel.loadModels()
        await viewModel.waitForCurrentOperationForTesting()
        viewModel.setManualModelID("manual-model")
        viewModel.retryModelDiscovery()
        XCTAssertEqual(viewModel.discoveryState, .retrying)
        await viewModel.waitForCurrentOperationForTesting()

        XCTAssertEqual(viewModel.discoveryState, .loaded(models))
        XCTAssertEqual(viewModel.manualModelID, "")
        XCTAssertFalse(viewModel.isManualModelEntryEnabled)
        let testedModelIDs = await client.testedModelIDs()
        XCTAssertEqual(testedModelIDs, [])
    }

    func testFailureCanRetryIntoLoadedState() async {
        let models = [LiveAiModelOption(id: "retry-model", displayName: nil)]
        let client = FakeLiveAiConnectorClient(
            discoverySteps: [
                .failure(StubFailure()),
                .result(.supported(models)),
            ]
        )
        let (viewModel, _) = configuredViewModel(client: client)

        viewModel.loadModels()
        await viewModel.waitForCurrentOperationForTesting()

        XCTAssertEqual(
            viewModel.discoveryState,
            .failed("The live operation failed without exposing provider details.")
        )
        XCTAssertTrue(viewModel.canRetryDiscovery)

        viewModel.retryModelDiscovery()
        XCTAssertEqual(viewModel.discoveryState, .retrying)
        await viewModel.waitForCurrentOperationForTesting()

        XCTAssertEqual(viewModel.discoveryState, .loaded(models))
    }

    func testDiscoveryCancellationHasExplicitCancelledState() async {
        let client = FakeLiveAiConnectorClient(
            discoverySteps: [.waitForCancellation]
        )
        let (viewModel, _) = configuredViewModel(client: client)

        viewModel.loadModels()
        XCTAssertEqual(viewModel.discoveryState, .loading)
        viewModel.cancelCurrentOperation()
        await viewModel.waitForCurrentOperationForTesting()

        XCTAssertEqual(viewModel.discoveryState, .cancelled)
        XCTAssertTrue(viewModel.canRetryDiscovery)
    }

    func testProviderChangePreventsCancelledTaskFromOverwritingResetState() async {
        let client = FakeLiveAiConnectorClient(
            discoverySteps: [.waitForCancellation]
        )
        let (viewModel, _) = configuredViewModel(client: client)

        viewModel.loadModels()
        viewModel.setProvider(.anthropic)
        try? await Task.sleep(nanoseconds: 10_000_000)

        XCTAssertEqual(viewModel.provider, .anthropic)
        XCTAssertEqual(viewModel.discoveryState, .idle)
        XCTAssertEqual(viewModel.connectionState, .idle)
        XCTAssertFalse(viewModel.isBusy)
    }

    func testCredentialSaveAndClearNeverExposeCredential() throws {
        let client = FakeLiveAiConnectorClient(discoverySteps: [])
        let store = MemoryLiveAiCredentialStore()
        let factory = FakeLiveAiConnectorClientFactory(client: client)
        let viewModel = LiveAiConfigurationViewModel(
            credentialStore: store,
            clientFactory: factory
        )
        let secret = "credential-that-must-never-be-rendered"

        viewModel.credentialInput = secret
        viewModel.saveCredential()

        XCTAssertTrue(viewModel.credentialStored)
        XCTAssertEqual(viewModel.credentialInput, "")
        XCTAssertFalse(viewModel.configurationStatus.contains(secret))
        XCTAssertEqual(try store.allCredentials().values.first, secret)

        viewModel.clearConfiguration()

        XCTAssertFalse(viewModel.credentialStored)
        XCTAssertTrue(try store.allCredentials().isEmpty)
        XCTAssertFalse(viewModel.configurationStatus.contains(secret))
    }

    func testUnknownCredentialFailureIsRedacted() {
        let secret = "leaked-by-bad-store"
        let client = FakeLiveAiConnectorClient(discoverySteps: [])
        let store = FailingLiveAiCredentialStore(secret: secret)
        let factory = FakeLiveAiConnectorClientFactory(client: client)
        let viewModel = LiveAiConfigurationViewModel(
            credentialStore: store,
            clientFactory: factory
        )

        viewModel.credentialInput = secret
        viewModel.saveCredential()

        XCTAssertFalse(viewModel.configurationStatus.contains(secret))
        XCTAssertEqual(
            viewModel.configurationStatus,
            "The live operation failed without exposing provider details."
        )
    }

    func testProviderChangeResetsDiscoveryAndUsesProviderDefaultBaseURL() async {
        let models = [LiveAiModelOption(id: "model-a", displayName: nil)]
        let client = FakeLiveAiConnectorClient(
            discoverySteps: [.result(.supported(models))]
        )
        let (viewModel, _) = configuredViewModel(client: client)
        viewModel.loadModels()
        await viewModel.waitForCurrentOperationForTesting()
        viewModel.selectModel("model-a")

        viewModel.setProvider(.anthropic)

        XCTAssertEqual(viewModel.provider, .anthropic)
        XCTAssertEqual(viewModel.baseURL, "https://api.anthropic.com/v1")
        XCTAssertEqual(viewModel.discoveryState, .idle)
        XCTAssertEqual(viewModel.connectionState, .idle)
        XCTAssertNil(viewModel.selectedModelID)
        XCTAssertFalse(viewModel.isManualModelEntryEnabled)
    }

    func testInvalidBaseURLFailsBeforeCredentialPersistence() throws {
        let client = FakeLiveAiConnectorClient(discoverySteps: [])
        let store = MemoryLiveAiCredentialStore()
        let factory = FakeLiveAiConnectorClientFactory(client: client)
        let viewModel = LiveAiConfigurationViewModel(
            credentialStore: store,
            clientFactory: factory
        )

        viewModel.setBaseURL("http://provider.example.com/v1")
        viewModel.credentialInput = "not-saved"
        viewModel.saveCredential()

        XCTAssertFalse(viewModel.credentialStored)
        XCTAssertTrue(try store.allCredentials().isEmpty)
        XCTAssertEqual(
            viewModel.configurationStatus,
            "Enter an HTTPS base URL ending in /v1. Loopback HTTP is allowed for local testing."
        )
    }

    func testSimulatorProofSeedImportsCredentialAndSelectsOnlyExactHint() async throws {
        let models = [
            LiveAiModelOption(id: "other-model", displayName: nil),
            LiveAiModelOption(id: "proof-model", displayName: nil),
        ]
        let client = FakeLiveAiConnectorClient(
            discoverySteps: [.result(.supported(models))]
        )
        let store = MemoryLiveAiCredentialStore()
        let factory = FakeLiveAiConnectorClientFactory(client: client)
        let proofSeed = LiveAiProofSeed(
            provider: .openRouter,
            baseURL: LiveAiProvider.openRouter.defaultBaseURL,
            credential: "proof-credential",
            modelID: "proof-model"
        )
        let viewModel = LiveAiConfigurationViewModel(
            proofSeed: proofSeed,
            credentialStore: store,
            clientFactory: factory
        )

        XCTAssertTrue(viewModel.credentialStored)
        XCTAssertFalse(viewModel.configurationStatus.contains("proof-credential"))
        viewModel.loadModels()
        await viewModel.waitForCurrentOperationForTesting()

        XCTAssertEqual(viewModel.selectedModelID, "proof-model")
        XCTAssertEqual(viewModel.discoveryState, .loaded(models))
        XCTAssertTrue(viewModel.canTestConnection)
        XCTAssertEqual(try store.allCredentials().values.first, "proof-credential")
    }

    func testProofSeedEnvironmentRequiresExplicitOptInAndCompleteValues() {
        XCTAssertNil(LiveAiProofSeed.fromEnvironment([:]))
        XCTAssertNil(
            LiveAiProofSeed.fromEnvironment([
                "UAC_IOS_SAMPLE_LIVE_BOOTSTRAP": "1",
                "UAC_IOS_SAMPLE_LIVE_PROVIDER": "openai",
            ])
        )

        let seed = LiveAiProofSeed.fromEnvironment([
            "UAC_IOS_SAMPLE_LIVE_BOOTSTRAP": "1",
            "UAC_IOS_SAMPLE_LIVE_PROVIDER": "anthropic",
            "UAC_IOS_SAMPLE_LIVE_CREDENTIAL": "credential",
            "UAC_IOS_SAMPLE_LIVE_MODEL": "exact-model",
        ])

        XCTAssertEqual(seed?.provider, .anthropic)
        XCTAssertEqual(seed?.baseURL, LiveAiProvider.anthropic.defaultBaseURL)
        XCTAssertEqual(seed?.modelID, "exact-model")
    }

    private func configuredViewModel(
        client: FakeLiveAiConnectorClient
    ) -> (LiveAiConfigurationViewModel, MemoryLiveAiCredentialStore) {
        let store = MemoryLiveAiCredentialStore()
        let factory = FakeLiveAiConnectorClientFactory(client: client)
        let viewModel = LiveAiConfigurationViewModel(
            credentialStore: store,
            clientFactory: factory
        )
        viewModel.credentialInput = "test-credential"
        viewModel.saveCredential()
        return (viewModel, store)
    }
}

private struct StubFailure: Error {}

private enum FakeDiscoveryStep: @unchecked Sendable {
    case result(LiveAiModelDiscoveryResult)
    case failure(any Error)
    case waitForCancellation
}

private actor FakeLiveAiConnectorClient: LiveAiConnectorClient {
    private var discoverySteps: [FakeDiscoveryStep]
    private var recordedModelIDs: [String] = []
    private var recordedListCallCount = 0

    init(discoverySteps: [FakeDiscoveryStep]) {
        self.discoverySteps = discoverySteps
    }

    func listModels() async throws -> LiveAiModelDiscoveryResult {
        recordedListCallCount += 1
        guard !discoverySteps.isEmpty else {
            throw StubFailure()
        }
        let step = discoverySteps.removeFirst()
        switch step {
        case .result(let result):
            return result
        case .failure(let error):
            throw error
        case .waitForCancellation:
            try await Task.sleep(nanoseconds: UInt64.max)
            throw CancellationError()
        }
    }

    func testConnection(modelID: String) async throws {
        recordedModelIDs.append(modelID)
    }

    nonisolated func close() {}

    func testedModelIDs() -> [String] {
        recordedModelIDs
    }

    func listCallCount() -> Int {
        recordedListCallCount
    }
}

private final class FakeLiveAiConnectorClientFactory:
    LiveAiConnectorClientFactory,
    @unchecked Sendable
{
    private let client: FakeLiveAiConnectorClient

    init(client: FakeLiveAiConnectorClient) {
        self.client = client
    }

    func makeClient(
        configuration: LiveAiConfiguration,
        credentialStore: any LiveAiCredentialStore
    ) throws -> any LiveAiConnectorClient {
        client
    }
}

private final class MemoryLiveAiCredentialStore:
    LiveAiCredentialStore,
    @unchecked Sendable
{
    private let lock = NSLock()
    private var credentials: [String: String] = [:]

    func credential(for key: String) throws -> String? {
        lock.lock()
        defer { lock.unlock() }
        return credentials[key]
    }

    func saveCredential(_ credential: String, for key: String) throws {
        lock.lock()
        defer { lock.unlock() }
        credentials[key] = credential
    }

    func clearAll() throws {
        lock.lock()
        defer { lock.unlock() }
        credentials.removeAll()
    }

    func allCredentials() throws -> [String: String] {
        lock.lock()
        defer { lock.unlock() }
        return credentials
    }
}

private final class FailingLiveAiCredentialStore:
    LiveAiCredentialStore,
    @unchecked Sendable
{
    private struct CredentialLeakFailure: Error, CustomStringConvertible {
        let secret: String
        var description: String { secret }
    }

    private let secret: String

    init(secret: String) {
        self.secret = secret
    }

    func credential(for key: String) throws -> String? {
        nil
    }

    func saveCredential(_ credential: String, for key: String) throws {
        throw CredentialLeakFailure(secret: secret)
    }

    func clearAll() throws {
        throw CredentialLeakFailure(secret: secret)
    }
}
