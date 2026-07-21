# P1 Cross-Platform Client Samples

## Objective

Turn the verified iOS-Kotlin POC into a cross-platform package baseline with thin, deterministic demonstration clients for iOS, Android, and JVM, and establish the host-integration contract that later production artifacts must preserve.

This work package validates packaging and consumption. It does not add Ktor, provider SDKs, API keys, canonical AI DTOs, or gateway networking.

## Live-test boundary

P1 is intentionally secretless. Its samples, local checks, and GitHub Actions use deterministic fake behavior and must not require a provider or Gateway credential. The roadmap's mandatory local pre-PR live-test gate activates with the first provider adapter in P4; it does not apply to P1 because P1 has no live behavior to verify.

Do not add `.env` files, credential loaders, live-provider scripts, protected secret-bearing workflows, or claims of live-provider proof in this work package. P4 must establish the ignored local-secret convention, value-free environment example, separate `./scripts/check-live.sh` command, and protected GitHub Environment before its first adapter pull request. Once that gate exists, later changes to live provider, Gateway, or shared transport behavior must satisfy it as defined in `universal-ai-connector-v2.md`.

P1's normal GitHub Actions workflow remains read-only and secretless, with no write token, PAT, autonomous merger, or `pull_request_target`. Pull-request jobs check out the exact PR head, install the non-secret `rg` scanner dependency where needed, disable ripgrep configuration and ignore rules, fail closed if secret scanning cannot execute, and never print matched credential material. The stable `Required checks` status must complete successfully for the exact independently reviewed head before a draft pull request leaves draft or any merge command runs; pending, skipped, or invalidly executed required checks are not an auto-merge queueing strategy.

## P1 host-integration outcome

P1 must prove that each sample is a real consumer of the supported package boundary. It does not publish remote artifacts, but it must remove repository-internal knowledge from sample APIs and document the same steps that a future external consumer will use.

The integration baseline is:

- one shared Kotlin client entry point for Android and JVM;
- one Swift façade for Apple consumers;
- idiomatic `suspend` and `Flow` APIs in Kotlin;
- idiomatic `async`, `AsyncThrowingStream`, Swift errors, and cancellation in Swift;
- simple default construction for the deterministic implementation;
- documented lifecycle, concurrency, cancellation, and cleanup behavior;
- no callback bridge, generated Objective-C name, internal implementation package, or packaging detail exposed as supported host API.

The POC names remain valid only while preserving the verified migration path. New samples should use non-POC, product-facing names. Remove the old path only after equivalent Kotlin and Swift integration tests pass.

## Progress evidence

On July 19, 2026, the existing bridge gained JVM and Android targets. `./gradlew :bridge:jvmTest` and `./gradlew :bridge:testAndroidHostTest` each compiled their target and ran all 6 common tests with zero failures, and `./gradlew :bridge:bundleAndroidMainAar` assembled the Android library. The complete P0 regression also passed under Gradle 8.11.1: iOS Simulator bridge tests, simulator XCFramework assembly, all 8 Swift integration tests, the iOS sample build, secret scanning, and whitespace checks.

