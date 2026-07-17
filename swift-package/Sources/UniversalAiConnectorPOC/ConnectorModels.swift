import Foundation

public struct ConnectorStreamEvent: Sendable, Equatable {
    public let sequence: Int
    public let text: String

    public init(sequence: Int, text: String) {
        self.sequence = sequence
        self.text = text
    }
}

public struct ConnectorDiagnostics: Sendable, Equatable {
    public let unaryCancellations: Int
    public let streamCancellations: Int

    public init(unaryCancellations: Int, streamCancellations: Int) {
        self.unaryCancellations = unaryCancellations
        self.streamCancellations = streamCancellations
    }
}

public enum ConnectorError: Error, Sendable, Equatable {
    case invalidInput
    case simulatedFailure(code: String, message: String)
    case bridgeFailure(message: String)
}
