# Universal AI Connector V2 Roadmap

## Status

- Repository stage: interoperability POC verified; P1 cross-platform baseline in progress
- Current implementation: iOS Simulator delivery proof plus JVM and Android common-test targets
- Active work package: P1, cross-platform package and client-sample baseline
- Package version target: `0.1.0-alpha.1`
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

## Milestones

| ID | Work package | Status | Evidence |
|---|---|---|---|
| P0 | iOS-Kotlin interoperability POC | Completed | 6 Kotlin tests, 8 Swift tests, XCFramework and sample build passed July 17, 2026 |
| P1 | Cross-platform package and client-sample baseline | In progress | JVM and Android targets compiled, 6 common tests passed on each, Android AAR assembled, and P0 regressed July 19, 2026; samples and iOS device remain |
| P2 | Canonical core and JSON contracts | Not started | |
| P3 | HTTP transport and provider registry | Not started | |
| P4 | OpenAI Responses adapter | Not started | |
| P5 | Anthropic adapter | Not started | |
| P6 | OpenRouter and OpenAI-compatible adapters | Not started | |
| P7 | Universal Gateway V2 adapter | Not started | |
| P8 | Production Swift and Apple distribution | Not started | |
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
- The Apple XCFramework contains device and simulator slices.
- Async response, streaming, stable errors, and cancellation remain covered.
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

## P3: HTTP transport and provider registry

Add injectable Ktor transport, base URL normalization, safe header handling, timeouts, SSE parsing, request-ID and retry-after extraction, cancellation propagation, and log redaction.

Generation retries remain disabled by default. Never reconnect or retry after response content begins.

## P4-P7: Adapters

Implement adapters in order:

1. OpenAI Responses
2. Anthropic Messages
3. OpenRouter and generic OpenAI-compatible endpoints
4. Universal Gateway V2 canonical protocol

Each adapter owns its provider DTOs, request translation, response translation, structured-output handling, streaming translation, capability reporting, and canonical error mapping. Live tests remain opt-in and secret-safe.

## P8: Swift and Apple distribution

Promote the POC bridge into a stable Swift façade and production XCFramework containing device and simulator slices. Define local and remote Swift Package distribution, artifact checksum production, versioning, and compatibility tests.

## P9: Alpha release

Release `0.1.0-alpha.1` only after:

- deterministic tests pass on JVM, Android, and iOS;
- all initial adapters pass request, response, error, structured-output, streaming, and cancellation tests;
- Swift distribution and samples are verified;
- API compatibility and secret scans pass;
- public API documentation and known limitations are published.

## Deferred work

The following remain outside this package roadmap until explicitly activated:

- Gateway V2 server implementation
- OpenKeyboard application and keyboard-extension migration
- provider-selection UI and credential storage
- billing, quotas, server routing, and server model allowlists
- agent frameworks, tool execution, RAG, and multimodal inputs

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
