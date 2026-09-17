import Darwin
import Foundation
import SwiftUI

@main
struct UniversalAiConnectorSampleApp: App {
    @StateObject private var liveViewModel: LiveAiConfigurationViewModel

    init() {
        #if DEBUG && targetEnvironment(simulator)
            let proofSeed = LiveAiProofSeed.fromEnvironment()
            _liveViewModel = StateObject(
                wrappedValue: LiveAiConfigurationViewModel(proofSeed: proofSeed)
            )
            Self.clearProofEnvironment()
        #else
            _liveViewModel = StateObject(
                wrappedValue: LiveAiConfigurationViewModel()
            )
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ContentView(liveViewModel: liveViewModel)
        }
    }

    private static func clearProofEnvironment() {
        let names = [
            "UAC_IOS_SAMPLE_LIVE_BOOTSTRAP",
            "UAC_IOS_SAMPLE_LIVE_PROVIDER",
            "UAC_IOS_SAMPLE_LIVE_CREDENTIAL",
            "UAC_IOS_SAMPLE_LIVE_MODEL",
            "UAC_IOS_SAMPLE_LIVE_BASE_URL",
        ]
        for name in names {
            unsetenv(name)
        }
    }
}
