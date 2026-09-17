import UIKit
import UniversalAiConnector

@objc(ExtensionRequestHandler)
final class ExtensionRequestHandler: UIViewController {
    private let connector = UniversalAiConnector()
    private var verificationTask: Task<Void, Never>?

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        verificationTask = Task { [connector] in
            do {
                let providerId = UniversalAiProviderId(rawValue: "deterministic")
                _ = try await connector.listModels(providerId: providerId)

                let request = UniversalAiRequest(
                    target: UniversalAiTarget(
                        providerId: providerId,
                        modelId: UniversalAiModelId(rawValue: "echo-v1")
                    ),
                    input: [
                        UniversalAiTextInput(
                            role: .user,
                            content: "extension consumer verification"
                        ),
                    ]
                )
                _ = try await connector.respond(to: request)

                for try await event in connector.stream(request: request) {
                    if event.terminal {
                        break
                    }
                }
            } catch is CancellationError {
                return
            } catch {
                assertionFailure("UniversalAiConnector consumer verification failed.")
            }
        }
    }

    deinit {
        verificationTask?.cancel()
        connector.close()
    }
}
