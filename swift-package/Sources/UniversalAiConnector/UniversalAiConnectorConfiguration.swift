/// Immutable provider-neutral construction data for one configured adapter.
///
/// The connector retains the supplier, not a credential returned by it. The
/// synchronous supplier is called once for each provider request and may read
/// host-owned secure storage at that time.
public struct UniversalAiProviderConfiguration: Sendable {
    public let providerId: UniversalAiProviderId
    public let baseURL: String
    internal let credentialSupplier: @Sendable () throws -> String

    public init(
        providerId: UniversalAiProviderId,
        baseURL: String,
        credentialSupplier: @escaping @Sendable () throws -> String
    ) {
        self.providerId = providerId
        self.baseURL = baseURL
        self.credentialSupplier = credentialSupplier
    }
}

/// Immutable per-client provider configuration.
public struct UniversalAiConnectorConfiguration: Sendable {
    public let providers: [UniversalAiProviderConfiguration]

    public init(providers: [UniversalAiProviderConfiguration] = []) {
        self.providers = providers
    }
}
