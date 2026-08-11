# Universal AI Connector

**Provider-neutral Kotlin Multiplatform AI connectivity for Swift, Android, and JVM applications**

![Project stage](https://img.shields.io/badge/stage-P7%20active-f59e0b)
![Deterministic checks](https://img.shields.io/badge/deterministic%20checks-passing-16a34a)
![Current platforms](https://img.shields.io/badge/verified-iOS%20Simulator%20%2B%20device%20link%20%7C%20JVM%20consumer%20%7C%20Android%20app-111827)
![License](https://img.shields.io/badge/license-MIT-7c3aed)

Universal AI Connector is an independent Kotlin Multiplatform project for exposing one provider-neutral AI client API to Android, iOS, and Kotlin/JVM applications. The initial JVM artifact is intended to provide portable Linux, Windows, and macOS consumption without requiring separate native desktop builds.

The repository has completed its P1 cross-platform baseline and P2 provider-neutral contract foundation. Apple applications use the product-facing `UniversalAiConnector` Swift Package product over one local XCFramework containing iOS ARM64 device and simulator slices. The Swift façade preserves asynchronous response, streaming, stable errors, cancellation, concurrency, and exactly-once terminal handling. Android and JVM share the product-facing Kotlin client through the public Gradle module boundary.

P4 completed the first bounded provider path: OpenAI Responses authentication, plain-text and
governed structured request/response translation, usage and request metadata, safe provider
failures, conservative capabilities, incremental streaming, cancellation, lifecycle cleanup,
secret-safe live verification, and package-boundary audits. P5 completes the Anthropic Messages
path through protocol readiness, non-streaming and structured translation, safe errors,
capabilities, streaming, cancellation, lifecycle cleanup, exact-head live proof, and boundary
acceptance. P6 completed OpenRouter/generic protocol readiness in P6-A, direct non-streaming
OpenRouter behavior in P6-B, generic OpenAI-compatible non-streaming behavior in P6-C, and strict
structured output, bounded errors/metadata, and conservative capabilities in P6-D, and
incremental streaming and active cancellation in P6-E. P6-F completed concurrent lifecycle,
close-race, cleanup, consumer, secret-safety, and package-boundary acceptance. P7 is active, and
P7-A freezes the external Gateway contract plus deterministic compatibility fixtures without
adding another runtime adapter.

> **Current phase:** P2 canonical core and JSON contracts and P3 provider-neutral HTTP transport
> and registry are completed. P4 OpenAI Responses is completed through non-streaming, structured
> output, errors, capabilities, streaming, cancellation, concurrent lifecycle, secret-safety,
> live evidence, and boundary acceptance. P5 Anthropic Messages is completed authoritatively.
> P6 is completed authoritatively. P7 is active, correctly scoped as validation of the existing
> generic OpenAI-compatible adapter against the independently maintained LLM Gateway rather than
> a proprietary Gateway protocol. P7-A external contract freeze and deterministic compatibility
> fixtures are complete in the current candidate; P7-B remains not started.
>
> **P1 completion:** Closing head `fdf33e5d197f13f5ab32f23cfc290ad263451946` passed the complete local gate, independent review, and exact-head GitHub Actions run [29991895652](https://github.com/maneesh888/universal-ai-connector/actions/runs/29991895652). It merged through [PR #12](https://github.com/maneesh888/universal-ai-connector/pull/12) on July 23, 2026, and resulting `main` run [29993494307](https://github.com/maneesh888/universal-ai-connector/actions/runs/29993494307) passed.
> Roadmap-closeout [PR #14](https://github.com/maneesh888/universal-ai-connector/pull/14) then recorded P1 as completed at `main` head `260345f1cd3d2f05faff1bdd6361b9ce58db1ddf`; resulting `main` run [30075847578](https://github.com/maneesh888/universal-ai-connector/actions/runs/30075847578) passed before P2 was activated separately.
>
> **P2 completion boundary:** P2 adds 21 authoritative schemas, 173 fixture documents, common Kotlin semantic validation and serialization, Swift-native canonical mappings, and deterministic JVM, Android, and Apple consumption. The milestone-closing pull-request brief is the authoritative record for exact closing-head checks, independent review, merge, and resulting `main` evidence so repository status does not require self-referential commits.
>
> **P3 completion boundary:** P3 adds injectable Ktor transport, URL/header/timeout policy, bounded SSE and response metadata, immutable per-client provider registration, integrated transport cancellation and cleanup, and exactly-once terminal arbitration. Its milestone-closing pull-request brief owns the exact-head local, CI, review, merge, and resulting-`main` evidence.
>
> **P4 completion boundary:** P4 adds the internal OpenAI Responses adapter, provider-neutral credential supply, deterministic and targeted live behavior proof, concurrent lifecycle and close-race coverage, and automated secretless CI and provider-boundary audits. Its milestone-closing pull-request brief owns the exact-head deterministic, live, CI, review, merge, and resulting-`main` evidence.
>
> **P5 completion boundary:** P5 adds the internal Anthropic Messages adapter, provider-neutral credential supply, deterministic and targeted live behavior proof, concurrent lifecycle and close-race coverage, and the automated secretless CI and provider-boundary audits. Its milestone-closing pull-request brief owns the exact-head deterministic, affected Anthropic live, CI, review, merge, and resulting-`main` evidence.
>
> **P6 completion boundary:** P6 adds the internal direct OpenRouter and conservative generic OpenAI-compatible Chat Completions adapters, provider-neutral credential supply, deterministic and targeted live behavior proof, cross-adapter concurrent lifecycle and close-race coverage, and the automated secretless CI and provider-boundary audits. Its milestone-closing pull-request brief owns the exact-head deterministic, delivered-provider live, CI, review, merge, and resulting-`main` evidence.
>
> **Accepted bounded proof:** The P2 Apple path covers 36 Swift integration tests, the two-slice XCFramework, simulator sample compilation, and generic iOS-device linking. The Android application passed installation, launch, rerun, and deterministic UI inspection on a local API 36 emulator. Physical iOS-device execution has not been performed.
>
> **Production status:** Architecture validation only—not a production AI client yet.

## Integration goal

The production library is intended to require one documented dependency and one primary client entry point on each host surface. Kotlin applications will receive idiomatic `suspend` and `Flow` APIs. Swift applications will receive a Swift façade using `async`, `AsyncThrowingStream`, Swift errors, and Swift cancellation without exposing Kotlin implementation types.

P1 established this package boundary through compiling iOS, Android, and JVM consumer samples. Remote Maven coordinates and remote Swift Package installation are planned for P8 and are not available yet.

## Project status and progress

### Overall roadmap completion: 70% — 7 of 10 milestones completed

```text
Interoperability POC       ████████████████████ 100%  ✅ Complete
Cross-platform baseline   ████████████████████ 100%  ✅ Complete
Canonical AI contracts    ████████████████████ 100%  ✅ Complete
HTTP client foundation    ████████████████████ 100%  ✅ Complete
Provider adapters         ████████████████████ 100%  ✅ Complete
Gateway compatibility     ███████░░░░░░░░░░░░░░  33%  🚧 In progress
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
| Canonical AI contracts | ✅ P2 completed with deterministic contract and host proof |
| HTTP transport | ✅ P3 completed with deterministic construction, policy, SSE/metadata, registry, cancellation, cleanup, and terminal proof |
| OpenAI Responses adapter | ✅ P4 completed with deterministic, live, lifecycle, secret-safety, and package-boundary proof |
| Anthropic Messages adapter | ✅ P5 completed with deterministic, live, lifecycle, secret-safety, and package-boundary proof |
| OpenRouter and compatible adapters | ✅ P6 completed with deterministic, live, lifecycle, secret-safety, and package-boundary proof |
| OpenAI-compatible Gateway validation | 🚧 P7-A external contract freeze and deterministic fixtures completed in the current candidate |

On July 20, 2026, the Android sample's 3 controller tests passed, its debug APK assembled, and the app installed and launched on a local API 36.1 Pixel 8 emulator. UI inspection confirmed the version, one-shot response, five ordered stream events, stable simulated error, response cancellation, and stream stop. GitHub Actions run [29730678994](https://github.com/maneesh888/universal-ai-connector/actions/runs/29730678994) then passed the Android consumer and complete remote matrix as configured at the time, but its source-testing jobs ran against synthetic merge commit `4a4bd2d88bc62c663a58cb5bb1f8d4bdaccec2d9` rather than the exact branch head. Their platform results are bounded compatibility evidence; the run does not provide exact-head repository-hygiene proof.

### Milestone status

| Milestone | Description | Status |
|---|---|---|
| P0 | iOS-Kotlin interoperability POC | ✅ Completed |
| P1 | Cross-platform package and client samples | ✅ Completed |
| P2 | Canonical core and JSON contracts | ✅ Completed |
| P3 | HTTP transport and provider registry | ✅ Completed |
| P4 | OpenAI Responses adapter | ✅ Completed |
| P5 | Anthropic adapter | ✅ Completed |
| P6 | OpenRouter and compatible adapters | ✅ Completed |
| P7 | OpenAI-compatible Gateway validation | 🚧 In progress; P7-A completed in the current candidate |
| P8 | Production distribution and host integration | ⏳ Planned |
| P9 | Release hardening and internal alpha | ⏳ Planned |

### P1 completion

The product-facing Apple package and closing legacy-surface cleanup are accepted. P1 preserves one supported Kotlin client, one Swift façade, and deterministic JVM, Android, and iOS consumer paths while rejecting the retired POC product and exported symbols. Physical-device execution was not a P1 completion requirement and has not been performed.

The detailed implementation and acceptance criteria are in the [cross-platform client samples plan](docs/plans/cross-platform-client-samples.md).

### P2 through P6 completion and P7 activation

P2 was activated separately on July 24, 2026 after P1 completion. It defines provider-neutral Kotlin
contracts, governed JSON representations, compatibility fixtures, deterministic canonical
behavior, and Swift-native façade mappings without introducing provider DTOs. P3 completed
transport construction, lifecycle ownership, URL/header/timeout policy, bounded SSE, response
metadata, provider registration, cancellation, cleanup, terminal arbitration, and host-boundary
proof. P4 completed the internal OpenAI Responses adapter. P5-A recorded the direct Anthropic
Messages and authentication decisions. The dedicated Anthropic key/model gate is now satisfied,
so P5 resumed at P5-B with the internal non-streaming adapter and exact-head local live route.
P5-C then completed governed structured output, errors, and capabilities, P5-D completed streaming
translation and active cancellation, and P5-E completed concurrent lifecycle, close-race, cleanup,
supported-consumer, secret-safety, and package-boundary acceptance. P6-A recorded direct OpenRouter
Chat Completions, generic compatibility, credential, and live-routing boundaries, and P6-B
completed direct non-streaming OpenRouter request/response behavior and its exact-head local-live
gate. P6-C completed generic non-streaming construction, translation, safe error handling,
deterministic compatibility fixtures, and representative OpenRouter live coverage. P6-D completed
structured output, typed and safe errors, bounded metadata, conservative capabilities, and
targeted live coverage. P6-E completed incremental streaming, strict terminal handling, safe
mid-stream errors, and active cancellation. P6-F completed direct and generic concurrent
lifecycle behavior, cross-adapter close races, response-body cleanup, existing consumers,
secret-safety, and package boundaries. P7 was activated on August 11, 2026 after the external LLM
Gateway's tested standard contract was pinned. P7-A freezes that conservative intersection and
adds deterministic compatibility fixtures through the existing generic adapter; it adds no live
task or Gateway-specific runtime surface.

## Architecture direction

Applications will consume Universal AI Connector models rather than provider DTOs:

```text
Application
    -> Universal AI Connector client
    -> provider adapter
    -> provider or OpenAI-compatible LLM Gateway endpoint
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
- Simple construction creates the supported platform transport without requiring an application to construct a Ktor `HttpClient`; advanced callers may inject a caller-owned `HttpClientEngine`.
- Host coroutine or task cancellation propagates into connector work.
- Samples consume public package boundaries and remain thin presentation layers.

Native Linux, Windows, and macOS artifacts are demand-driven. The initial desktop/server path is Kotlin/JVM; Java-specific, JavaScript, and Wasm façades are not currently committed support surfaces.

P8 will add one installable Compose Multiplatform desktop demonstration for macOS, Windows, and Linux. It will preserve a zero-configuration deterministic mode and add an opt-in live mode only after the corresponding provider adapter or OpenAI-compatible Gateway validation is complete. The JVM console remains the headless and server-oriented verification path.

The current Kotlin client is `com.maneesh.universalai.connector.UniversalAiConnector`. It is reusable, concurrent, and thread-safe. It owns no coroutine scope: `respond` and the cold `stream` flow run in the caller's coroutine context, and caller cancellation stops the active operation. Default construction does own the platform transport resources, so every connector must be closed at its host lifecycle boundary. `close()` is synchronous and idempotent. An injected Ktor engine remains caller-owned and usable after its connector closes.

Provider configuration is immutable and provider-neutral. Applications supply a synchronous
credential loader owned by the host; the connector invokes it once per network request and does
not read environment files or application storage. Provider base URLs require HTTPS. Plaintext
HTTP is accepted only for exact loopback hosts (`localhost`, canonical IPv4 addresses in
`127.0.0.0/8`, or IPv6 loopback `::1`) so local mock servers remain usable without allowing a
credentialed request to cross a non-loopback network in cleartext:

```kotlin
fun openAiConnector(loadCredential: () -> String): UniversalAiConnector =
    UniversalAiConnector(
        UniversalAiConnectorConfiguration(
            providers =
                listOf(
                    UniversalAiProviderConfiguration(
                        providerId = ProviderId.of("openai"),
                        baseUrl = "https://api.openai.com/v1",
                        credentialSupplier = loadCredential,
                    ),
                ),
        ),
    )
```

The Swift façade exposes the same boundary without provider DTOs:

```swift
func openAiConnector(
    loadCredential: @escaping @Sendable () throws -> String
) throws -> UniversalAiConnector {
    let provider = UniversalAiProviderConfiguration(
        providerId: UniversalAiProviderId(rawValue: "openai"),
        baseURL: "https://api.openai.com/v1",
        credentialSupplier: loadCredential
    )
    return try UniversalAiConnector(
        configuration: UniversalAiConnectorConfiguration(
            providers: [provider]
        )
    )
}
```

## Quick start

### Contributor setup

The source checkout uses standard command-line tools in addition to Xcode. The `env` command
mentioned below is the operating system utility that runs a command with temporary environment
variables; it is not a credentials file and new contributors do not create it.

| Tool | Why this repository needs it |
|---|---|
| Git and Bash | Source control, hooks, and committed verification scripts |
| Ripgrep (`rg`) | Fail-closed secret scanning |
| Java 21 JDK | Gradle, Kotlin Multiplatform, Android, and JVM builds |
| Android SDK platform 36 and Build Tools 36.1.0 | Shared Android artifact and consumer checks |
| Xcode on Apple silicon macOS | Kotlin/Native, Swift Package, and iOS sample verification |
| iOS 17 or newer simulator runtime | Apple simulator tests and sample build |

GitHub Apple verification currently runs with Xcode 16.4, and the complete local gate has also
passed with Xcode 26.0. The minimum supported Xcode version has not been established; the
preflight checks that the selected Xcode can run and resolve the iOS Simulator SDK, and the actual
build remains the compatibility authority.

After installing those tools, run the read-only preflight before enabling hooks:

```bash
./scripts/check-environment.sh --full
./scripts/install-hooks.sh
```

The preflight does not install software or change shell files. It explains a missing or
misconfigured tool and stops before a long build begins. Use `--hygiene` when only documentation
or shell checks are needed, and `--quick` for the pre-commit toolchain.

If the preflight reports a non-standard `env`, a personal executable is shadowing the operating
system command. Diagnose it with:

```bash
type -a env
env UAC_ENV_COMMAND_PROBE=works \
  /bin/sh -c 'test "$UAC_ENV_COMMAND_PROBE" = works'
```

Rename the conflicting personal executable and keep PATH setup in `.zprofile`, `.zshrc`, or the
matching profile for the contributor's shell. Do not change the repository security test to
accommodate a command that does not implement standard `env NAME=value command` behavior.

The Gradle wrapper is committed, so a separate Gradle installation is not required.

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
- canonical schema, fixture, serialization, and semantic-validation tests on JVM, Android host, and iOS Simulator
- product-facing shared and Apple-adapter tests on iOS Simulator
- device-and-simulator XCFramework generation and slice validation
- product-facing Swift Package integration tests
- iOS simulator sample build
- generic iOS-device sample link/build
- secret scanning
- Git whitespace validation

Enable the mandatory local commit and push gates once per clone:

```bash
./scripts/install-hooks.sh
```

The pre-commit hook runs the quick cross-platform suite. The pre-push hook requires a clean
worktree and runs the complete deterministic suite. `scripts/live-impact.sh` returns the delivered
providers affected relative to `origin/main`, and pre-push runs every selected exact-head local
gate in stable order. Do not bypass either hook.

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
./scripts/check-contracts.sh --all
./scripts/secret-scan.sh
git diff --check
```

For provider-impacting development, create the ignored local input manually. The runner never
opens or sources it automatically:

```bash
cp .env.live.example .env.live
chmod 600 .env.live
${EDITOR:-vi} .env.live
git check-ignore -q .env.live

set -a
source .env.live
set +a
./scripts/check-live.sh openai
./scripts/check-live.sh anthropic
./scripts/check-live.sh openrouter
```

The same ignored file contains distinct provider-specific key/model entries. P5-B delivers the
Anthropic route alongside the existing OpenAI and OpenRouter routes. Set provider values only in
the local editor and run only the affected delivered providers.
Missing inputs, unavailable model access, quota/rate limits, provider failures, and assertions are
blockers rather than skipped tests. GitHub remains credential-free; an affected PR records the
passing exact SHA and no-retention boundary for the automatic `live-policy` evidence check. See
[`docs/LIVE_PROVIDER_TESTING.md`](docs/LIVE_PROVIDER_TESTING.md).

The Xcode scripts prefer the newest available `iPhone 17 Pro` simulator. Override the destination when necessary:

```bash
UAC_SIMULATOR_NAME='iPhone 16' ./scripts/test-swift-package.sh

UAC_SIMULATOR_DESTINATION='platform=iOS Simulator,id=<simulator-udid>' \
  ./scripts/test-swift-package.sh
```

## Kotlin/JVM sample

The console sample declares only `implementation(project(":bridge"))` for connector behavior. It does not copy or compile shared sources and imports no internal or callback-bridge packages.

The first-use path is:

```kotlin
import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput

fun request(content: String) =
    UniversalAiRequest(
        target = UniversalAiTarget(
            providerId = ProviderId.of("deterministic"),
            modelId = ModelId.of("echo-v1"),
        ),
        input = listOf(
            UniversalAiTextInput(
                role = UniversalAiInputRole.User,
                content = content,
            ),
        ),
    )

val connector = UniversalAiConnector()
try {
    println(connector.version)
    val response = connector.respond(request("hello from JVM"))
    println(checkNotNull(response.outputs.single().text))

    connector.stream(request("stream")).collect { event ->
        println("${event.sequence}: ${event.type.rawValue} ${event.delta.orEmpty()}")
    }
} finally {
    connector.close()
}
```

Failures are delivered as `UniversalAiException` carrying a canonical category, raw-preserving code, stable safe message, optional metadata, and extensions. Cancellation remains caller-owned `CancellationException`; the sample cancels a one-shot request and stops a stream at its first output delta. Its `finally` block closes the connector on success, failure, or cancellation.

The Kotlin API is hidden from Objective-C export so Apple consumers use the supported Swift façade. An Apple-only callback adapter delegates to the same Kotlin client without exporting `Flow` or Kotlin implementation types through the Swift API. It is compiled into the iOS frameworks as an implementation dependency of the supported Swift product and is excluded from the JVM JAR and Android AAR; those non-Apple artifact boundaries are checked by the repository gate. The XCFramework build validates both Apple headers and fails if the product Kotlin client or `Flow` leaks into either one.

## Android sample

The Jetpack Compose application declares `implementation(project(":bridge"))` and uses the same `UniversalAiConnector` entry point as the JVM sample. `MainActivity` owns the coroutine lifetime through its lifecycle scope. Its controller cancels the active job and then closes the connector from `onDestroy`, releasing connector-owned transport resources without making the connector own the activity's coroutine scope.

The minimal application path is:

```kotlin
private val connector = UniversalAiConnector()
private var activeJob: Job? = null
private val request = UniversalAiRequest(
    target = UniversalAiTarget(
        providerId = ProviderId.of("deterministic"),
        modelId = ModelId.of("echo-v1"),
    ),
    input = listOf(
        UniversalAiTextInput(
            role = UniversalAiInputRole.User,
            content = "hello from Android",
        ),
    ),
)

activeJob = lifecycleScope.launch {
    val response = connector.respond(request)
    println(checkNotNull(response.outputs.single().text))

    connector.stream(request).collect { event ->
        println("${event.sequence}: ${event.type.rawValue} ${event.delta.orEmpty()}")
    }
}

override fun onDestroy() {
    activeJob?.cancel()
    connector.close()
    super.onDestroy()
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
    defer {
        connector.close()
    }
    let request = UniversalAiRequest(
        target: UniversalAiTarget(
            providerId: UniversalAiProviderId(rawValue: "deterministic"),
            modelId: UniversalAiModelId(rawValue: "echo-v1")
        ),
        input: [
            UniversalAiTextInput(
                role: .user,
                content: "hello from Swift"
            ),
        ]
    )
    let response = try await connector.respond(to: request)
    print(response.outputs.first?.text ?? "No text output.")

    for try await event in connector.stream(request: request) {
        print("\(event.sequence): \(event.type.rawValue) \(event.delta ?? "")")
    }
}
```

`UniversalAiConnector` is reusable, thread-safe, and supports concurrent responses and independently created streams. Each returned stream has one consuming task; concurrent iteration of the same stream is outside the supported contract. Each operation runs independently, and the façade owns no caller task or long-lived coroutine job. The calling Swift task owns response lifetime, and the consuming task owns stream lifetime. Cancelling either task propagates to that Kotlin operation, including cancellation that races with handle installation.

Callers that need to stop a stream promptly must cancel its consuming task, as the sample does after the first event. A plain `break` does not itself guarantee prompt cancellation while the returned `AsyncThrowingStream` remains retained; the underlying operation is cancelled when its iterator and stream are released, or it may complete normally if retained. The owning Swift object should cancel its tasks and call the connector's synchronous, idempotent `close()` at its lifecycle boundary. Closing cancels any still-active connector operations and releases connector-owned transport resources; `deinit` performs the same close as a safety net.

Failures arrive as `UniversalAiConnectorError`; Swift task cancellation remains `CancellationError`. The sample owns its tasks, cancels them when its view disappears, and closes the connector when its view model deinitializes. It provides explicit controls for:

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
contracts/              Versioned JSON Schemas and compatibility fixtures
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

The complete P1 package and its acceptance evidence are recorded in [`docs/plans/cross-platform-client-samples.md`](docs/plans/cross-platform-client-samples.md). The completed P2 decision and implementation sequence is documented in [`docs/plans/canonical-core-json-contracts.md`](docs/plans/canonical-core-json-contracts.md).

Provider and gateway work begins only after the cross-platform package foundation and canonical contracts are stable. Production Maven and remote Swift Package distribution is planned for P8 after the client contract and transport are established.

## License

Universal AI Connector is available under the MIT License. See [`LICENSE`](LICENSE).
