import Foundation

/// Serializes continuation, callback, and cancellation races for one response.
final class LockedOperationState<Value: Sendable>: @unchecked Sendable {
    typealias CancellationAction = @Sendable () -> Void
    typealias TerminationAction = @Sendable () -> Void

    private let lock = NSLock()
    private let terminationAction: TerminationAction
    private var continuation: CheckedContinuation<Value, Error>?
    private var cancellationAction: CancellationAction?
    private var completion: Result<Value, Error>?
    private var cancellationActionInstalled = false
    private var underlyingCancellationRequested = false
    private var finished = false

    init(onTermination: @escaping TerminationAction = {}) {
        terminationAction = onTermination
    }

    var isActive: Bool {
        lock.lock()
        let isActive = !finished
        lock.unlock()
        return isActive
    }

    @discardableResult
    func installContinuation(
        _ continuation: CheckedContinuation<Value, Error>
    ) -> Bool {
        let pendingCompletion: Result<Value, Error>?
        let installed: Bool

        lock.lock()
        if finished {
            pendingCompletion = completion
            installed = false
        } else {
            self.continuation = continuation
            pendingCompletion = nil
            installed = true
        }
        lock.unlock()

        if let pendingCompletion {
            continuation.resume(with: pendingCompletion)
        }
        return installed
    }

    /// Installs cancellation after the Kotlin call returns its handle.
    ///
    /// Cancellation can race ahead of installation. In that case the action is
    /// invoked exactly once as soon as it becomes available.
    func installCancellation(_ action: @escaping CancellationAction) {
        let shouldCancel: Bool

        lock.lock()
        if cancellationActionInstalled {
            shouldCancel = false
        } else if underlyingCancellationRequested {
            cancellationActionInstalled = true
            shouldCancel = true
        } else if finished {
            cancellationActionInstalled = true
            shouldCancel = false
        } else {
            cancellationActionInstalled = true
            cancellationAction = action
            shouldCancel = false
        }
        lock.unlock()

        if shouldCancel {
            action()
        }
    }

    func succeed(_ value: Value) {
        finish(with: .success(value))
    }

    func fail(_ error: Error) {
        finish(with: .failure(error))
    }

    func cancel() {
        finish(
            with: .failure(CancellationError()),
            cancellingUnderlyingOperation: true
        )
    }

    private func finish(
        with result: Result<Value, Error>,
        cancellingUnderlyingOperation: Bool = false
    ) {
        let continuation: CheckedContinuation<Value, Error>?
        let cancellationAction: CancellationAction?

        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }

        finished = true
        completion = result
        underlyingCancellationRequested = cancellingUnderlyingOperation
        continuation = self.continuation
        self.continuation = nil
        cancellationAction =
            cancellingUnderlyingOperation ? self.cancellationAction : nil
        self.cancellationAction = nil
        lock.unlock()

        cancellationAction?()
        continuation?.resume(with: result)
        terminationAction()
    }
}