GitHub Actions run [29687591527](https://github.com/maneesh888/universal-ai-connector/actions/runs/29687591527) passed on July 19, 2026. Its JVM tests on Linux, Windows, and macOS, Android host tests and AAR packaging on Linux, complete P0 Apple suite on macOS, and stable `Required checks` aggregator are bounded compatibility evidence; its hygiene conclusion is invalid for the reasons recorded below.

That merged baseline remained partial P1 evidence only: it did not include a JVM console sample, Android sample application, iOS ARM64 device slice, or expanded shared test matrix. Android emulator and physical-device behavior were not exercised.

The current bounded P1 package adds `UniversalAiConnector` as the supported Android/JVM entry point and `samples/jvm-console` as a real `project(":bridge")` consumer. The client is reusable, concurrent, and thread-safe; it owns no coroutine scope or resource, requires no cleanup, and runs suspending work and cold flows in the caller's context. Its deterministic behavior covers one-shot response, ordered streaming, stable typed errors, and caller cancellation. The Kotlin API is hidden from Objective-C export so the verified Apple callback bridge and Swift façade remain the only supported Apple path.

Local verification passed July 19, 2026: `:bridge:jvmTest`, `:bridge:testAndroidHostTest`, and `:bridge:iosSimulatorArm64Test` each ran 13 shared tests with zero failures; `:bridge:bundleAndroidMainAar` assembled the library; the JVM console `test`, `build`, `run`, and `consumerCheck` tasks passed with its one exact-output smoke test; and `./scripts/check.sh --full` passed XCFramework header validation, all 8 Swift tests, the iOS sample build, secret scanning, and whitespace validation.

GitHub Actions run [29698575249](https://github.com/maneesh888/universal-ai-connector/actions/runs/29698575249) passed July 19, 2026. The JVM console consumer and 13 shared tests passed on Linux, Windows, and macOS; Android host tests and AAR packaging passed on Linux; the complete P0 Apple regression passed on macOS; and the stable `Required checks` aggregator passed. These are bounded compatibility results, not exact-head or repository-hygiene proof.

The July 20, 2026 Android consumer package adds `samples/android`, a separate Jetpack Compose application with `implementation(project(":bridge"))`. It uses `UniversalAiConnector`, keeps coroutine lifetime in the activity lifecycle scope, and renders every shared demonstration operation without networking or secrets. `./scripts/check.sh --full` passed the Kotlin, JVM consumer, Android consumer, XCFramework, 8-test Swift, iOS sample, secret, and whitespace regression. After a final job-start ordering hardening, `:samples:android:consumerCheck` passed all 3 controller tests and debug APK assembly. `./scripts/run-android-sample.sh` then installed and cold-launched the final APK on a local API 36.1 Pixel 8 emulator; screenshot and UI-hierarchy inspection confirmed version `0.1.0-alpha.1`, the exact one-shot response, five ordered events, stable `simulated_failure`, response cancellation, and stream stop after event 1. A direct on-device cancellation-button check also completed and left its controls enabled. GitHub Actions run [29730678994](https://github.com/maneesh888/universal-ai-connector/actions/runs/29730678994) passed the Android consumer and other platform jobs as then configured; no physical-device claim is made.

The historical runs above were pull-request runs whose jobs used GitHub's synthetic merge checkout rather than the exact PR head. Their green hygiene conclusions are also invalid remote secret-scan evidence: `rg` was unavailable and the former scanner failed open. Their platform and consumer results remain bounded compatibility evidence, but exact-head repository-hygiene proof must come from a later run whose logs show exact-head checkout plus the fail-closed scanner and its missing-tool, ignore-rule, configuration-exclusion, and non-disclosure regressions executing successfully.

The July 21, 2026 Apple delivery candidate adds an iOS ARM64 target beside iOS Simulator ARM64 and assembles both into one locally consumed XCFramework. A new `UniversalAiConnector` Swift Package product delegates through an Apple-only callback adapter to the same shared Kotlin client used by Android and JVM. The adapter is isolated to the iOS source set and excluded from the JVM JAR and Android AAR. It owns no long-lived coroutine job or external resource; every response or stream has its own cancellation handle. The retained `UniversalAiConnectorPOC` product and its eight tests remain as migration regression coverage while the product-facing suite establishes parity. The renamed SwiftUI sample imports only `UniversalAiConnector` and provides deterministic controls for response, streaming, forced error, immediate response cancellation, and consuming-task cancellation after the first stream event. Exact local and pull-request evidence for the final proposed head is recorded only after its mandatory checks complete.

Local verification passed July 21, 2026 with `./scripts/check.sh --full`. The JVM, Android host, and iOS Simulator suites each ran 13 cross-platform shared tests, and iOS Simulator additionally ran 7 Apple-adapter tests. Android AAR packaging and the JVM and Android consumers passed, and the artifact-boundary check confirmed that the Apple adapter is absent from both non-Apple artifacts. XCFramework assembly produced exactly `ios-arm64` and `ios-arm64-simulator`, each arm64 with an iOS 17.0 minimum, and both exported headers passed the Kotlin-client and `Flow` boundary checks. The aggregate Swift Package simulator scheme passed 15 product-facing tests plus all 8 retained POC tests. The product-only SwiftUI sample then built for the simulator and compiled and linked for `generic/platform=iOS` with signing disabled. Secret scanning and its fail-closed regression suite, generated-artifact exclusion, shell syntax, and whitespace checks passed. This is deterministic simulator and generic-device link evidence only; no physical iOS device was installed to or executed.

## Target structure

```text
bridge/                                      Shared Kotlin client plus iOS-only callback adapters
swift-package/Sources/UniversalAiConnector/ Product-facing supported Swift façade
swift-package/Sources/UniversalAiConnectorPOC/ Retained POC compatibility façade
samples/ios/UniversalAiConnectorSample/     SwiftUI product consumer
samples/android/                             Android public-module consumer
samples/jvm-console/                         Kotlin/JVM public-module consumer
```

The product-facing Swift façade is the supported Apple API. Keep the retained POC product and tests only as migration coverage until the product path has equivalent exact-head integration evidence; do not expose either callback adapter as a supported application API.

## Shared demonstration contract

All clients demonstrate the same deterministic operations:

- retrieve the library version;
- submit a one-shot text request;
- receive an ordered event stream;
- trigger and display a stable simulated error;
- cancel a one-shot request;
- cancel or stop a stream.

Samples remain presentation-only. Shared behavior belongs in common Kotlin code, and Apple-specific adaptation belongs in the Swift bridge/façade.

## Consumer boundary rules

- Android and JVM samples depend on the shared library module through its public Gradle dependency boundary; they must not compile common source files directly.
- The iOS sample imports the Swift Package product and must not import the Kotlin framework directly from application code.
- Samples may own UI state, command-line rendering, and host task scopes. Connector behavior, error mapping rules, fake response generation, and stream semantics remain in the package.
- Each sample must include a minimal first-use path before its full response, stream, error, and cancellation demonstration.
- README snippets must be copied from or checked against compiling sample code.

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
- Keep the initial JVM API Kotlin-first and verify the same sample on Linux, Windows, and macOS hosts.
- Ensure cancellation tests use coroutine test primitives and do not depend on wall-clock sleeps.

The console sample is non-interactive and imports only `com.maneesh.universalai.connector` from the public bridge module. `:samples:jvm-console:consumerCheck` compiles the dependency boundary, asserts the complete consumer-facing output with virtual coroutine time, and runs the application to deterministic termination.

### Android

- Add the Android KMP target and the minimum required Android library configuration.
- Add a small sample application that exercises response, streaming, cancellation, and error states.
- Use the same public Kotlin client entry point as the JVM sample and keep coroutine lifetime in the application layer.
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
- consuming-task cancellation while the returned stream remains retained;
- parent-task cancellation;
- cancellation before Kotlin handle installation;
- stream cancellation before handle installation and late-terminal suppression;
- concurrent response and stream calls;
- repeated connector creation and release without late callbacks.

### Packaging/build checks

- JVM sample compiles and runs.
- JVM sample compiles and runs on Linux, Windows, and macOS CI without OS-specific source changes.
- Android library and sample compile.
- Apple callback-adapter classes are absent from the JVM JAR and Android AAR.
- iOS Simulator tests pass.
- iOS device framework slice links in a generic device build.
- Swift Package tests pass.
- iOS SwiftUI sample builds for the simulator.
- Generated artifacts are ignored.
- Secret and whitespace scans pass.

### Host-integration smoke checks

- A clean Kotlin consumer can add the local package dependency, construct the client, and execute the deterministic one-shot operation.
- A clean Swift consumer can add the local Swift Package product, construct the façade, and execute the deterministic async operation.
- Consumer code imports only documented public packages and products.
- Cancellation from the host task or coroutine reaches the shared implementation.
- Samples compile without accessing repository-internal source directories or manually copying generated frameworks.
- Documented first-use snippets remain aligned with the compiling consumer code.

## Scripts and CI

Extend repository scripts so one top-level check runs deterministic validation. Keep live/provider checks separate.

CI uses:

- macOS for Kotlin/Native, combined-XCFramework validation, Swift Package simulator tests, iOS simulator and generic-device sample builds, and a JVM consumer smoke check;
- Linux for JVM and Android unit/build checks plus the JVM console consumer;
- Windows for the JVM test and console-consumer path.

CI must not require API keys, running simulators outside the macOS job, or committed binary artifacts.

## Acceptance criteria

- All required target compilations and deterministic tests pass.
- All three samples consume the same shared client behavior.
- Android and JVM use one public Kotlin client entry point, while iOS uses one product-facing Swift façade.
- The iOS sample uses the packaged XCFramework rather than compiling Kotlin sources directly.
- No sample imports an internal bridge or implementation package from application code.
- Cancellation is proven from every client surface where the platform supports it.
- Construction, lifecycle, concurrency, cancellation, and cleanup expectations are documented for Kotlin and Swift hosts.
- The public sample APIs contain no provider-specific types.
- First-use snippets for Kotlin and Swift are backed by compiling consumer samples.
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
- Graphical desktop demonstration; P1 uses the headless JVM console, while the installable macOS/Windows/Linux Compose desktop demo is a P8 requirement
- Native Linux, Windows, or macOS artifacts; P1 desktop/server coverage is through Kotlin/JVM
- Java-specific, JavaScript, or Wasm façades
