import Foundation
import UniversalAiConnectorBridge

final class LockedOperationState<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Error>?
    private var handle: PocCancellationHandle?
    private var completion: Result<Value, Error>?
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

    func installHandle(_ handle: PocCancellationHandle) {
        let shouldCancel: Bool

        lock.lock()
        if finished || cancellationRequested {
            shouldCancel = true
        } else {
            self.handle = handle
            shouldCancel = false
        }
        lock.unlock()

        if shouldCancel {
            handle.cancel()
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
        let handle: PocCancellationHandle?

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
        handle = self.handle
        self.handle = nil
        lock.unlock()

        handle?.cancel()
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
        handle = nil
        lock.unlock()

        continuation?.resume(with: result)
    }
}
