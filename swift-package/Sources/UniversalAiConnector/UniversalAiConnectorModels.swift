/// One ordered event emitted by ``UniversalAiConnector/stream(input:)``.
public struct UniversalAiStreamEvent: Sendable, Equatable {
    public let sequence: Int
    public let text: String

    public init(sequence: Int, text: String) {
        self.sequence = sequence
        self.text = text
    }
}

/// Stable, product-facing errors emitted by ``UniversalAiConnector``.
public enum UniversalAiConnectorError: Error, Sendable, Equatable {
    case invalidInput
    case simulatedFailure(code: String, message: String)
    case connectorFailure(message: String)
}
