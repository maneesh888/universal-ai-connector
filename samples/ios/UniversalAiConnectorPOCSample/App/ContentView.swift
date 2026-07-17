import SwiftUI
import UniversalAiConnectorPOC

struct ContentView: View {
    @StateObject private var viewModel = POCViewModel()

    var body: some View {
        NavigationStack {
            List {
                statusRow("Framework", value: viewModel.version)
                statusRow("Unary", value: viewModel.unaryResult)
                statusRow("Stream", value: viewModel.streamResult)
                statusRow("Error", value: viewModel.errorResult)
                statusRow("Cancellation", value: viewModel.cancellationResult)
            }
            .navigationTitle("Kotlin Bridge POC")
            .task {
                await viewModel.run()
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
final class POCViewModel: ObservableObject {
    @Published private(set) var version = "Waiting…"
    @Published private(set) var unaryResult = "Waiting…"
    @Published private(set) var streamResult = "Waiting…"
    @Published private(set) var errorResult = "Waiting…"
    @Published private(set) var cancellationResult = "Waiting…"

    private let connector = UniversalAiConnectorPOC()
    private var didRun = false

    func run() async {
        guard !didRun else {
            return
        }
        didRun = true
        connector.resetDiagnostics()

        version = connector.version

        do {
            unaryResult = try await connector.respond(to: "hello from Xcode")
        } catch {
            unaryResult = "Failed: \(error)"
        }

        do {
            var chunks: [String] = []
            streamResult = "Waiting for first event…"
            for try await event in connector.stream(input: "chunk") {
                chunks.append(event.text)
                streamResult = chunks.joined(separator: " | ")
            }
        } catch {
            streamResult = "Failed: \(error)"
        }

        do {
            _ = try await connector.respond(to: "__force_error__")
            errorResult = "Unexpected success"
        } catch {
            errorResult = String(describing: error)
        }

        let cancellationTask = Task {
            try await connector.respond(to: "cancel me")
        }
        try? await Task.sleep(for: .milliseconds(30))
        cancellationTask.cancel()

        do {
            _ = try await cancellationTask.value
            cancellationResult = "Unexpected success"
        } catch is CancellationError {
            for _ in 0..<20 where connector.diagnostics().unaryCancellations == 0 {
                try? await Task.sleep(for: .milliseconds(25))
            }
            cancellationResult =
                "Swift CancellationError; Kotlin cancellations: " +
                "\(connector.diagnostics().unaryCancellations)"
        } catch {
            cancellationResult = "Unexpected error: \(error)"
        }
    }
}
