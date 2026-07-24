import Foundation

/// Serializes connector close and active-operation registration races.
final class LockedConnectorLifecycle: @unchecked Sendable {
    typealias CloseAction = @Sendable () -> Void

    private let lock = NSLock()
    private var operations: [UUID: CloseAction] = [:]
    private var closed = false

    var isOpen: Bool {
        lock.lock()
        let isOpen = !closed
        lock.unlock()
        return isOpen
    }

    /// Registers an operation unless close has already begun.
    func register(
        _ identifier: UUID,
        onClose: @escaping CloseAction
    ) -> Bool {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return false
        }
        precondition(
            operations[identifier] == nil,
            "An operation identifier must be registered only once."
        )
        operations[identifier] = onClose
        lock.unlock()
        return true
    }

    func unregister(_ identifier: UUID) {
        lock.lock()
        operations.removeValue(forKey: identifier)
        lock.unlock()
    }

    /// Marks the connector closed and closes every registered operation once.
    @discardableResult
    func close() -> Bool {
        let closeActions: [CloseAction]

        lock.lock()
        guard !closed else {
            lock.unlock()
            return false
        }
        closed = true
        closeActions = Array(operations.values)
        operations.removeAll(keepingCapacity: false)
        lock.unlock()

        closeActions.forEach { action in
            action()
        }
        return true
    }
}
