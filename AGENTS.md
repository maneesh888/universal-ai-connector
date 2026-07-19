# Universal AI Connector Workflow

## Scope

This repository contains a verified iOS-Kotlin interoperability POC and the plans for evolving it into the Universal AI Connector V2 package.

The next approved scope is cross-platform targets, shared interoperability tests, thin iOS, Android, and JVM sample clients, and a measurable host-integration baseline. Do not add provider adapters, Ktor networking, canonical AI contracts, gateway integration, artifact publication, or OpenKeyboard integration before their work package is active in `docs/plans/universal-ai-connector-v2.md`.

## Start Every Task

1. Resolve the root with `git rev-parse --show-toplevel`.
2. Inspect `git status --short --branch`.
3. Read `docs/plans/universal-ai-connector-v2.md`, `docs/DEVELOPMENT_WORKFLOW.md`, and the active work-package plan completely.
4. Execute only one bounded work package unless the user explicitly authorizes a larger batch.
5. Preserve unrelated changes and generated artifacts.
6. Use the Gradle wrapper and repository scripts.
7. Do not commit or push unless explicitly authorized.

## Current Verification

- Hygiene only: `./scripts/check.sh --hygiene`
- Fast local check: `./scripts/check.sh --quick`
- Complete deterministic check: `./scripts/check.sh --full`
- JVM shared tests: `./gradlew :bridge:jvmTest`
- JVM console consumer: `./gradlew :samples:jvm-console:consumerCheck`
- JVM console application: `./gradlew :samples:jvm-console:run`
- Android shared host tests: `./gradlew :bridge:testAndroidHostTest`
- Android library AAR: `./gradlew :bridge:bundleAndroidMainAar`
- Kotlin bridge tests: `./gradlew :bridge:iosSimulatorArm64Test`
- XCFramework: `./scripts/build-xcframework.sh`
- Swift Package and simulator tests: `./scripts/test-swift-package.sh`
- Sample simulator build: `./scripts/build-sample.sh`
- Secret scan: `./scripts/secret-scan.sh`
- Final whitespace check: `git diff --check`

Set `POC_SIMULATOR_DESTINATION` to override the default Xcode destination.

As of July 19, 2026, GitHub Actions prove repository hygiene, JVM tests on Linux, Windows, and macOS, Android host tests and AAR packaging on Linux, and the P0 Apple interoperability path on pull requests. The current P1 workflow also exercises the JVM console consumer in those existing operating-system jobs; record that portability proof only after its pull-request matrix passes. CI still does not prove iOS device, Android application, provider, gateway, or release behavior.

## Host Integration Standard

- Treat Android, iOS, and Kotlin/JVM as the supported application surfaces for the initial alpha. Prove JVM portability on Linux, Windows, and macOS before claiming those host operating systems.
- Give Kotlin consumers one shared client entry point with idiomatic `suspend` and `Flow` APIs. Give Swift consumers one Swift façade with `async` functions, `AsyncThrowingStream`, Swift errors, and Swift cancellation.
- Keep callback bridges, coroutine scopes, Kotlin implementation types, generated Objective-C names, and packaging details out of supported host-facing APIs.
- Provide a simple default construction path. Keep transport injection and other advanced configuration available without making them mandatory for first use once P3 activates networking.
- Treat samples as external consumers: they must use the packaged module or artifact boundary and must not reach into internal source sets or implementation packages.
- A platform is integration-verified only when its clean consumer sample builds from documented instructions and exercises response, streaming, stable errors, and cancellation where supported.
- Keep remote Maven and Swift Package publication in P8. P1 establishes local package boundaries, consumer samples, and copy-paste-ready integration documentation.

## Development Tools

- Use the GitHub connector for remote PR, issue, workflow, log, and artifact inspection when it is available.
- Use official provider-documentation MCPs for current external API contracts only when the corresponding provider milestone is active.
- Use Xcode or simulator MCPs for exact platform proof when available; otherwise use the repository Xcode scripts and report the missing proof surface.
- Treat MCPs as development tools, never runtime package dependencies.
- Do not store MCP tokens or credentials in the repository.

## Architecture Rules

- Keep platform-neutral behavior in Kotlin `commonMain`.
- Keep sample applications thin; they demonstrate the shared API and do not duplicate connector behavior.
- Keep one shared Kotlin client contract for Android and JVM consumers; do not create platform-specific connector behavior merely to simplify a sample.
- Keep Kotlin `Flow`, coroutine types, and Kotlin implementation types behind the Swift callback bridge.
- Keep the supported Apple API in the Swift façade.
- Preserve idiomatic host lifecycle behavior: caller cancellation must propagate, terminal callbacks must be exactly once, and owned resources must have a documented cleanup path.
- Use deterministic fake implementations for samples and normal CI until live-provider work is explicitly active.
- Keep provider DTOs internal to their future provider modules.
- Do not introduce OpenKeyboard actions, prompts, storage, or UI into this package.

## Safety

- Never commit generated XCFrameworks, build directories, DerivedData, `.xcresult` bundles, logs, credentials, or secrets.
- Never print API keys or Authorization headers.
- Before committing, inspect `git status --short --branch`, run the active verification suite, stage only intended files, and inspect `git diff --cached --name-only`.
