/// A stable semantic validation failure for standalone Swift contract values.
///
/// Request-shaped values remain nonthrowing and surface the same validation
/// information as ``UniversalAiConnectorError`` when an operation begins.
public struct UniversalAiContractValidationError:
    Error, Sendable, Equatable
{
    public let code: String
    public let path: String
    public let message: String

    public init(code: String, path: String, message: String) {
        self.code = code
        self.path = path
        self.message = message
    }
}

// MARK: - Raw-backed identifiers and discriminators

public struct UniversalAiProviderId: RawRepresentable, Sendable, Hashable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }
}

public struct UniversalAiModelId: RawRepresentable, Sendable, Hashable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }
}

public struct UniversalAiRequestId: RawRepresentable, Sendable, Hashable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }
}

public struct UniversalAiResponseId: RawRepresentable, Sendable, Hashable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }
}

public struct UniversalAiOutputId: RawRepresentable, Sendable, Hashable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }
}

public struct UniversalAiInputRole: RawRepresentable, Sendable, Hashable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public static let system = Self(rawValue: "system")
    public static let developer = Self(rawValue: "developer")
    public static let user = Self(rawValue: "user")
    public static let assistant = Self(rawValue: "assistant")
}

public struct UniversalAiResponseFormatKind:
    RawRepresentable, Sendable, Hashable
{
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public static let plainText = Self(rawValue: "plain_text")
    public static let jsonSchema = Self(rawValue: "json_schema")
}

public struct UniversalAiOutputKind: RawRepresentable, Sendable, Hashable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public static let text = Self(rawValue: "text")
    public static let structuredJson = Self(rawValue: "structured_json")
}

public struct UniversalAiCompletionReason:
    RawRepresentable, Sendable, Hashable
{
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public static let stop = Self(rawValue: "stop")
    public static let maxOutputTokens = Self(rawValue: "max_output_tokens")
    public static let contentFilter = Self(rawValue: "content_filter")
}

public struct UniversalAiStreamEventType:
    RawRepresentable, Sendable, Hashable
{
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public static let responseStarted = Self(rawValue: "response.started")
    public static let outputStarted = Self(rawValue: "output.started")
    public static let outputDelta = Self(rawValue: "output.delta")
    public static let outputCompleted = Self(rawValue: "output.completed")
    public static let usageUpdated = Self(rawValue: "usage.updated")
    public static let responseCompleted = Self(rawValue: "response.completed")
}

public struct UniversalAiErrorCategory: Sendable, Hashable {
    public let rawValue: String

    public init(rawValue: String) throws {
        try UniversalAiContractValidation.validateErrorCategory(rawValue)
        self.rawValue = rawValue
    }

    internal init(trustedRawValue: String) {
        self.rawValue = trustedRawValue
    }

    public static let validation = Self(trustedRawValue: "validation")
    public static let authentication = Self(trustedRawValue: "authentication")
    public static let authorization = Self(trustedRawValue: "authorization")
    public static let notFound = Self(trustedRawValue: "not_found")
    public static let rateLimit = Self(trustedRawValue: "rate_limit")
    public static let transport = Self(trustedRawValue: "transport")
    public static let provider = Self(trustedRawValue: "provider")
    public static let `protocol` = Self(trustedRawValue: "protocol")
    public static let `internal` = Self(trustedRawValue: "internal")
}

public struct UniversalAiErrorCode: Sendable, Hashable {
    public let rawValue: String

    public init(rawValue: String) throws {
        try UniversalAiContractValidation.validateErrorCode(rawValue)
        self.rawValue = rawValue
    }

    internal init(trustedRawValue: String) {
        self.rawValue = trustedRawValue
    }

    public static let invalidInput = Self(trustedRawValue: "invalid_input")
    public static let invalidRequest = Self(trustedRawValue: "invalid_request")
    public static let invalidStreamSequence =
        Self(trustedRawValue: "invalid_stream_sequence")
    public static let incompleteStream =
        Self(trustedRawValue: "incomplete_stream")
    public static let unsupportedTerminalEvent =
        Self(trustedRawValue: "unsupported_terminal_event")
    public static let simulatedFailure =
        Self(trustedRawValue: "simulated_failure")
    public static let connectorFailure =
        Self(trustedRawValue: "connector_failure")
}

