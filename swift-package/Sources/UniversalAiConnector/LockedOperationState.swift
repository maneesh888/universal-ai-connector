import Foundation

/// Serializes continuation, callback, and cancellation races for one response.
final class LockedOperationState<Value: Sendable>: @unchecked Sendable {
    typealias CancellationAction = @Sendable () -> Void

    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Error>?
    private var cancellationAction: CancellationAction?
    private var completion: Result<Value, Error>?
    private var cancellationActionInstalled = false
    private var cancellationRequested = false
    private var finished = false

    func installContinuation(_ continuation: CheckedContinuation<Value, Error>) {
        let pendingCompletion: Result<Value, Error>?

        lock.lock()
        if finished {
            pendingCompletion = completion
        } else if cancellationRequested {
            finished = true
            completion = .failure(CancellationError())
            pendingCompletion = completion
        } else {
            self.continuation = continuation
            pendingCompletion = nil
        }
        lock.unlock()

        if let pendingCompletion {
            continuation.resume(with: pendingCompletion)
        }
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
        } else if cancellationRequested {
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
        let continuation: CheckedContinuation<Value, Error>?
        let cancellationAction: CancellationAction?

        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }

        cancellationRequested = true
        finished = true
        completion = .failure(CancellationError())
        continuation = self.continuation
        self.continuation = nil
        cancellationAction = self.cancellationAction
        self.cancellationAction = nil
        lock.unlock()

        cancellationAction?()
        continuation?.resume(throwing: CancellationError())
    }

    private func finish(with result: Result<Value, Error>) {
        let continuation: CheckedContinuation<Value, Error>?

        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }

        finished = true
        completion = result
        continuation = self.continuation
        self.continuation = nil
        cancellationAction = nil
        lock.unlock()

        continuation?.resume(with: result)
    }
}
