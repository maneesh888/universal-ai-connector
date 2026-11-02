import XCTest
@testable import ReleaseConsumer

final class ReleaseConsumerTests: XCTestCase {
    func testReleasedPublicClientConstructsReportsVersionAndCloses() {
        XCTAssertEqual(candidateVersion(), ExpectedRelease.version)
    }
}