public struct UniversalAiExtensionNamespace: Sendable, Hashable {
    public let rawValue: String

    public init(rawValue: String) throws {
        try UniversalAiContractValidation.validateExtensionNamespace(rawValue)
        self.rawValue = rawValue
    }

    internal init(trustedRawValue: String) {
        self.rawValue = trustedRawValue
    }
}

// MARK: - Capabilities and model descriptors

/// A raw-backed capability name that preserves future valid values.
public struct UniversalAiCapabilityName:
    Sendable, Hashable
{
    public let rawValue: String

    public init(rawValue: String) throws {
        try UniversalAiContractValidation.validateCapabilityToken(
            rawValue,
            code: "invalid_capability_name",
            path: ""
        )
        self.rawValue = rawValue
    }

    internal init(trustedRawValue: String) {
        self.rawValue = trustedRawValue
    }

    public static let streaming = Self(trustedRawValue: "streaming")
    public static let structuredOutput =
        Self(trustedRawValue: "structured_output")

    public var isKnown: Bool {
        self == .streaming || self == .structuredOutput
    }
}

/// The conservative interpretation of a capability support value.
public enum UniversalAiCapabilitySupportState: Sendable, Equatable {
    case supported
    case unsupported
    case unknown
}

/// A raw-backed support value that preserves future valid values.
public struct UniversalAiCapabilitySupport:
    Sendable, Hashable
{
    public let rawValue: String

    public init(rawValue: String) throws {
        try UniversalAiContractValidation.validateCapabilityToken(
            rawValue,
            code: "invalid_capability_support",
            path: "/support"
        )
        self.rawValue = rawValue
    }

    internal init(trustedRawValue: String) {
        self.rawValue = trustedRawValue
    }

    public static let supported = Self(trustedRawValue: "supported")
    public static let unsupported = Self(trustedRawValue: "unsupported")
    public static let unknown = Self(trustedRawValue: "unknown")

    public var semanticState: UniversalAiCapabilitySupportState {
        switch self {
        case .supported:
            return .supported
        case .unsupported:
            return .unsupported
        default:
            return .unknown
        }
    }

    public var isKnown: Bool {
        self == .supported || self == .unsupported || self == .unknown
    }
}

/// A raw-backed capability-limit name that preserves future valid values.
public struct UniversalAiCapabilityLimitName:
    Sendable, Hashable
{
    public let rawValue: String

    public init(rawValue: String) throws {
        try UniversalAiContractValidation.validateCapabilityToken(
            rawValue,
            code: "invalid_capability_limit_name",
            path: ""
        )
        self.rawValue = rawValue
    }

    internal init(trustedRawValue: String) {
        self.rawValue = trustedRawValue
    }

    public static let maxSchemaBytes =
        Self(trustedRawValue: "max_schema_bytes")
    public static let maxSchemaDepth =
        Self(trustedRawValue: "max_schema_depth")

    public var isKnown: Bool {
        self == .maxSchemaBytes || self == .maxSchemaDepth
    }
}

// MARK: - Recursive JSON values

/// An exact validated JSON number token that is never coerced through `Double`.
public struct UniversalAiJsonNumber: Sendable, Hashable {
    public let rawValue: String

    public init(rawValue: String) throws {
        try UniversalAiContractValidation.validateJsonNumber(rawValue)
        self.rawValue = rawValue
    }

    internal init(trustedRawValue: String) {
        self.rawValue = trustedRawValue
    }
}

/// A raw-preserving recursive JSON value used by extensions and structured data.
public indirect enum UniversalAiJsonValue: Sendable, Equatable {
    case null
    case boolean(Bool)
    case string(String)
    case number(UniversalAiJsonNumber)
    case array([UniversalAiJsonValue])
    case object(UniversalAiJsonObject)
}

