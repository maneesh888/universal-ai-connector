import SwiftUI
import UniversalAiConnector

@main
struct ReleaseConsumerApp: App {
    private var version: String {
        let client = UniversalAiConnector()
        defer { client.close() }
        return client.version
    }
    var body: some Scene {
        WindowGroup { Text(version) }
    }
}
