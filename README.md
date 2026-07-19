# Universal AI Connector

**Provider-neutral Kotlin Multiplatform AI connectivity for Swift, Android, and JVM applications**

![Project stage](https://img.shields.io/badge/stage-P1%20cross--platform%20baseline-2563eb)
![JVM tests](https://img.shields.io/badge/JVM%20tests-14%20passing-16a34a)
![Current platforms](https://img.shields.io/badge/verified-iOS%20Simulator%20%7C%20JVM%20consumer%20%7C%20Android%20host-111827)
![License](https://img.shields.io/badge/license-MIT-7c3aed)

Universal AI Connector is an independent Kotlin Multiplatform project for exposing one provider-neutral AI client API to Android, iOS, and Kotlin/JVM applications. The initial JVM artifact is intended to provide portable Linux, Windows, and macOS consumption without requiring separate native desktop builds.

The repository is currently in its P1 cross-platform baseline. The verified Apple proof still shows that Swift can call Kotlin/Native through an XCFramework, receive asynchronous and streaming results, map stable errors, and propagate cancellation. Android and JVM now share a product-facing Kotlin client, and a JVM console application consumes that client through the public Gradle module boundary.

No AI provider, gateway, API key, or network integration is implemented yet.

> **Current phase:** P1 cross-platform package baseline in progress.
>
> **Current P1 proof:** The pre-consumer PR baseline passes JVM tests on Linux, Windows, and macOS, Android host tests/AAR packaging on Linux, and the complete Apple P0 suite on macOS. Locally, 13 shared tests pass on JVM, Android host, and iOS Simulator; the JVM console output smoke, Android AAR, XCFramework/Swift tests, and iOS sample build also pass. The updated three-host consumer matrix is pending its pull-request run; the Android application and iOS device delivery remain.
>
> **Production status:** Architecture validation only—not a production AI client yet.

## Integration goal

The production library is intended to require one documented dependency and one primary client entry point on each host surface. Kotlin applications will receive idiomatic `suspend` and `Flow` APIs. Swift applications will receive a Swift façade using `async`, `AsyncThrowingStream`, Swift errors, and Swift cancellation without exposing Kotlin implementation types.

P1 is establishing this package boundary through compiling iOS, Android, and JVM consumer samples. Remote Maven coordinates and remote Swift Package installation are planned for P8 and are not available yet.

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
| Linux, Windows, and macOS JVM PR jobs | ✅ Verified |
| Product-facing Kotlin client for Android and JVM | ✅ Verified on JVM |
| JVM console through the public Gradle module boundary | ✅ Verified locally |
| JVM console on Linux, Windows, and macOS CI | 🚧 PR verification pending |
| Android application consumer | 🚧 Next P1 package |
| iOS device framework slice | ⏭️ Next milestone |
| JVM sample client | ✅ Verified locally |
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
| P8 | Production distribution and host integration | ⏳ Planned |
| P9 | Release hardening and internal alpha | ⏳ Planned |

### P1 remaining work

P1 will preserve the working interoperability path while completing:

1. iOS device and iOS Simulator XCFramework slices.
2. The Android demonstration client using the shared Kotlin API.
3. The product-facing Swift migration and iOS sample upgrade.
4. The remaining shared and host interoperability tests.
5. Consumer-boundary checks and copy-paste-ready first-use documentation.
6. Linux, Windows, and macOS JVM CI plus the existing macOS Apple toolchain coverage.

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

The planned host-facing shape is deliberately small:

- Android and JVM share one Kotlin client and common models.
- iOS uses one Swift façade over the packaged XCFramework.
- Simple construction works without advanced transport setup; injectable transport is added with the P3 networking milestone.
- Host coroutine or task cancellation propagates into connector work.
- Samples consume public package boundaries and remain thin presentation layers.

Native Linux, Windows, and macOS artifacts are demand-driven. The initial desktop/server path is Kotlin/JVM; Java-specific, JavaScript, and Wasm façades are not currently committed support surfaces.

The current Kotlin client is `com.maneesh.universalai.connector.UniversalAiConnector`. It is reusable, concurrent, and thread-safe. It owns no coroutine scope or external resource, so no cleanup is required. `respond` and the cold `stream` flow run in the caller's coroutine context, and caller cancellation stops the active operation.

## Quick start

Requirements:

- Java 21
- macOS on Apple silicon and Xcode 26.x for Apple verification
- An installed iOS 17 or newer simulator runtime
- Android SDK platform 36 and Build Tools 36.1 for the P1 Android checks

Run the JVM console consumer on any supported JVM host:

```bash
./gradlew :samples:jvm-console:run
```

Verify its public module dependency, exact output, and executable entry point:

```bash
./gradlew :samples:jvm-console:consumerCheck
```

Run the complete deterministic verification:

```bash
./scripts/check.sh
```

On macOS, the full check covers:

- shared JVM tests
- the JVM console consumer test and executable
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
./gradlew :samples:jvm-console:consumerCheck
./gradlew :samples:jvm-console:run
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

## Kotlin/JVM sample

The console sample declares only `implementation(project(":bridge"))` for connector behavior. It does not copy or compile shared sources and imports no `poc` or callback-bridge packages.

The first-use path is:

```kotlin
val connector = UniversalAiConnector()
println(connector.version)
println(connector.respond("hello from JVM"))

connector.stream("stream").collect { event ->
    println("${event.sequence}: ${event.text}")
}
```

Failures are delivered as `UniversalAiConnectorException` with a typed `UniversalAiErrorCode` and stable string value. Cancellation is controlled by the caller's coroutine or flow collection; the sample cancels a one-shot request and stops a stream after its first event without input or orchestration sleeps.

The Kotlin API is hidden from Objective-C export so Apple consumers continue through the existing callback bridge and supported Swift façade. The XCFramework build fails if the product Kotlin client or `Flow` leaks into the generated Apple header.

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
samples/jvm-console/    Non-interactive public-module Kotlin/JVM consumer
scripts/                Deterministic verification commands
docs/plans/             Package roadmap and work-package plans
```

Generated XCFrameworks, build directories, DerivedData, `.xcresult` bundles, and logs are ignored and must not be committed.

## Roadmap

The package roadmap is documented in [`docs/plans/universal-ai-connector-v2.md`](docs/plans/universal-ai-connector-v2.md).

The next bounded P1 package is the Android consumer sample using the same `UniversalAiConnector` API. See [`docs/plans/cross-platform-client-samples.md`](docs/plans/cross-platform-client-samples.md).

Provider and gateway work begins only after the cross-platform package foundation and canonical contracts are stable. Production Maven and remote Swift Package distribution is planned for P8 after the client contract and transport are established.

## License

Universal AI Connector is available under the MIT License. See [`LICENSE`](LICENSE).