public struct UniversalAiJsonObject: Sendable, Equatable {
    public let members: [String: UniversalAiJsonValue]

    public init(_ members: [String: UniversalAiJsonValue] = [:]) {
        self.members = members
    }

    public subscript(_ name: String) -> UniversalAiJsonValue? {
        members[name]
    }
}

public struct UniversalAiExtensions: Sendable, Equatable {
    public let entries:
        [UniversalAiExtensionNamespace: UniversalAiJsonObject]

    public init(
        _ entries: [UniversalAiExtensionNamespace: UniversalAiJsonObject] = [:]
    ) throws {
        try UniversalAiContractValidation.validateExtensions(entries)
        self.entries = entries
    }

    internal init(
        trustedEntries:
            [UniversalAiExtensionNamespace: UniversalAiJsonObject]
    ) {
        self.entries = trustedEntries
    }

    public static let empty = Self(trustedEntries: [:])

    /// Returns a copy with one namespace payload replaced as a whole.
    public func replacing(
        namespace: UniversalAiExtensionNamespace,
        payload: UniversalAiJsonObject
    ) throws -> Self {
        try Self(entries.merging([namespace: payload]) { _, replacement in
            replacement
        })
    }

    /// Returns a copy without the specified namespace.
    public func removing(
        namespace: UniversalAiExtensionNamespace
    ) -> Self {
        var result = entries
        result.removeValue(forKey: namespace)
        return Self(trustedEntries: result)
    }
}

public struct UniversalAiStructuredOutputSchema: Sendable, Equatable {
    public let value: UniversalAiJsonValue

    public init(value: UniversalAiJsonValue) {
        self.value = value
    }

    public static func == (
        lhs: UniversalAiStructuredOutputSchema,
        rhs: UniversalAiStructuredOutputSchema
    ) -> Bool {
        UniversalAiContractValidation.jsonSemanticallyEqual(
            lhs.value,
            rhs.value
        )
    }
}

public struct UniversalAiStructuredOutputValue: Sendable, Equatable {
    public let value: UniversalAiJsonValue

    public init(value: UniversalAiJsonValue) {
        self.value = value
    }

    public static func == (
        lhs: UniversalAiStructuredOutputValue,
        rhs: UniversalAiStructuredOutputValue
    ) -> Bool {
        UniversalAiContractValidation.jsonSemanticallyEqual(
            lhs.value,
            rhs.value
        )
    }
}

public struct UniversalAiCapabilityDeclaration: Sendable, Equatable {
    public let support: UniversalAiCapabilitySupport
    public let limits: [UniversalAiCapabilityLimitName: Int64]
    public let extensions: UniversalAiExtensions

    public init(
        support: UniversalAiCapabilitySupport,
        limits: [UniversalAiCapabilityLimitName: Int64] = [:],
        extensions: UniversalAiExtensions = .empty
    ) throws {
        try UniversalAiContractValidation.validateCapabilityDeclaration(
            support: support,
            limits: limits
        )
        self.support = support
        self.limits = limits
        self.extensions = extensions
    }

    internal init(
        trustedSupport: UniversalAiCapabilitySupport,
        limits: [UniversalAiCapabilityLimitName: Int64] = [:],
        extensions: UniversalAiExtensions = .empty
    ) {
        self.support = trustedSupport
        self.limits = limits
        self.extensions = extensions
    }
}

public struct UniversalAiCapabilitySet: Sendable, Equatable {
    public let declarations:
        [UniversalAiCapabilityName: UniversalAiCapabilityDeclaration]

    public init(
        _ declarations:
            [UniversalAiCapabilityName: UniversalAiCapabilityDeclaration] = [:]
    ) throws {
        try UniversalAiContractValidation.validateCapabilitySet(declarations)
        self.declarations = declarations
    }

    internal init(
        trustedDeclarations:
            [UniversalAiCapabilityName: UniversalAiCapabilityDeclaration]
    ) {
        self.declarations = trustedDeclarations
    }

    public static let empty = Self(trustedDeclarations: [:])

