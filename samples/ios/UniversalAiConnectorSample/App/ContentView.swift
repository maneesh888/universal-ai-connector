import SwiftUI
import UniversalAiConnector

struct ContentView: View {
    @StateObject private var viewModel = UniversalAiConnectorViewModel()

    var body: some View {
        NavigationStack {
            List {
                Section("Package") {
                    statusRow("Version", value: viewModel.version)
                }

                Section("Response") {
                    Button("Run response") {
                        viewModel.runResponse()
                    }
                    .disabled(viewModel.isResponseRunning)

                    statusRow("Result", value: viewModel.responseResult)
                }

                Section("Streaming") {
                    Button("Run stream") {
                        viewModel.runStream()
                    }
                    .disabled(viewModel.isStreamRunning)

                    statusRow("Events", value: viewModel.streamResult)
                }

                Section("Stable error") {
                    Button("Force error") {
                        viewModel.runForcedError()
                    }
                    .disabled(viewModel.isForcedErrorRunning)

                    statusRow("Result", value: viewModel.forcedErrorResult)
                }

                Section("Response cancellation") {
                    Button("Run response cancellation") {
                        viewModel.runResponseCancellation()
                    }
                    .disabled(viewModel.isResponseCancellationRunning)

                    statusRow("Result", value: viewModel.responseCancellationResult)
                }

                Section("Stream cancellation") {
                    Button("Stop stream after first event") {
                        viewModel.runStreamCancellation()
                    }
                    .disabled(viewModel.isStreamCancellationRunning)

                    statusRow("Events", value: viewModel.streamCancellationResult)
                }
            }
            .navigationTitle("Universal AI Connector")
            .onDisappear {
                viewModel.cancelAll()
            }
        }
    }

    private func statusRow(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.headline)
            Text(value)
                .font(.callout.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
        }
        .padding(.vertical, 4)
    }
}

@MainActor
final class UniversalAiConnectorViewModel: ObservableObject {
    @Published private(set) var version: String
    @Published private(set) var responseResult = "Ready"
    @Published private(set) var streamResult = "Ready"
    @Published private(set) var forcedErrorResult = "Ready"
    @Published private(set) var responseCancellationResult = "Ready"
    @Published private(set) var streamCancellationResult = "Ready"

    @Published private(set) var isResponseRunning = false
    @Published private(set) var isStreamRunning = false
    @Published private(set) var isForcedErrorRunning = false
    @Published private(set) var isResponseCancellationRunning = false
    @Published private(set) var isStreamCancellationRunning = false

    private let connector: UniversalAiConnector
    private var responseTask: Task<Void, Never>?
    private var streamTask: Task<Void, Never>?
    private var forcedErrorTask: Task<Void, Never>?
    private var responseCancellationTask: Task<Void, Never>?
    private var streamCancellationTask: Task<Void, Never>?

    init(connector: UniversalAiConnector = UniversalAiConnector()) {
        self.connector = connector
        version = connector.version
    }

    func runResponse() {
        guard !isResponseRunning else {
            return
        }

        isResponseRunning = true
        responseResult = "Running…"
        responseTask = Task { [weak self] in
            guard let self else {
                return
            }
            defer {
                isResponseRunning = false
                responseTask = nil
            }

            do {
                responseResult = try await connector.respond(to: "hello from SwiftUI")
            } catch is CancellationError {
                responseResult = "Cancelled with CancellationError."
            } catch {
                responseResult = "Failed: \(errorText(error))"
            }
        }
    }

    func runStream() {
        guard !isStreamRunning else {
            return
        }

        isStreamRunning = true
        streamResult = "Waiting for event 1…"
        streamTask = Task { [weak self] in
            guard let self else {
                return
            }
            defer {
                isStreamRunning = false
                streamTask = nil
            }

            do {
                var events: [String] = []
                for try await event in connector.stream(input: "chunk") {
                    events.append("\(event.sequence): \(event.text)")
                    streamResult = events.joined(separator: " | ")
                }
            } catch is CancellationError {
                streamResult = "Cancelled with CancellationError."
            } catch {
                streamResult = "Failed: \(errorText(error))"
            }
        }
    }

    func runForcedError() {
        guard !isForcedErrorRunning else {
            return
        }

        isForcedErrorRunning = true
        forcedErrorResult = "Running…"
        forcedErrorTask = Task { [weak self] in
            guard let self else {
                return
            }
            defer {
                isForcedErrorRunning = false
                forcedErrorTask = nil
            }

            do {
                let response = try await connector.respond(to: "__force_error__")
                forcedErrorResult = "Unexpected success: \(response)"
            } catch is CancellationError {
                forcedErrorResult = "Cancelled with CancellationError."
            } catch {
                forcedErrorResult = errorText(error)
            }
        }
    }

    func runResponseCancellation() {
        guard !isResponseCancellationRunning else {
            return
        }

        isResponseCancellationRunning = true
        responseCancellationResult = "Cancelling before completion…"
        responseCancellationTask = Task { [weak self] in
            guard let self else {
                return
            }
            defer {
                isResponseCancellationRunning = false
                responseCancellationTask = nil
            }

            let request = Task {
                try await self.connector.respond(to: "cancel this response")
            }
            request.cancel()

            do {
                let response = try await request.value
                responseCancellationResult = "Unexpected success: \(response)"
            } catch is CancellationError {
                responseCancellationResult = "Cancelled with CancellationError."
            } catch {
                responseCancellationResult = "Failed: \(errorText(error))"
            }
        }
    }

    func runStreamCancellation() {
        guard !isStreamCancellationRunning else {
            return
        }

        isStreamCancellationRunning = true
        streamCancellationResult = "Waiting for event 1…"
        streamCancellationTask = Task { [weak self] in
            guard let self else {
                return
            }
            defer {
                isStreamCancellationRunning = false
                streamCancellationTask = nil
            }

            do {
                var events: [String] = []
                for try await event in connector.stream(input: "cancel stream") {
                    events.append("\(event.sequence): \(event.text)")
                    break
                }
                streamCancellationResult =
                    "Stopped after " + events.joined(separator: " | ")
            } catch is CancellationError {
                streamCancellationResult = "Cancelled with CancellationError."
            } catch {
                streamCancellationResult = "Failed: \(errorText(error))"
            }
        }
    }

    func cancelAll() {
        responseTask?.cancel()
        streamTask?.cancel()
        forcedErrorTask?.cancel()
        responseCancellationTask?.cancel()
        streamCancellationTask?.cancel()
    }

    private func errorText(_ error: Error) -> String {
        guard let connectorError = error as? UniversalAiConnectorError else {
            return String(describing: error)
        }

        switch connectorError {
        case .invalidInput:
            return "invalid_input"
        case let .simulatedFailure(code, message):
            return "\(code): \(message)"
        case let .connectorFailure(message):
            return "connector_failure: \(message)"
        }
    }
}
