# Universal AI Connector V2 Roadmap

## Status

- Repository stage: P2 canonical core and JSON contracts completed; P3 not started
- Current implementation: the accepted P1 host baseline plus the provider-neutral P2 canonical-contract baseline
- Active work package: none; P3 requires separate activation
- Accepted Apple surface: PR [#9](https://github.com/maneesh888/universal-ai-connector/pull/9) passed local full verification, independent exact-head review, and exact-head GitHub Actions run [29826390650](https://github.com/maneesh888/universal-ai-connector/actions/runs/29826390650), then merged July 21, 2026
- P1 completion evidence: closing head `fdf33e5d197f13f5ab32f23cfc290ad263451946` passed the complete local gate and independent review; exact-head run [29991895652](https://github.com/maneesh888/universal-ai-connector/actions/runs/29991895652) passed; PR [#12](https://github.com/maneesh888/universal-ai-connector/pull/12) merged July 23, 2026; and resulting `main` run [29993494307](https://github.com/maneesh888/universal-ai-connector/actions/runs/29993494307) passed
- P2 completion: ADRs 0001-0007 and P2-D readiness are accepted; P2-E through P2-J delivered canonical Kotlin and Swift host contracts, 21 authoritative schemas, 173 fixture documents, deterministic host verification, and atomic closeout evidence in the milestone-closing pull request
- P2 closeout authority: the transition in this milestone-closing candidate is accepted only after exact-head review and required checks pass, the pull request merges, and the resulting `main` workflow is inspected; those self-referential identifiers belong in the pull-request brief
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

## Platform-complexity budget

Cross-platform delivery is a foundation cost, not a platform tax that every later feature may repay. Reuse the P1 host, packaging, sample, and CI boundaries, and keep later behavior behind the shared contracts unless a milestone explicitly changes those contracts.

| Milestones | Expected platform cost | Allowed host-surface change |
|---|---|---|
| P1 | High, one-time foundation | Establish targets, package boundaries, thin samples, lifecycle behavior, and the deterministic host matrix |
| P2-P3 | Controlled contract stabilization | Finalize canonical models, the primary client contract, construction, transport injection, ownership, and cleanup without adding host targets or provider-specific host APIs |
| P4-P7 | Low | Implement provider and Gateway behavior in shared/internal adapter modules and tests; reuse the stable Kotlin and Swift entry points and existing samples |
| P8 | High, planned distribution work | Add publication, released-artifact consumers, signing/checksums, and the desktop demonstration without duplicating connector behavior per host |
| P9 | Verification and hardening | Exercise the complete matrix and fix defects; do not introduce a new platform surface as incidental release work |

Apply these guardrails to every future work package:

- Do not add another host target, sample, or CI lane without an approved consumer requirement and an explicit maintenance-cost decision.
- During P2-P3, change the supported Kotlin or Swift façade only when the canonical contract, construction, or lifecycle requires it. After P3 acceptance, keep those host entry points stable through P7 except for an approved compatibility, correctness, or security fix.
- P4-P7 must not add per-provider Swift, Android, or JVM implementations, DTOs, controls, or lifecycle paths. Provider differences stay behind canonical shared contracts and internal adapter modules.
- Keep the Android, iOS, and JVM samples as stable contract consumers. Update all samples only when an approved canonical host behavior changes, not merely because another provider adapter is added.
- Use affected-module and targeted host tests during implementation. Run the repository's mandatory quick gate at commit time and the complete supported platform matrix at push, pull-request, and release gates rather than repeatedly in the inner edit loop.
- If a proposed P3-P7 feature materially requires changes across the shared API, Swift façade, Android/JVM host API, all samples, packaging scripts, and CI lanes, pause implementation. Record the cross-platform reason in an ADR or scoped plan decision and either correct the abstraction or explicitly approve the wider platform cost before proceeding.
- Do not reintroduce the retired POC Swift or callback surfaces. Later milestones extend the single product-facing Kotlin client and Swift façade established by P1.

## Live provider and gateway verification gate

Keep the normal commit, push, pull-request, and GitHub Actions path deterministic and secretless. Beginning with P4, any change that can affect live provider or Gateway behavior must also pass the affected live suite locally before the initial pull request is created and before every later push that updates that pull request. This includes later changes to the shared P3 transport, authentication, streaming, retry, error-mapping, or log-redaction paths after a live adapter exists.

P4 must introduce a separate `./scripts/check-live.sh` entry point before its first adapter pull request. The live gate must:

- read credentials only from the developer's process environment or an OS-backed credential store; any optional local env file must be ignored by Git and accompanied only by a value-free example;
- use dedicated, revocable, low-quota test credentials and never print credentials, authorization headers, full request bodies containing sensitive input, or unredacted provider responses;
- run the deterministic adapter suite first, then the smallest live response, streaming, error, and cancellation smoke tests needed for the affected provider or Gateway;
- bind its evidence to the exact commit SHA and record the command, provider or Gateway target, model or test fixture, execution date, result, and proof boundaries in the pull-request review brief; and
- treat a missing credential, unavailable provider, rate limit, or failed assertion as a blocked pull-request creation or update for affected live behavior, not as a skipped success.

Any head change invalidates earlier local live evidence and requires the affected live suite to run again before the updated head is pushed. Documentation-only and unrelated deterministic changes do not require live credentials.

After the draft pull request is created, the same affected live suite must run for the exact head through a protected GitHub Environment and act as a mandatory readiness and merge condition. Before the first P4 pull request, add its stable status to branch protection or make it a server-enforced dependency of a required aggregator; an Environment alone does not make the status a merge requirement. The live status must complete successfully for the exact independently reviewed head before the pull request leaves draft or any merge command runs; pending or skipped live verification is a blocker. Run that secret-bearing workflow only for trusted heads with least-privilege credentials and required approval; never expose secrets to fork pull requests or execute untrusted pull-request code through `pull_request_target`. The ordinary `ci.yml` workflow remains read-only and secretless. A maintainer must run the protected live gate on a trusted head when a fork contribution affects live behavior.

## Milestones

| ID | Work package | Status | Evidence |
|---|---|---|---|
| P0 | iOS-Kotlin interoperability POC | Completed | 6 Kotlin tests, 8 Swift tests, XCFramework and sample build passed July 17, 2026 |
| P1 | Cross-platform package and client-sample baseline | Completed | Product Kotlin API, JVM console, Android app, and Apple façade/sample accepted; closing head `fdf33e5d197f13f5ab32f23cfc290ad263451946` passed local verification, independent review, exact-head run 29991895652, PR #12 merge, and resulting `main` run 29993494307 |
| P2 | Canonical core and JSON contracts | Completed | Provider-neutral Kotlin and Swift host contracts, 21 authoritative schemas, 173 fixture documents, deterministic compatibility checks, and host consumers; exact-head closeout evidence belongs in the milestone-closing pull-request brief |
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

Use the active work package in `canonical-core-json-contracts.md`. P2 was activated only after P1
completed and P2 became the sole milestone marked `In progress`; ADRs 0001-0007 and the P2-D
readiness review now govern its implementation.

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

P3 verification remains deterministic through Ktor `MockEngine` and local fixtures because no provider adapter is active yet. Once P4 establishes the live suite, any later P3 change that can affect live behavior is subject to the local pre-PR and protected GitHub live gates above.

## P4-P7: Adapters

Implement adapters in order:

1. OpenAI Responses
2. Anthropic Messages
3. OpenRouter and generic OpenAI-compatible endpoints
4. Universal Gateway V2 canonical protocol

Each adapter owns its provider DTOs, request translation, response translation, structured-output handling, streaming translation, capability reporting, and canonical error mapping. Each adapter milestone must add deterministic mock coverage and targeted live response, streaming, error, and cancellation smoke coverage. A pull request that adds or changes live adapter behavior may not be created or updated until the affected live suite passes locally for its exact head, and it may not merge until the protected GitHub Environment reruns that suite successfully for the same head.

P4 also establishes the secret-safety baseline required by live testing: ignored local secret files, a value-free environment example, documented credential names and rotation procedure, log-redaction assertions, and the separate `./scripts/check-live.sh` command. Provider credentials are host-supplied test inputs; they must never be embedded in mobile or desktop artifacts, committed configuration, normal CI, samples, or logs.

## P8: Production distribution and host integration

Harden and distribute the product-facing Swift façade and combined device-and-simulator XCFramework established in P1. Publish Android/JVM artifacts through documented Maven coordinates and Apple artifacts through a remote Swift Package. Add an installable Compose Multiplatform desktop demonstration application for macOS, Windows, and Linux. Define signing and checksums where required, synchronized versioning, API compatibility policy, and clean-consumer compatibility tests.

Acceptance requires:

- one copy-paste dependency declaration for Android/JVM and one remote Swift Package dependency for Apple;
- consumer fixtures that resolve released artifacts rather than repository source projects;
- compiled first-use examples for Kotlin and Swift;
- user-visible Android, iOS, and desktop demonstrations covering response, streaming, stable errors, and cancellation;
- a desktop deterministic mode that starts without an account, network, gateway, provider credential, or secret;
- an opt-in desktop live mode that accepts host-provided adapter configuration only after the corresponding adapter milestone is complete;
- Gateway client configuration limited to its base URL and gateway credential provider, with provider credentials remaining on the Gateway server and no secret logging or committed credentials;
- local pre-PR and protected GitHub live gates pass for any distribution or sample change that affects live provider or Gateway behavior;
- self-contained desktop distributions built and smoke-tested on their matching macOS, Windows, and Linux hosts;
- documented minimum toolchain and platform versions;
- no manual framework copying, generated artifact commits, or repository-specific build steps for consumers.

## P9: Alpha release

Release `0.1.0-alpha.1` only after:

- deterministic tests pass on JVM, Android, and iOS;
- all initial adapters pass deterministic and live request, response, error, structured-output, streaming, and cancellation tests on the exact release head;
- the complete live suite passes locally before the release pull request is created or updated and passes again through the protected GitHub Environment before merge;
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