    public var isEmpty: Bool {
        declarations.isEmpty
    }

    public var count: Int {
        declarations.count
    }

    public subscript(
        _ name: UniversalAiCapabilityName
    ) -> UniversalAiCapabilityDeclaration? {
        declarations[name]
    }

    public func supportState(
        for name: UniversalAiCapabilityName
    ) -> UniversalAiCapabilitySupportState {
        declarations[name]?.support.semanticState ?? .unknown
    }

    /// Resolves provider defaults with sparse model declarations.
    ///
    /// A model declaration replaces the complete same-name provider entry.
    public static func resolve(
        providerProfile: UniversalAiProviderCapabilityProfile,
        modelTarget: UniversalAiTarget,
        modelOverrides: UniversalAiCapabilitySet = .empty
    ) throws -> Self {
        guard providerProfile.providerId == modelTarget.providerId else {
            throw UniversalAiContractValidationError(
                code: "capability_provider_mismatch",
                path: "/target/providerId",
                message:
                    "Provider capability defaults must match the model target provider."
            )
        }
        var resolved = providerProfile.capabilities.declarations
        for (name, declaration) in modelOverrides.declarations {
            resolved[name] = declaration
        }
        return try Self(resolved)
    }
}

/// Provider-level capability defaults without discovery behavior.
public struct UniversalAiProviderCapabilityProfile: Sendable, Equatable {
    public let contractVersion: String
    public let providerId: UniversalAiProviderId
    public let capabilities: UniversalAiCapabilitySet
    public let extensions: UniversalAiExtensions

    public init(
        contractVersion: String = UniversalAiRequest.currentContractVersion,
        providerId: UniversalAiProviderId,
        capabilities: UniversalAiCapabilitySet = .empty,
        extensions: UniversalAiExtensions = .empty
    ) throws {
        try UniversalAiContractValidation.validateContractVersion(
            contractVersion
        )
        try UniversalAiContractValidation.validateProviderId(
            providerId.rawValue,
            path: "/providerId"
        )
        self.contractVersion = contractVersion
        self.providerId = providerId
        self.capabilities = capabilities
        self.extensions = extensions
    }
}

public struct UniversalAiModelTokenLimits: Sendable, Equatable {
    public let contextWindowTokens: Int64?
    public let maxInputTokens: Int64?
    public let maxOutputTokens: Int64?

    public init(
        contextWindowTokens: Int64? = nil,
        maxInputTokens: Int64? = nil,
        maxOutputTokens: Int64? = nil
    ) throws {
        try UniversalAiContractValidation.validateModelTokenLimits(
            contextWindowTokens: contextWindowTokens,
            maxInputTokens: maxInputTokens,
            maxOutputTokens: maxOutputTokens
        )
        self.contextWindowTokens = contextWindowTokens
        self.maxInputTokens = maxInputTokens
        self.maxOutputTokens = maxOutputTokens
    }

    var isEmpty: Bool {
        contextWindowTokens == nil &&
            maxInputTokens == nil &&
            maxOutputTokens == nil
    }
}

/// A provider-neutral model descriptor with effective capabilities.
public struct UniversalAiModelDescriptor: Sendable, Equatable {
    public let contractVersion: String
    public let target: UniversalAiTarget
    public let displayName: String?
    public let limits: UniversalAiModelTokenLimits?
    public let capabilities: UniversalAiCapabilitySet
    public let extensions: UniversalAiExtensions

    public init(
        contractVersion: String = UniversalAiRequest.currentContractVersion,
        target: UniversalAiTarget,
        displayName: String? = nil,
        limits: UniversalAiModelTokenLimits? = nil,
        capabilities: UniversalAiCapabilitySet = .empty,
        extensions: UniversalAiExtensions = .empty
    ) throws {
        try UniversalAiContractValidation.validateContractVersion(
            contractVersion
        )
        try UniversalAiContractValidation.validateProviderId(
            target.providerId.rawValue,
            path: "/target/providerId"
        )
        try UniversalAiContractValidation.validateModelId(
            target.modelId.rawValue,
            path: "/target/modelId"
        )
        try UniversalAiContractValidation.validateModelDisplayName(displayName)
        self.contractVersion = contractVersion
        self.target = target
        self.displayName = displayName
        self.limits = limits?.isEmpty == true ? nil : limits
        self.capabilities = capabilities
        self.extensions = extensions
    }
}

