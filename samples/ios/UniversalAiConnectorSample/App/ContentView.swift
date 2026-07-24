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
                    Button("Cancel stream task after first output delta") {
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
                let response = try await connector.respond(
                    to: canonicalRequest("hello from SwiftUI")
                )
                responseResult = responseText(response)
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
                for try await event in connector.stream(
                    request: canonicalRequest("chunk")
                ) {
                    events.append(eventText(event))
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
                let response = try await connector.respond(
                    to: canonicalRequest("__force_error__")
                )
                forcedErrorResult =
                    "Unexpected success: \(responseText(response))"
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

            let requestTask = Task {
                try await self.connector.respond(
                    to: self.canonicalRequest("cancel this response")
                )
            }
            requestTask.cancel()

            do {
                let response = try await requestTask.value
                responseCancellationResult =
                    "Unexpected success: \(responseText(response))"
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
        streamCancellationResult = "Waiting for first output delta…"
        streamCancellationTask = Task { [weak self] in
            guard let self else {
                return
            }
            defer {
                isStreamCancellationRunning = false
                streamCancellationTask = nil
            }

            let (firstDeltas, firstDeltaContinuation) =
                AsyncStream.makeStream(of: UniversalAiStreamEvent.self)
            let consumingTask = Task {
                defer {
                    firstDeltaContinuation.finish()
                }

                var isFirstDelta = true
                for try await event in connector.stream(
                    request: canonicalRequest("cancel stream")
                ) {
                    if isFirstDelta && event.type == .outputDelta {
                        isFirstDelta = false
                        firstDeltaContinuation.yield(event)
                        firstDeltaContinuation.finish()
                    }
                }
            }

            do {
                try await withTaskCancellationHandler {
                    var firstDeltaIterator = firstDeltas.makeAsyncIterator()
                    guard let event = await firstDeltaIterator.next() else {
                        try Task.checkCancellation()
                        try await consumingTask.value
                        streamCancellationResult = "Unexpected stream completion."
                        return
                    }

                    streamCancellationResult =
                        "Received \(eventText(event))"
                        + "; cancelling the consuming task…"
                    consumingTask.cancel()
                    try await consumingTask.value
                    streamCancellationResult = "Unexpected stream completion."
                } onCancel: {
                    consumingTask.cancel()
                    firstDeltaContinuation.finish()
                }
            } catch is CancellationError {
                if streamCancellationResult.hasPrefix("Received ") {
                    streamCancellationResult +=
                        " Cancellation completed with CancellationError."
                } else {
                    streamCancellationResult = "Cancelled with CancellationError."
                }
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

        return "\(connectorError.category.rawValue)/"
            + "\(connectorError.code.rawValue): \(connectorError.message)"
    }

    private func canonicalRequest(_ content: String) -> UniversalAiRequest {
        UniversalAiRequest(
            target: UniversalAiTarget(
                providerId: UniversalAiProviderId(rawValue: "deterministic"),
                modelId: UniversalAiModelId(rawValue: "echo-v1")
            ),
            input: [
                UniversalAiTextInput(role: .user, content: content),
            ]
        )
    }

    private func responseText(_ response: UniversalAiResponse) -> String {
        response.outputs.first?.text ?? "No text output."
    }

    private func eventText(_ event: UniversalAiStreamEvent) -> String {
        var fields = ["\(event.sequence): \(event.type.rawValue)"]
        if let delta = event.delta {
            fields.append("delta=\(delta)")
        }
        if let output = event.output {
            fields.append("output=\(output.text ?? output.kind.rawValue)")
        }
        if let response = event.response {
            fields.append("response=\(responseText(response))")
        }
        if event.terminal {
            fields.append("terminal=true")
        }
        return fields.joined(separator: " · ")
    }
}
