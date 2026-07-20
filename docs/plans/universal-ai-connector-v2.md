# Universal AI Connector V2 Roadmap

## Status

- Repository stage: interoperability POC verified; P1 cross-platform baseline in progress
- Current implementation: iOS Simulator delivery proof, JVM and Android common-test targets, one product-facing Kotlin client, and local JVM console and Android application consumers
- Active work package: P1, cross-platform package and client-sample baseline
- Next bounded P1 package: product-facing Apple delivery/sample upgrade with an iOS ARM64 device slice
- Package version target: `0.1.0-alpha.1`
- Initial host surfaces: Android, iOS, and Kotlin/JVM on Linux, Windows, and macOS
- Gateway and OpenKeyboard integration: deferred

This document is the package repository's source of truth for implementation order. Complete one work package at a time and record verification evidence before advancing.

## Product boundary

Universal AI Connector is an independent Kotlin Multiplatform package. It must not depend on OpenKeyboard, SwiftUI, App Group storage, Keychain storage, keyboard actions, keyboard prompts, or Gateway V1 DTOs.

The package will own provider-neutral public models. Provider and gateway protocols remain internal adapters:

```text
Canonical request
    -> provider adapter
    -> provider request/response
    -> canonical response/error/stream events
```

Initial foundations:

- Kotlin Multiplatform
- kotlinx.coroutines and `Flow`
- kotlinx.serialization
- Ktor client with an injectable `HttpClient` or engine
- deterministic fake providers and Ktor `MockEngine` tests

## Host integration and platform strategy

The initial alpha optimizes for broad practical reach without maintaining every Kotlin/Native target:

| Host surface | Initial delivery | Verification expectation |
|---|---|---|
| Android | Kotlin Multiplatform Android library | Shared tests, AAR packaging, and Android sample build |
| iOS | Swift façade over a device-and-simulator XCFramework | Kotlin/Native tests, Swift Package tests, and SwiftUI sample builds |
| Linux | Kotlin/JVM artifact | JVM tests and console consumer on Linux CI |
| Windows | Kotlin/JVM artifact | JVM tests and console consumer on Windows CI |
| macOS | Kotlin/JVM artifact plus the Apple delivery toolchain | JVM consumer proof and the Apple verification suite on macOS CI |

Native macOS ARM64 and Linux X64 may be added when a no-JVM or native-language consumer requires them. Windows Kotlin/Native, JavaScript, and Wasm remain demand-driven. A host is not described as supported merely because the compiler can produce a target: the repository must also test its public API, packaging, documented consumption path, and lifecycle behavior.

The initial JVM console remains the headless and server-oriented proof. P8 must add one Compose Multiplatform desktop demonstration application that runs from the same JVM code on macOS, Windows, and Linux. It must offer a zero-configuration deterministic mode for evaluation and an explicitly configured live mode once provider and Gateway adapters exist. Native desktop library targets remain demand-driven; the demonstration application consumes the Kotlin/JVM artifact.

The host-facing developer experience must converge on:

- one documented dependency path per host surface;
- one primary client entry point;
- a simple default configuration plus optional advanced injection;
- idiomatic Kotlin `suspend`/`Flow` and Swift `async`/`AsyncThrowingStream` behavior;
- stable host-native errors and cancellation;
- samples that consume package boundaries rather than internal source shortcuts;
- installation and first-use snippets kept executable by consumer smoke tests.

## Milestones

| ID | Work package | Status | Evidence |
|---|---|---|---|
| P0 | iOS-Kotlin interoperability POC | Completed | 6 Kotlin tests, 8 Swift tests, XCFramework and sample build passed July 17, 2026 |
| P1 | Cross-platform package and client-sample baseline | In progress | Product Kotlin API and JVM console passed locally and in CI run 29698575249; Android app tests, APK, and API 36.1 emulator launch passed locally July 20; Apple sample upgrade and iOS device remain |
| P2 | Canonical core and JSON contracts | Not started | |
| P3 | HTTP transport and provider registry | Not started | |
| P4 | OpenAI Responses adapter | Not started | |
| P5 | Anthropic adapter | Not started | |
| P6 | OpenRouter and OpenAI-compatible adapters | Not started | |
| P7 | Universal Gateway V2 adapter | Not started | |
| P8 | Production distribution and host integration | Not started | |
| P9 | Release hardening and internal alpha | Not started | |

Only one row may be `In progress` at a time.

## P0 completion boundary

The accepted POC proves:

- Swift imports a Kotlin/Native XCFramework through a local Swift Package.
- Swift calls synchronous and asynchronous Kotlin functions.
- Kotlin `Flow` is exposed as Swift `AsyncThrowingStream`.
- Kotlin failures map to stable Swift errors.
- Swift task and stream cancellation cancel Kotlin coroutine jobs.
- The standalone iOS sample compiles for an iOS Simulator.

P0 does not prove provider networking, canonical AI behavior, Android/JVM consumption, iOS device distribution, or OpenKeyboard integration.

## P1: Cross-platform package and client-sample baseline

Implement the work package in `cross-platform-client-samples.md` without adding real provider networking.