// MARK: - Canonical request

public struct UniversalAiTarget: Sendable, Equatable {
    public let providerId: UniversalAiProviderId
    public let modelId: UniversalAiModelId

    public init(
        providerId: UniversalAiProviderId,
        modelId: UniversalAiModelId
    ) {
        self.providerId = providerId
        self.modelId = modelId
    }
}

public struct UniversalAiTextInput: Sendable, Equatable {
    public let role: UniversalAiInputRole
    public let content: String

    public init(role: UniversalAiInputRole, content: String) {
        self.role = role
        self.content = content
    }
}

public struct UniversalAiResponseFormat: Sendable, Equatable {
    public let kind: UniversalAiResponseFormatKind
    public let schema: UniversalAiStructuredOutputSchema?

    public init(
        kind: UniversalAiResponseFormatKind,
        schema: UniversalAiStructuredOutputSchema? = nil
    ) {
        self.kind = kind
        self.schema = schema
    }

    public static let plainText = Self(kind: .plainText)

    public static func jsonSchema(
        _ schema: UniversalAiStructuredOutputSchema
    ) -> Self {
        Self(kind: .jsonSchema, schema: schema)
    }
}

public struct UniversalAiGenerationParameters: Sendable, Equatable {
    public let maxOutputTokens: Int?
    public let temperature: Double?
    public let topP: Double?
    public let stopSequences: [String]

    public init(
        maxOutputTokens: Int? = nil,
        temperature: Double? = nil,
        topP: Double? = nil,
        stopSequences: [String] = []
    ) {
        self.maxOutputTokens = maxOutputTokens
        self.temperature = temperature
        self.topP = topP
        self.stopSequences = stopSequences
    }

    public static let `default` = Self()
}

public struct UniversalAiRequest: Sendable, Equatable {
    public static let currentContractVersion = "1"

    public let contractVersion: String
    public let target: UniversalAiTarget
    public let input: [UniversalAiTextInput]
    public let responseFormat: UniversalAiResponseFormat
    public let generation: UniversalAiGenerationParameters
    public let extensions: UniversalAiExtensions

    public init(
        contractVersion: String = Self.currentContractVersion,
        target: UniversalAiTarget,
        input: [UniversalAiTextInput],
        responseFormat: UniversalAiResponseFormat = .plainText,
        generation: UniversalAiGenerationParameters = .default,
        extensions: UniversalAiExtensions = .empty
    ) {
        self.contractVersion = contractVersion
        self.target = target
        self.input = input
        self.responseFormat = responseFormat
        self.generation = generation
        self.extensions = extensions
    }
}

// MARK: - Canonical response and stream envelope

public struct UniversalAiOutput: Sendable, Equatable {
    public let id: UniversalAiOutputId
    public let index: Int
    public let kind: UniversalAiOutputKind
    public let text: String?
    public let structuredJson: UniversalAiStructuredOutputValue?
    public let extensions: UniversalAiExtensions

    public init(
        id: UniversalAiOutputId,
        index: Int,
        kind: UniversalAiOutputKind,
        text: String? = nil,
        structuredJson: UniversalAiStructuredOutputValue? = nil,
        extensions: UniversalAiExtensions = .empty
    ) {
        self.id = id
        self.index = index
        self.kind = kind
        self.text = text
        self.structuredJson = structuredJson
        self.extensions = extensions
    }
}

public struct UniversalAiUsage: Sendable, Equatable {
    public let inputTokens: Int64
    public let outputTokens: Int64
    public let totalTokens: Int64
    public let inputDetails: [String: Int64]
    public let outputDetails: [String: Int64]
    public let extensions: UniversalAiExtensions

