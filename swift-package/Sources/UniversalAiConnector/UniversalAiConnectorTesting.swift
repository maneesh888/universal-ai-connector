import UniversalAiConnectorBridge

protocol UniversalAiModelListOperation: Sendable {
    func start(
        adapterName: String,
        onSuccess: @escaping (AppleBridgeModelListResult) -> Void,
        onError: @escaping (AppleBridgeError) -> Void,
        onCancelled: @escaping () -> Void
    ) -> UniversalAiModelListCancellation
}

final class AppleBridgeModelListOperation:
    UniversalAiModelListOperation,
    @unchecked Sendable
{
    private let bridge: AppleConnectorBridge

    init(bridge: AppleConnectorBridge) {
        self.bridge = bridge
    }

    func start(
        adapterName: String,
        onSuccess: @escaping (AppleBridgeModelListResult) -> Void,
        onError: @escaping (AppleBridgeError) -> Void,
        onCancelled: @escaping () -> Void
    ) -> UniversalAiModelListCancellation {
        let handle = bridge.listModels(
            adapterName: adapterName,
            onSuccess: onSuccess,
            onError: onError,
            onCancelled: onCancelled
        )
        let handleBox = AppleCancellationHandleBox(handle)
        return UniversalAiModelListCancellation {
            handleBox.cancel()
        }
    }
}

final class UniversalAiModelListCancellation: @unchecked Sendable {
    private let action: () -> Void

    init(_ action: @escaping () -> Void) {
        self.action = action
    }

    func cancel() {
        action()
    }
}

struct UniversalAiConnectorTestingHooks: Sendable {
    var beforeResponseCancellationInstallation: @Sendable () -> Void
    var beforeStreamCancellationInstallation: @Sendable () -> Void
    var onStreamCancellationRequested: @Sendable () -> Void

    init(
        beforeResponseCancellationInstallation: @escaping @Sendable () -> Void = {},
        beforeStreamCancellationInstallation: @escaping @Sendable () -> Void = {},
        onStreamCancellationRequested: @escaping @Sendable () -> Void = {}
    ) {
        self.beforeResponseCancellationInstallation =
            beforeResponseCancellationInstallation
        self.beforeStreamCancellationInstallation =
            beforeStreamCancellationInstallation
        self.onStreamCancellationRequested = onStreamCancellationRequested
    }
}

struct UniversalAiConnectorDiagnostics: Sendable, Equatable {
    let responseCancellations: Int
    let streamCancellations: Int
}

final class AppleCancellationHandleBox: @unchecked Sendable {
    private let handle: AppleCancellationHandle

    init(_ handle: AppleCancellationHandle) {
        self.handle = handle
    }

    func cancel() {
        handle.cancel()
    }
}
