import Dispatch
import Foundation
import XCTest
@testable import UniversalAiConnector

final class UniversalAiConnectorTests: XCTestCase {
    func testProductFrameworkImportsAndReportsVersion() {
        let connector = UniversalAiConnector()

        XCTAssertEqual(connector.version, "0.1.0-alpha.1")
    }

    func testAsyncResponseReturnsSharedValue() async throws {
        let connector = UniversalAiConnector()

        let response = try await connector.respond(to: " hello ")

        XCTAssertEqual(response, "Kotlin echo: hello")
    }

    func testInvalidResponseMapsToStableSwiftError() async {
        let connector = UniversalAiConnector()

        do {
            _ = try await connector.respond(to: "   ")
            XCTFail("Expected invalid input error.")
        } catch let error as UniversalAiConnectorError {
            XCTAssertEqual(error, .invalidInput)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testForcedResponseFailurePreservesStableCodeAndMessage() async {
        let connector = UniversalAiConnector()

        do {
            _ = try await connector.respond(to: "__force_error__")
            XCTFail("Expected simulated failure.")
        } catch let error as UniversalAiConnectorError {
            XCTAssertEqual(
                error,
                .simulatedFailure(
                    code: "simulated_failure",
                    message: "The Universal AI Connector produced the requested simulated failure."
                )
            )
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testStreamEmitsOrderedEvents() async throws {
        let connector = UniversalAiConnector()
        var events: [UniversalAiStreamEvent] = []

        for try await event in connector.stream(input: "chunk") {
            events.append(event)
        }

        XCTAssertEqual(events.map(\.sequence), [1, 2, 3, 4, 5])
        XCTAssertEqual(
            events.map(\.text),
            ["chunk 1", "chunk 2", "chunk 3", "chunk 4", "chunk 5"]
        )
    }

    func testStreamFailureMapsToStableSwiftError() async {
        let connector = UniversalAiConnector()

        do {
            for try await _ in connector.stream(input: "__force_error__") {
                XCTFail("A failing stream must not emit events.")
            }
            XCTFail("Expected simulated stream failure.")
        } catch let error as UniversalAiConnectorError {
            XCTAssertEqual(
                error,
                .simulatedFailure(
                    code: "simulated_failure",
                    message: "The Universal AI Connector produced the requested simulated failure."
                )
            )
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testCancellingConsumingTaskCancelsRetainedStreamExactlyOnce() async throws {
        let iteratorReadCount = LockedCounter()
        let secondReadStarted = AsyncSignal()
        let releaseSecondRead = DispatchSemaphore(value: 0)
        let connector = UniversalAiConnector(
            testingHooks: UniversalAiConnectorTestingHooks(
                beforeStreamIteratorNext: {
                    iteratorReadCount.increment()
                    if iteratorReadCount.value == 2 {
                        secondReadStarted.signal()
                        releaseSecondRead.wait()
                    }
                }
            )
        )
        connector.resetDiagnosticsForTesting()
        let retainedStream = connector.stream(input: "retained")
        let firstEventReceived = AsyncSignal()
        let consumingTask = Task {
            for try await event in retainedStream {
                XCTAssertEqual(event.sequence, 1)
                firstEventReceived.signal()
            }
        }

        await firstEventReceived.wait()
        await secondReadStarted.wait()
        consumingTask.cancel()
        releaseSecondRead.signal()

        do {
            try await consumingTask.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        try await waitForCancellationCount(connector, streams: 1)
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(
            connector.diagnosticsForTesting(),
            UniversalAiConnectorDiagnostics(
                responseCancellations: 0,
                streamCancellations: 1
            )
        )

        // Prove task cancellation is sufficient even while the sequence stays
        // alive; releasing it is not the trigger exercised by this test.
        withExtendedLifetime(retainedStream) {}
    }

    func testParentTaskCancellationCancelsStreamAndThrowsCancellationError() async throws {
        let connector = UniversalAiConnector()
        connector.resetDiagnosticsForTesting()
        let task = Task {
            for try await _ in connector.stream(input: "parent") {
                // Consume until the parent task is cancelled.
            }
        }

        try await Task.sleep(for: .milliseconds(150))
        task.cancel()

        do {
            try await task.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        try await waitForCancellationCount(connector, streams: 1)
        XCTAssertEqual(connector.diagnosticsForTesting().streamCancellations, 1)
    }

    func testUnaryTaskCancellationCancelsResponseAndResumesOnce() async throws {
        let connector = UniversalAiConnector()
        connector.resetDiagnosticsForTesting()
        let task = Task {
            try await connector.respond(to: "cancel")
        }

        try await Task.sleep(for: .milliseconds(30))
        task.cancel()

        do {
            _ = try await task.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        try await waitForCancellationCount(connector, responses: 1)
        XCTAssertEqual(connector.diagnosticsForTesting().responseCancellations, 1)
    }

    func testCancellationBeforeHandleInstallationPropagatesExactlyOnce() async throws {
        let enteredHook = AsyncSignal()
        let releaseHook = DispatchSemaphore(value: 0)
        let connector = UniversalAiConnector(
            testingHooks: UniversalAiConnectorTestingHooks(
                beforeResponseCancellationInstallation: {
                    enteredHook.signal()
                    releaseHook.wait()
                }
            )
        )
        connector.resetDiagnosticsForTesting()

        let task = Task {
            try await connector.respond(to: "installation-race")
        }

        await enteredHook.wait()
        task.cancel()
        releaseHook.signal()

        do {
            _ = try await task.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        try await waitForCancellationCount(connector, responses: 1)
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(connector.diagnosticsForTesting().responseCancellations, 1)
    }

    func testLockedStateIgnoresLateTerminalsAfterPreinstallationCancellation() async {
        let state = LockedOperationState<String>()
        let continuationReady = AsyncSignal()
        let cancellationCount = LockedCounter()
        let task = Task {
            try await withCheckedThrowingContinuation { continuation in
                state.installContinuation(continuation)
                continuationReady.signal()
            }
        }

        await continuationReady.wait()
        state.cancel()
        state.installCancellation {
            cancellationCount.increment()
        }
        state.installCancellation {
            cancellationCount.increment()
        }
        state.succeed("late success")
        state.fail(UniversalAiConnectorError.connectorFailure(message: "late failure"))
        state.cancel()

        do {
            _ = try await task.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        XCTAssertEqual(cancellationCount.value, 1)
    }

    func testLockedStreamStateIgnoresLateTerminalsAfterPreinstallationCancellation() async {
        let state = LockedStreamState<String>()
        let cancellationCount = LockedCounter()
        let stream = AsyncThrowingStream<String, Error> { continuation in
            state.installContinuation(continuation)
        }
        let task = Task {
            var iterator = stream.makeAsyncIterator()
            return try await iterator.next()
        }

        state.cancel()
        state.installCancellation {
            cancellationCount.increment()
        }
        state.installCancellation {
            cancellationCount.increment()
        }
        state.yield("late event")
        state.finish()
        state.fail(UniversalAiConnectorError.connectorFailure(message: "late failure"))
        state.cancel()

        do {
            _ = try await task.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        XCTAssertEqual(cancellationCount.value, 1)
    }

    func testConcurrentResponsesRemainIndependent() async throws {
        let connector = UniversalAiConnector()
        let responses = try await withThrowingTaskGroup(
            of: (Int, String).self,
            returning: [String].self
        ) { group in
            for index in 0..<8 {
                group.addTask {
                    (index, try await connector.respond(to: "request-\(index)"))
                }
            }

            var ordered = Array(repeating: "", count: 8)
            for try await (index, response) in group {
                ordered[index] = response
            }
            return ordered
        }

        XCTAssertEqual(
            responses,
            (0..<8).map { "Kotlin echo: request-\($0)" }
        )
    }

    func testConcurrentStreamsRemainIndependent() async throws {
        let connector = UniversalAiConnector()
        let streams = try await withThrowingTaskGroup(
            of: (Int, [UniversalAiStreamEvent]).self,
            returning: [[UniversalAiStreamEvent]].self
        ) { group in
            for index in 0..<4 {
                group.addTask {
                    var events: [UniversalAiStreamEvent] = []
                    for try await event in connector.stream(input: "stream-\(index)") {
                        events.append(event)
                    }
                    return (index, events)
                }
            }

            var ordered = Array(repeating: [UniversalAiStreamEvent](), count: 4)
            for try await (index, events) in group {
                ordered[index] = events
            }
            return ordered
        }

        for index in 0..<4 {
            XCTAssertEqual(streams[index].map(\.sequence), [1, 2, 3, 4, 5])
            XCTAssertEqual(
                streams[index].map(\.text),
                (1...5).map { "stream-\(index) \($0)" }
            )
        }
    }

    func testRepeatedCreationAndReleaseCompletesWithoutLateCallbacks() async throws {
        for index in 0..<12 {
            weak var releasedConnector: UniversalAiConnector?

            do {
                let connector = UniversalAiConnector()
                releasedConnector = connector
                let response = try await connector.respond(to: "release-\(index)")
                XCTAssertEqual(response, "Kotlin echo: release-\(index)")
            }

            XCTAssertNil(releasedConnector)
        }

        // Keep the test process alive beyond both deterministic callback delays.
        // A late second terminal would violate the checked-continuation contract.
        try await Task.sleep(for: .milliseconds(250))
    }

    private func waitForCancellationCount(
        _ connector: UniversalAiConnector,
        responses: Int? = nil,
        streams: Int? = nil
    ) async throws {
        for _ in 0..<40 {
            let diagnostics = connector.diagnosticsForTesting()
            let responseMatches = responses.map {
                diagnostics.responseCancellations == $0
            } ?? true
            let streamMatches = streams.map {
                diagnostics.streamCancellations == $0
            } ?? true
            if responseMatches && streamMatches {
                return
            }
            try await Task.sleep(for: .milliseconds(25))
        }

        XCTFail("Timed out waiting for cancellation diagnostics.")
    }
}

private final class LockedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int {
        lock.withLock { count }
    }

    func increment() {
        lock.withLock {
            count += 1
        }
    }
}

private final class AsyncSignal: @unchecked Sendable {
    private let lock = NSLock()
    private var signalled = false
    private var continuation: CheckedContinuation<Void, Never>?

    func signal() {
        let continuation: CheckedContinuation<Void, Never>?

        lock.lock()
        if let waitingContinuation = self.continuation {
            continuation = waitingContinuation
            self.continuation = nil
        } else {
            signalled = true
            continuation = nil
        }
        lock.unlock()

        continuation?.resume()
    }

    func wait() async {
        await withCheckedContinuation { continuation in
            let shouldResume: Bool

            lock.lock()
            if signalled {
                signalled = false
                shouldResume = true
            } else {
                self.continuation = continuation
                shouldResume = false
            }
            lock.unlock()

            if shouldResume {
                continuation.resume()
            }
        }
    }
}
