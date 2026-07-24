import Foundation

/// Serializes stream callback, terminal, and cancellation races.
final class LockedStreamState<Element: Sendable>: @unchecked Sendable {
    typealias CancellationAction = @Sendable () -> Void

    private let lock = NSLock()
    private var continuation: AsyncThrowingStream<Element, Error>.Continuation?
    private var cancellationAction: CancellationAction?
    private var terminalElement: Element?
    private var terminalError: Error?
    private var cancellationActionInstalled = false
    private var cancellationRequested = false
    private var finished = false

    func installContinuation(
        _ continuation: AsyncThrowingStream<Element, Error>.Continuation
    ) {
        let isFinished: Bool
        let terminalElement: Element?
        let terminalError: Error?

        lock.lock()
        isFinished = finished
        terminalElement = self.terminalElement
        terminalError = self.terminalError
        if !isFinished {
            self.continuation = continuation
        }
        lock.unlock()

        guard isFinished else {
            return
        }

        if let terminalElement {
            continuation.yield(terminalElement)
            continuation.finish()
        } else if let terminalError {
            continuation.finish(throwing: terminalError)
        } else {
            continuation.finish()
        }
    }

    /// Installs cancellation after the Kotlin call returns its handle.
    ///
    /// If the stream was cancelled first, the action runs exactly once here.
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

    func yield(_ element: Element) {
        let continuation: AsyncThrowingStream<Element, Error>.Continuation?

        lock.lock()
        continuation = finished ? nil : self.continuation
        lock.unlock()

        continuation?.yield(element)
    }

    /// Atomically accepts the explicit semantic terminal element.
    ///
    /// Marking the state finished before yielding prevents cancellation, a late
    /// event, or the adapter's following normal-completion callback from winning
    /// after the terminal event has been observed.
    func yieldTerminal(_ element: Element) {
        let continuation: AsyncThrowingStream<Element, Error>.Continuation?

        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }

        finished = true
        terminalElement = element
        terminalError = nil
        continuation = self.continuation
        self.continuation = nil
        cancellationAction = nil
        lock.unlock()

        continuation?.yield(element)
        continuation?.finish()
    }

    func finish() {
        terminate(throwing: nil, cancellationRequested: false)
    }

    func fail(_ error: Error) {
        terminate(throwing: error, cancellationRequested: false)
    }

    func cancel() {
        terminate(
            throwing: CancellationError(),
            cancellationRequested: true
        )
    }

    private func terminate(
        throwing error: Error?,
        cancellationRequested: Bool
    ) {
        let continuation: AsyncThrowingStream<Element, Error>.Continuation?
        let cancellationAction: CancellationAction?

        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }

        finished = true
        terminalError = error
        self.cancellationRequested = cancellationRequested
        continuation = self.continuation
        self.continuation = nil
        cancellationAction = cancellationRequested ? self.cancellationAction : nil
        self.cancellationAction = nil
        lock.unlock()

        cancellationAction?()
        if let error {
            continuation?.finish(throwing: error)
        } else {
            continuation?.finish()
        }
    }
}