Acceptance requires:

- JVM, Android, iOS ARM64, and iOS Simulator ARM64 targets compile.
- Shared deterministic behavior is tested from common code.
- JVM console, Android, and iOS Swift samples consume the same shared client contract.
- Samples compile as external consumers through documented package boundaries and do not import internal implementation packages.
- The Apple XCFramework contains device and simulator slices.
- Async response, streaming, stable errors, and cancellation remain covered.
- The supported Kotlin and Swift entry points have documented construction, lifecycle, concurrency, cancellation, and cleanup behavior.
- Linux, Windows, and macOS CI prove the Kotlin/JVM consumer path before JVM host-OS portability is claimed.
- Generated artifacts and secrets remain excluded from Git.

## P2: Canonical core and JSON contracts

Define provider-neutral identifiers, targets, inputs, response formats, generation parameters, responses, outputs, usage, capabilities, model descriptors, errors, and streaming events.

Before implementation, close these decisions in ADRs:

- error delivery and cancellation semantics;
- streaming event and terminal-event model;
- provider and model capability representation, including unknown support;
- provider-extension mechanism;
- JSON Schema subset and validation policy;
- schema/OpenAPI/Kotlin source-of-truth policy;
- contract versioning and compatibility rules.

Acceptance requires schema-valid fixtures, unknown-field compatibility, unknown-value compatibility, and no vendor DTOs in public signatures.

The canonical API must remain small enough for one primary client entry point. Provider extensions must not force ordinary consumers to handle vendor DTOs or construct provider-specific request objects.

## P3: HTTP transport and provider registry

Add injectable Ktor transport, base URL normalization, safe header handling, timeouts, SSE parsing, request-ID and retry-after extraction, cancellation propagation, and log redaction.

Default construction must select supported platform transport behavior without requiring ordinary consumers to create a Ktor `HttpClient`. Advanced consumers may inject a transport or engine for control and deterministic tests. Resource ownership and cleanup must be explicit for both paths.

Generation retries remain disabled by default. Never reconnect or retry after response content begins.

## P4-P7: Adapters

Implement adapters in order:

1. OpenAI Responses
2. Anthropic Messages
3. OpenRouter and generic OpenAI-compatible endpoints
4. Universal Gateway V2 canonical protocol

Each adapter owns its provider DTOs, request translation, response translation, structured-output handling, streaming translation, capability reporting, and canonical error mapping. Live tests remain opt-in and secret-safe.

## P8: Production distribution and host integration

Promote the POC bridge into a stable Swift façade and production XCFramework containing device and simulator slices. Publish Android/JVM artifacts through documented Maven coordinates and Apple artifacts through a remote Swift Package. Add an installable Compose Multiplatform desktop demonstration application for macOS, Windows, and Linux. Define signing and checksums where required, synchronized versioning, API compatibility policy, and clean-consumer compatibility tests.

Acceptance requires:

- one copy-paste dependency declaration for Android/JVM and one remote Swift Package dependency for Apple;
- consumer fixtures that resolve released artifacts rather than repository source projects;
- compiled first-use examples for Kotlin and Swift;
- user-visible Android, iOS, and desktop demonstrations covering response, streaming, stable errors, and cancellation;
- a desktop deterministic mode that starts without an account, network, gateway, provider credential, or secret;
- an opt-in desktop live mode that accepts host-provided adapter configuration only after the corresponding adapter milestone is complete;
- Gateway client configuration limited to its base URL and gateway credential provider, with provider credentials remaining on the Gateway server and no secret logging or committed credentials;
- self-contained desktop distributions built and smoke-tested on their matching macOS, Windows, and Linux hosts;
- documented minimum toolchain and platform versions;
- no manual framework copying, generated artifact commits, or repository-specific build steps for consumers.

## P9: Alpha release

Release `0.1.0-alpha.1` only after:

- deterministic tests pass on JVM, Android, and iOS;
- all initial adapters pass request, response, error, structured-output, streaming, and cancellation tests;
- Swift distribution and samples are verified;
- the Android, iOS, and desktop demonstration screens are launch-tested and retain deterministic no-secret modes;
- documented Android, iOS, JVM/Linux, JVM/Windows, and JVM/macOS consumer paths resolve and compile from released artifacts;
- API compatibility and secret scans pass;
- public API documentation and known limitations are published.

## Deferred work

The following remain outside this package roadmap until explicitly activated:

- Gateway V2 server implementation
- OpenKeyboard application and keyboard-extension migration
- provider-selection UI and credential storage
- billing, quotas, server routing, and server model allowlists
- agent frameworks, tool execution, RAG, and multimodal inputs
- native desktop library targets without a demonstrated no-JVM or native-language consumer requirement; the planned P8 graphical desktop demo uses Kotlin/JVM
- Java-specific, JavaScript, and Wasm façades until their consumer demand and maintenance cost are approved

## Session reporting

Every completed work package must report:

- repository branch or worktree;
- files and modules changed;
- tests and builds executed;
- pass/fail results;
- public contract changes;
- generated contract fixtures;
- remaining risks;
- next incomplete work package;
- commit ID when committed.
