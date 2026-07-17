import XCTest
@testable import UniversalAiConnectorPOC

final class UniversalAiConnectorPOCTests: XCTestCase {
    private var connector: UniversalAiConnectorPOC!

    override func setUp() {
        super.setUp()
        connector = UniversalAiConnectorPOC()
        connector.resetDiagnostics()
    }

    func testXCFrameworkImportsAndSynchronousCallWorks() {
        XCTAssertEqual(connector.version, "universal-ai-connector-poc/0.1.0")
        XCTAssertEqual(
            connector.greeting(name: "Xcode"),
            "Hello, Xcode, from Kotlin."
        )
    }

    func testAsyncResponseReturnsKotlinValue() async throws {
        let response = try await connector.respond(to: "hello")
        XCTAssertEqual(response, "Kotlin echo: hello")
    }

    func testInvalidInputMapsToStableSwiftError() async {
        do {
            _ = try await connector.respond(to: "   ")
            XCTFail("Expected invalid input error.")
        } catch let error as ConnectorError {
            XCTAssertEqual(error, .invalidInput)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testForcedFailurePreservesStableCodeAndMessage() async {
        do {
            _ = try await connector.respond(to: "__force_error__")
            XCTFail("Expected simulated failure.")
        } catch let error as ConnectorError {
            XCTAssertEqual(
                error,
                .simulatedFailure(
                    code: "simulated_failure",
                    message: "The Kotlin POC produced the requested simulated failure."
                )
            )
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testStreamEmitsOrderedEvents() async throws {
        var events: [ConnectorStreamEvent] = []

        for try await event in connector.stream(input: "chunk") {
            events.append(event)
        }

        XCTAssertEqual(events.map(\.sequence), [1, 2, 3, 4, 5])
        XCTAssertEqual(
            events.map(\.text),
            ["chunk 1", "chunk 2", "chunk 3", "chunk 4", "chunk 5"]
        )
    }

    func testEarlyStreamTerminationCancelsKotlin() async throws {
        do {
            let stream = connector.stream(input: "early")
            for try await _ in stream {
                break
            }
        }

        try await waitForCancellationCount(stream: 1)
        XCTAssertEqual(connector.diagnostics().streamCancellations, 1)
    }

    func testParentTaskCancellationCancelsKotlinAndThrowsCancellationError() async throws {
        let connector = try XCTUnwrap(connector)
        let task = Task {
            for try await _ in connector.stream(input: "task") {
                // Keep consuming until the parent task is cancelled.
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

        try await waitForCancellationCount(stream: 1)
        XCTAssertEqual(connector.diagnostics().streamCancellations, 1)
    }

    func testUnaryTaskCancellationCancelsKotlinAndResumesOnlyOnce() async throws {
        let connector = try XCTUnwrap(connector)
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

        try await waitForCancellationCount(unary: 1)
        XCTAssertEqual(connector.diagnostics().unaryCancellations, 1)
    }

    private func waitForCancellationCount(
        unary: Int? = nil,
        stream: Int? = nil
    ) async throws {
        for _ in 0..<40 {
            let diagnostics = connector.diagnostics()
            let unaryMatches = unary.map { diagnostics.unaryCancellations == $0 } ?? true
            let streamMatches = stream.map { diagnostics.streamCancellations == $0 } ?? true
            if unaryMatches && streamMatches {
                return
            }
            try await Task.sleep(for: .milliseconds(25))
        }
    }
}