    public init(
        inputTokens: Int64,
        outputTokens: Int64,
        totalTokens: Int64,
        inputDetails: [String: Int64] = [:],
        outputDetails: [String: Int64] = [:],
        extensions: UniversalAiExtensions = .empty
    ) {
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
        self.totalTokens = totalTokens
        self.inputDetails = inputDetails
        self.outputDetails = outputDetails
        self.extensions = extensions
    }
}

public struct UniversalAiResponse: Sendable, Equatable {
    public let contractVersion: String
    public let id: UniversalAiResponseId
    public let requestId: UniversalAiRequestId?
    public let target: UniversalAiTarget
    public let outputs: [UniversalAiOutput]
    public let usage: UniversalAiUsage?
    public let completionReason: UniversalAiCompletionReason
    public let extensions: UniversalAiExtensions

    public init(
        contractVersion: String,
        id: UniversalAiResponseId,
        requestId: UniversalAiRequestId? = nil,
        target: UniversalAiTarget,
        outputs: [UniversalAiOutput],
        usage: UniversalAiUsage? = nil,
        completionReason: UniversalAiCompletionReason,
        extensions: UniversalAiExtensions = .empty
    ) {
        self.contractVersion = contractVersion
        self.id = id
        self.requestId = requestId
        self.target = target
        self.outputs = outputs
        self.usage = usage
        self.completionReason = completionReason
        self.extensions = extensions
    }
}

public struct UniversalAiStreamEvent: Sendable, Equatable {
    public let contractVersion: String
    public let type: UniversalAiStreamEventType
    public let terminal: Bool
    public let sequence: Int64
    public let responseId: UniversalAiResponseId
    public let requestId: UniversalAiRequestId?
    public let outputId: UniversalAiOutputId?
    public let outputIndex: Int?
    public let delta: String?
    public let output: UniversalAiOutput?
    public let usage: UniversalAiUsage?
    public let response: UniversalAiResponse?
    public let extensions: UniversalAiExtensions

    public init(
        contractVersion: String,
        type: UniversalAiStreamEventType,
        terminal: Bool,
        sequence: Int64,
        responseId: UniversalAiResponseId,
        requestId: UniversalAiRequestId? = nil,
        outputId: UniversalAiOutputId? = nil,
        outputIndex: Int? = nil,
        delta: String? = nil,
        output: UniversalAiOutput? = nil,
        usage: UniversalAiUsage? = nil,
        response: UniversalAiResponse? = nil,
        extensions: UniversalAiExtensions = .empty
    ) {
        self.contractVersion = contractVersion
        self.type = type
        self.terminal = terminal
        self.sequence = sequence
        self.responseId = responseId
        self.requestId = requestId
        self.outputId = outputId
        self.outputIndex = outputIndex
        self.delta = delta
        self.output = output
        self.usage = usage
        self.response = response
        self.extensions = extensions
    }
}

/// A raw-preserving, product-facing canonical failure.
public struct UniversalAiConnectorError: Error, Sendable, Equatable {
    public let category: UniversalAiErrorCategory
    public let code: UniversalAiErrorCode
    public let message: String
    public let metadata: UniversalAiJsonObject?
    public let extensions: UniversalAiExtensions

    public init(
        category: UniversalAiErrorCategory,
        code: UniversalAiErrorCode,
        message: String,
        metadata: UniversalAiJsonObject? = nil,
        extensions: UniversalAiExtensions = .empty
    ) throws {
        try UniversalAiContractValidation.validateErrorMetadata(metadata)
        try UniversalAiContractValidation.validateErrorMessage(message)
        self.category = category
        self.code = code
        self.message = message
        self.metadata = metadata
        self.extensions = extensions
    }

    internal init(
        trustedCategory category: UniversalAiErrorCategory,
        code: UniversalAiErrorCode,
        message: String,
        metadata: UniversalAiJsonObject? = nil,
        extensions: UniversalAiExtensions = .empty
    ) {
        self.category = category
        self.code = code
        self.message = message
        self.metadata = metadata
        self.extensions = extensions
    }
}
