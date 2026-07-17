import Foundation
import UniversalAiConnectorBridge

final class LockedCancellationHandle: @unchecked Sendable {
    private let lock = NSLock()
    private var handle: PocCancellationHandle?
    private var cancellationRequested = false

    func install(_ handle: PocCancellationHandle) {
        let shouldCancel: Bool

        lock.lock()
        if cancellationRequested {
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

    func cancel() {
        let handle: PocCancellationHandle?

        lock.lock()
        guard !cancellationRequested else {
            lock.unlock()
            return
        }

        cancellationRequested = true
        handle = self.handle
        self.handle = nil
        lock.unlock()

        handle?.cancel()
    }
}
