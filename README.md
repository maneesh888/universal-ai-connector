# Universal AI Connector

**Provider-neutral Kotlin Multiplatform AI connectivity for Swift, Android, and JVM applications**

![Project stage](https://img.shields.io/badge/stage-P1%20cross--platform%20baseline-2563eb)
![JVM tests](https://img.shields.io/badge/JVM%20tests-20%20passing-16a34a)
![Current platforms](https://img.shields.io/badge/verified-iOS%20Simulator%20%2B%20device%20link%20%7C%20JVM%20consumer%20%7C%20Android%20app-111827)
![License](https://img.shields.io/badge/license-MIT-7c3aed)

Universal AI Connector is an independent Kotlin Multiplatform project for exposing one provider-neutral AI client API to Android, iOS, and Kotlin/JVM applications. The initial JVM artifact is intended to provide portable Linux, Windows, and macOS consumption without requiring separate native desktop builds.

The repository is currently in its P1 cross-platform baseline. Apple applications use the product-facing `UniversalAiConnector` Swift Package product over one local XCFramework containing iOS ARM64 device and simulator slices. The Swift façade preserves asynchronous response, streaming, stable errors, cancellation, concurrency, and exactly-once terminal handling. Android and JVM share the product-facing Kotlin client through the public Gradle module boundary.

No AI provider, gateway, API key, or network integration is implemented yet.

> **Current phase:** P1 cross-platform package baseline in progress.
>
> **Current P1 proof:** On July 21, 2026, `./scripts/check.sh --full` passed the 20-test shared suites, Android/JVM consumers, exact two-slice XCFramework validation, 15 product-facing plus 8 retained-POC Swift tests, the simulator sample build, the generic iOS-device link/build, and repository hygiene. The Android application separately passed installation, launch, and deterministic UI inspection on a local API 36.1 Pixel 8 emulator on July 20. Historical GitHub Actions run [29730678994](https://github.com/maneesh888/universal-ai-connector/actions/runs/29730678994) remains bounded compatibility evidence only because it used a synthetic merge commit and its former secret scanner failed open. Exact-head remote evidence for this Apple package is still required.
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
| Kotlin/Native iOS ARM64 device and simulator frameworks | ✅ Locally verified |
| Combined device-and-simulator XCFramework | ✅ Locally verified |
| Product-facing local Swift Package façade | ✅ Locally verified |
| Swift synchronous and async calls into Kotlin | ✅ Verified |
| Kotlin `Flow` to Swift `AsyncThrowingStream` | ✅ Verified |
| Stable Kotlin-to-Swift error mapping | ✅ Verified |
| Swift-to-Kotlin cancellation | ✅ Verified |
| SwiftUI simulator sample compilation | ✅ Locally verified |
| Generic iOS-device sample link/build | ✅ Locally verified; no device execution |
| JVM target and shared tests | ✅ Verified |
| Android library, host tests, and AAR | ✅ Verified |
| Linux, Windows, and macOS JVM PR jobs | ✅ Verified |
| Product-facing Kotlin client for Android and JVM | ✅ Verified on JVM and Android |
| JVM console through the public Gradle module boundary | ✅ Verified locally |
| JVM console on Linux, Windows, and macOS CI | ✅ Verified |
| Android application consumer | ✅ Verified locally on API 36.1 emulator |
| Graphical JVM desktop demonstration | ⏳ Planned for P8 distribution work |
| Physical iOS-device execution | ⏳ Not exercised |
| JVM sample client | ✅ Verified locally |
| Canonical AI contracts and HTTP transport | ⏳ Planned |
| OpenAI, Anthropic, OpenRouter, and gateway adapters | ⏳ Planned |

On July 20, 2026, the Android sample's 3 controller tests passed, its debug APK assembled, and the app installed and launched on a local API 36.1 Pixel 8 emulator. UI inspection confirmed the version, one-shot response, five ordered stream events, stable simulated error, response cancellation, and stream stop. GitHub Actions run [29730678994](https://github.com/maneesh888/universal-ai-connector/actions/runs/29730678994) then passed the Android consumer and complete remote matrix as configured at the time, but its source-testing jobs ran against synthetic merge commit `4a4bd2d88bc62c663a58cb5bb1f8d4bdaccec2d9` rather than the exact branch head. Their platform results are bounded compatibility evidence; the run does not provide exact-head repository-hygiene proof.

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

The product-facing Apple package is implemented while the proven POC Swift product and regression tests remain available as a temporary compatibility path. P1 remains in progress until the exact proposed head completes independent review and the required GitHub host matrix; physical-device execution is not a P1 completion requirement and has not been performed.

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

P8 will add one installable Compose Multiplatform desktop demonstration for macOS, Windows, and Linux. It will preserve a zero-configuration deterministic mode and add an opt-in live mode only after provider and Gateway adapters exist. The JVM console remains the headless and server-oriented verification path.

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

Build and test the Android application consumer:

```bash
./gradlew :samples:android:consumerCheck
```

With a booted emulator or connected device, install and launch it:

```bash
./scripts/run-android-sample.sh
```

Set `UAC_ANDROID_SERIAL=<serial>` when more than one device is connected; the script uses that device for both installation and launch. Set `UAC_ADB=/path/to/adb` when `adb` is not on `PATH` or in the standard macOS Android SDK location.

Run the complete deterministic verification:

```bash
./scripts/check.sh
```

On macOS, the full check covers:

- shared JVM tests
- Android shared host tests and AAR packaging
- the JVM console consumer test and executable
- the Android consumer controller tests and debug APK
- Kotlin iOS Simulator tests
- device-and-simulator XCFramework generation and slice validation
- 15 product-facing and 8 retained-POC Swift Package integration tests
- iOS simulator sample build
- generic iOS-device sample link/build
- secret scanning
- Git whitespace validation

Enable the mandatory local commit and push gates once per clone:

```bash
./scripts/install-hooks.sh
```

The pre-commit hook runs the quick cross-platform suite. The pre-push hook requires a clean worktree and runs the complete deterministic suite. Do not bypass either hook.

Run individual checks when needed:

```bash
./gradlew :bridge:jvmTest
./gradlew :bridge:testAndroidHostTest
./gradlew :bridge:bundleAndroidMainAar
./gradlew :samples:jvm-console:consumerCheck
./gradlew :samples:jvm-console:run
./gradlew :samples:android:consumerCheck
./scripts/run-android-sample.sh
./gradlew :bridge:iosSimulatorArm64Test
./scripts/build-xcframework.sh
./scripts/test-swift-package.sh
./scripts/build-sample.sh
./scripts/build-sample-device.sh
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

The Kotlin API is hidden from Objective-C export so Apple consumers use the supported Swift façade. A private product-facing callback adapter delegates to the same Kotlin client without exporting `Flow` or Kotlin implementation types through the Swift API. The XCFramework build validates both Apple headers and fails if the product Kotlin client or `Flow` leaks into either one.

## Android sample

The Jetpack Compose application declares `implementation(project(":bridge"))` and uses the same `UniversalAiConnector` entry point as the JVM sample. `MainActivity` owns the coroutine lifetime through its lifecycle scope; cancelling that scope cancels active connector work, while the reusable connector itself owns no scope or cleanup resource.

The minimal application path is:

```kotlin
val connector = UniversalAiConnector()

lifecycleScope.launch {
    val response = connector.respond("hello from Android")
    connector.stream("Android stream").collect { event ->
        println("${event.sequence}: ${event.text}")
    }
}
```

The full screen runs deterministic local behavior automatically and provides controls to rerun response, stream, stable-error, response-cancellation, and stream-stop paths. It performs no networking and needs no API key.

## iOS sample

Build the local binary target, then open the product-facing sample:

```bash
./scripts/build-xcframework.sh
open samples/ios/UniversalAiConnectorSample.xcodeproj
```

In Xcode, add or retain the local package at `swift-package/` and select only the `UniversalAiConnector` library product for application code. The compiling first-use path is:

```swift
import UniversalAiConnector

func runFirstUse() async throws {
    let connector = UniversalAiConnector()
    let response = try await connector.respond(to: "hello from Swift")
    print(response)

    for try await event in connector.stream(input: "stream") {
        print("\(event.sequence): \(event.text)")
    }
}
```

`UniversalAiConnector` is reusable, thread-safe, and supports concurrent responses and streams. Each operation runs independently; the façade and its Kotlin callback adapter own no long-lived coroutine job or external resource. The calling Swift task owns response lifetime, and the consuming task owns stream lifetime. Cancelling either task propagates to that Kotlin operation, including cancellation that races with handle installation. Ending stream iteration also cancels its Kotlin operation. After active tasks and streams finish or are cancelled, no explicit `close` call is required and the connector can be released.

Failures arrive as `UniversalAiConnectorError`; Swift task cancellation remains `CancellationError`. The sample owns its tasks, cancels them when its view disappears, and provides explicit controls for:

- an asynchronous response;
- ordered streaming;
- a stable simulated error;
- response cancellation; and
- stream cancellation.

Verify the sample for both supported build destinations:

```bash
./scripts/build-sample.sh
./scripts/build-sample-device.sh
```

The second command compiles and links against the `ios-arm64` framework slice using Xcode's generic iOS-device destination with code signing disabled. It is not physical-device execution proof.

## Repository layout

```text
bridge/                 Kotlin Multiplatform bridge and tests
swift-package/          Supported Swift façade and Swift tests
samples/ios/            Standalone iOS SwiftUI sample
samples/android/        Jetpack Compose public-module Android consumer
samples/jvm-console/    Non-interactive public-module Kotlin/JVM consumer
scripts/                Deterministic verification commands
docs/plans/             Package roadmap and work-package plans
```

Generated XCFrameworks, build directories, DerivedData, `.xcresult` bundles, and logs are ignored and must not be committed.

## Roadmap

The package roadmap is documented in [`docs/plans/universal-ai-connector-v2.md`](docs/plans/universal-ai-connector-v2.md).

The product-facing Apple delivery/sample package is the current P1 completion candidate. Its exact evidence and remaining review boundary are recorded in [`docs/plans/cross-platform-client-samples.md`](docs/plans/cross-platform-client-samples.md).

Provider and gateway work begins only after the cross-platform package foundation and canonical contracts are stable. Production Maven and remote Swift Package distribution is planned for P8 after the client contract and transport are established.

## License

Universal AI Connector is available under the MIT License. See [`LICENSE`](LICENSE).
