# P1 Cross-Platform Client Samples

## Objective

Turn the verified iOS-Kotlin POC into a cross-platform package baseline with thin, deterministic demonstration clients for iOS, Android, and JVM.

This work package validates packaging and consumption. It does not add Ktor, provider SDKs, API keys, canonical AI DTOs, or gateway networking.

## Progress evidence

On July 19, 2026, the existing bridge gained JVM and Android targets. `./gradlew :bridge:jvmTest` and `./gradlew :bridge:testAndroidHostTest` each compiled their target and ran all 6 common tests with zero failures, and `./gradlew :bridge:bundleAndroidMainAar` assembled the Android library. The complete P0 regression also passed under Gradle 8.11.1: iOS Simulator bridge tests, simulator XCFramework assembly, all 8 Swift integration tests, the iOS sample build, secret scanning, and whitespace checks.

This is partial P1 evidence only. A JVM console sample, Android sample application, iOS ARM64 device slice, expanded shared test matrix, and cross-platform CI remain unverified. Android emulator and physical-device behavior were not exercised.

## Target structure

```text
v2/
├── universal-ai-core/          Shared POC client contract and deterministic fake behavior
├── universal-ai-testing/       Reusable interoperability fixtures and assertions
└── universal-ai-swift-bridge/  Apple callback bridge and XCFramework entry point

samples/
├── ios-swift/                  SwiftUI application using the Swift Package façade
├── android/                    Small Android application using the shared client
└── jvm-console/                Command-line client using the shared client
```

The existing `bridge`, `swift-package`, and `samples/ios` code should be migrated deliberately after equivalent tests exist. Do not delete the verified POC path before the replacement passes.

## Shared demonstration contract

All clients demonstrate the same deterministic operations:

- retrieve the library version;
- submit a one-shot text request;
- receive an ordered event stream;
- trigger and display a stable simulated error;
- cancel a one-shot request;
- cancel or stop a stream.

Samples remain presentation-only. Shared behavior belongs in common Kotlin code, and Apple-specific adaptation belongs in the Swift bridge/façade.

## Platform deliverables

### iOS

- Add `iosArm64` alongside `iosSimulatorArm64`.
- Produce one XCFramework with device and simulator slices.
- Preserve the local Swift Package binary-target integration.
- Upgrade the SwiftUI sample with explicit controls for response, stream, cancellation, and forced error.
- Keep Swift async APIs, `AsyncThrowingStream`, stable Swift errors, and cancellation propagation.

### JVM

- Add the JVM KMP target.
- Add a console sample that exercises every shared demonstration operation.
- Ensure cancellation tests use coroutine test primitives and do not depend on wall-clock sleeps.

### Android

- Add the Android KMP target and the minimum required Android library configuration.
- Add a small sample application that exercises response, streaming, cancellation, and error states.
- Keep UI code in the sample; no Android framework dependency belongs in common core.

## Test matrix

### Common/Kotlin tests

- deterministic synchronous and asynchronous values;
- invalid-input and forced-error codes;
- ordered stream events and completion;
- stream failure;
- cancellation before callback delivery;
- cancellation after the first stream event;
- exactly-once terminal callback behavior;
- concurrent one-shot requests;
- concurrent streams;
- no success/error callback after cancellation.

Run common behavior on JVM, Android unit tests, and iOS Simulator wherever supported by the target test runner.

### Swift integration tests

- framework import and synchronous call;
- async response and error mapping;
- complete stream ordering;
- early stream termination;
- parent-task cancellation;
- cancellation before Kotlin handle installation;
- concurrent response and stream calls;
- repeated connector creation and release without late callbacks.

### Packaging/build checks

- JVM sample compiles and runs.
- Android library and sample compile.
- iOS Simulator tests pass.
- iOS device framework slice links in a generic device build.
- Swift Package tests pass.
- iOS SwiftUI sample builds for the simulator.
- Generated artifacts are ignored.
- Secret and whitespace scans pass.

## Scripts and CI

Extend repository scripts so one top-level check runs deterministic validation. Keep live/provider checks separate.

CI should use:

- macOS for Kotlin/Native, XCFramework, Swift Package, and iOS sample verification;
- Linux for JVM and Android unit/build checks.

CI must not require API keys, running simulators outside the macOS job, or committed binary artifacts.

## Acceptance criteria

- All required target compilations and deterministic tests pass.
- All three samples consume the same shared client behavior.
- The iOS sample uses the packaged XCFramework rather than compiling Kotlin sources directly.
- Cancellation is proven from every client surface where the platform supports it.
- The public sample APIs contain no provider-specific types.
- No networking or secret-bearing configuration is introduced.
- README commands and sample paths are accurate on a clean checkout.
- `./scripts/secret-scan.sh` and `git diff --check` pass.

## Out of scope

- Canonical AI request and response models
- Provider discovery or model discovery
- OpenAI, Anthropic, OpenRouter, compatible, or Gateway adapters
- Live API calls
- OpenKeyboard integration
- Artifact publication to Maven or a remote Swift Package
