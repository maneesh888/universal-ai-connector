import UniversalAiConnectorBridge

struct UniversalAiConnectorTestingHooks: Sendable {
    var beforeResponseCancellationInstallation: @Sendable () -> Void
    var beforeStreamCancellationInstallation: @Sendable () -> Void
    var beforeStreamIteratorNext: @Sendable () -> Void

    init(
        beforeResponseCancellationInstallation: @escaping @Sendable () -> Void = {},
        beforeStreamCancellationInstallation: @escaping @Sendable () -> Void = {},
        beforeStreamIteratorNext: @escaping @Sendable () -> Void = {}
    ) {
        self.beforeResponseCancellationInstallation =
            beforeResponseCancellationInstallation
        self.beforeStreamCancellationInstallation =
            beforeStreamCancellationInstallation
        self.beforeStreamIteratorNext = beforeStreamIteratorNext
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
