# Universal AI Connector

Universal AI Connector is an independent Kotlin Multiplatform project for exposing one provider-neutral AI client API to Kotlin, Android, JVM, and Swift applications.

The repository is currently at the interoperability proof-of-concept stage. It proves that a Swift application can call Kotlin/Native code through an XCFramework, receive asynchronous and streaming results, map stable errors, and propagate Swift task cancellation into Kotlin coroutines.

No AI provider, gateway, API key, or network integration is implemented yet.

## Current status

| Area | Status |
|---|---|
| Kotlin/Native iOS Simulator framework | Verified |
| XCFramework generation | Verified |
| Local Swift Package wrapper | Verified |
| Swift async response bridge | Verified |
| Kotlin `Flow` to Swift `AsyncThrowingStream` | Verified |
| Swift-to-Kotlin cancellation | Verified |
| Interactive iOS sample build | Verified |
| iOS device, Android, and JVM targets | Planned |
| Canonical AI contracts and HTTP transport | Planned |
| OpenAI, Anthropic, OpenRouter, and gateway adapters | Planned |

The complete local verification suite passed on July 17, 2026 with 6 Kotlin tests and 8 Swift integration tests.

## Architecture direction

Applications will consume Universal AI Connector models rather than provider DTOs:

```text
Application
    -> Universal AI Connector client
    -> provider adapter
    -> provider or Universal Gateway
    -> canonical response/error/stream events
```

The current Apple delivery path is:

```text
Swift application
    -> local Swift Package façade
    -> Kotlin/Native XCFramework
    -> Kotlin coroutines and Flow
```

The Swift façade keeps Kotlin implementation types, coroutine types, and `Flow` out of the supported Swift API.

## Quick start

Requirements:

- macOS on Apple silicon
- Xcode 26.x
- Java 21
- An installed iOS 17 or newer simulator runtime

Run the complete POC verification:

```bash
./scripts/check.sh
```

The check covers:

- Kotlin iOS Simulator tests
- XCFramework generation
- Swift Package integration tests
- iOS sample build
- secret scanning
- Git whitespace validation

Run individual checks when needed:

```bash
./gradlew :bridge:iosSimulatorArm64Test
./scripts/build-xcframework.sh
./scripts/test-swift-package.sh
./scripts/build-sample.sh
./scripts/secret-scan.sh
git diff --check
```

The Xcode scripts prefer the newest available `iPhone 17 Pro` simulator. Override the destination when necessary:

```bash
POC_SIMULATOR_NAME='iPhone 16' ./scripts/test-swift-package.sh

POC_SIMULATOR_DESTINATION='platform=iOS Simulator,id=<simulator-udid>' \
  ./scripts/test-swift-package.sh
```

## iOS sample

Build the framework first:

```bash
./scripts/build-xcframework.sh
```

Then open:

```text
samples/ios/UniversalAiConnectorPOCSample.xcodeproj
```

The sample demonstrates:

- synchronous Kotlin version access;
- an asynchronous response;
- ordered streaming events;
- stable simulated error mapping;
- Swift task cancellation reaching Kotlin.

## Repository layout

```text
bridge/                 Kotlin Multiplatform bridge and tests
swift-package/          Supported Swift façade and Swift tests
samples/ios/            Standalone iOS SwiftUI sample
scripts/                Deterministic verification commands
docs/plans/             Package roadmap and work-package plans
```

Generated XCFrameworks, build directories, DerivedData, `.xcresult` bundles, and logs are ignored and must not be committed.

## Roadmap

The package roadmap is documented in [`docs/plans/universal-ai-connector-v2.md`](docs/plans/universal-ai-connector-v2.md).

The next approved implementation package expands the current bridge into tested iOS, Android, and JVM client demonstrations. See [`docs/plans/cross-platform-client-samples.md`](docs/plans/cross-platform-client-samples.md).

Provider and gateway work begins only after the cross-platform package foundation and canonical contracts are stable.

## License

Universal AI Connector is available under the MIT License. See [`LICENSE`](LICENSE).
