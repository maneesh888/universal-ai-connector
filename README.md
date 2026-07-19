# Universal AI Connector

**Provider-neutral Kotlin Multiplatform AI connectivity for Swift, Android, and JVM applications**

![Project stage](https://img.shields.io/badge/stage-interoperability%20POC-2563eb)
![Tests](https://img.shields.io/badge/tests-14%20passing-16a34a)
![Current platforms](https://img.shields.io/badge/verified-iOS%20Simulator%20%7C%20JVM%20%7C%20Android%20host-111827)
![License](https://img.shields.io/badge/license-MIT-7c3aed)

Universal AI Connector is an independent Kotlin Multiplatform project for exposing one provider-neutral AI client API to Kotlin, Android, JVM, and Swift applications.

The repository is currently at the interoperability proof-of-concept stage. It proves that a Swift application can call Kotlin/Native code through an XCFramework, receive asynchronous and streaming results, map stable errors, and propagate Swift task cancellation into Kotlin coroutines.

No AI provider, gateway, API key, or network integration is implemented yet.

> **Current phase:** P1 cross-platform package baseline in progress.
>
> **Current P1 proof:** Shared tests pass on JVM and Android host; cross-platform samples and iOS device delivery remain.
>
> **Production status:** Architecture validation only—not a production AI client yet.

## Project status and progress

### Overall roadmap completion: 10% — 1 of 10 milestones completed

```text
Interoperability POC       ████████████████████ 100%  ✅ Complete
Cross-platform baseline   ░░░░░░░░░░░░░░░░░░░░   0%  🚧 In progress
Canonical AI contracts    ░░░░░░░░░░░░░░░░░░░░   0%  ⏳ Planned
HTTP client foundation    ░░░░░░░░░░░░░░░░░░░░   0%  ⏳ Planned
Provider adapters         ░░░░░░░░░░░░░░░░░░░░   0%  ⏳ Planned
Gateway integration       ░░░░░░░░░░░░░░░░░░░░   0%  ⏳ Planned
Production distribution   ░░░░░░░░░░░░░░░░░░░░   0%  ⏳ Planned
Alpha release             ░░░░░░░░░░░░░░░░░░░░   0%  ⏳ Planned
```

The percentage measures completed roadmap milestones, not production readiness. See the [V2 roadmap](docs/plans/universal-ai-connector-v2.md) for milestone definitions and acceptance criteria.

### What works today

| Area | Status |
|---|---|
| Kotlin/Native iOS Simulator framework | ✅ Verified |
| XCFramework generation | ✅ Verified |
| Local Swift Package wrapper | ✅ Verified |
| Swift synchronous and async calls into Kotlin | ✅ Verified |
| Kotlin `Flow` to Swift `AsyncThrowingStream` | ✅ Verified |
| Stable Kotlin-to-Swift error mapping | ✅ Verified |
| Swift-to-Kotlin cancellation | ✅ Verified |
| SwiftUI sample compilation | ✅ Verified |
| JVM target and shared tests | ✅ Verified |
| Android library, host tests, and AAR | ✅ Verified |
| iOS device framework slice | ⏭️ Next milestone |
| Android and JVM sample clients | 🚧 Current milestone |
| Canonical AI contracts and HTTP transport | ⏳ Planned |
| OpenAI, Anthropic, OpenRouter, and gateway adapters | ⏳ Planned |

On July 19, 2026, all 6 shared tests passed independently on JVM and Android host, and the Android AAR assembled successfully. The complete Apple regression also passed with 6 iOS Simulator Kotlin tests, 8 Swift integration tests, XCFramework assembly, and the standalone sample build on an iPhone 17 Pro simulator destination.

### Milestone status

| Milestone | Description | Status |
|---|---|---|
| P0 | iOS-Kotlin interoperability POC | ✅ Completed |
| P1 | Cross-platform package and client samples | 🚧 In progress |
| P2 | Canonical core and JSON contracts | ⏳ Planned |
| P3 | HTTP transport and provider registry | ⏳ Planned |
| P4 | OpenAI Responses adapter | ⏳ Planned |
| P5 | Anthropic adapter | ⏳ Planned |
| P6 | OpenRouter and compatible adapters | ⏳ Planned |
| P7 | Universal Gateway V2 adapter | ⏳ Planned |
| P8 | Production Swift and Apple distribution | ⏳ Planned |
| P9 | Release hardening and internal alpha | ⏳ Planned |

### P1 remaining work

P1 will preserve the working interoperability path while adding:

1. iOS device and iOS Simulator XCFramework slices.
2. JVM and Android Kotlin Multiplatform targets.
3. Shared deterministic interoperability tests.
4. Proper iOS SwiftUI, Android, and JVM demonstration clients.
5. macOS and Linux CI coverage for the supported targets.

The detailed implementation and acceptance criteria are in the [cross-platform client samples plan](docs/plans/cross-platform-client-samples.md).

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
- Android SDK platform 36 and Build Tools 36.1 for the P1 Android checks

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
./gradlew :bridge:jvmTest
./gradlew :bridge:testAndroidHostTest
./gradlew :bridge:bundleAndroidMainAar
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
