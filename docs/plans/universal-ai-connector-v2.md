# Universal AI Connector V2 Roadmap

## Status

- Repository stage: P6 OpenRouter and OpenAI-compatible adapters are completed in this milestone-closing candidate; P7-P9 remain not started
- Current implementation: the accepted P1 host baseline, P2 canonical-contract baseline, completed P3 provider-neutral transport foundation, completed P4 OpenAI Responses adapter, completed P5 Anthropic adapter, authoritative P6-A through P6-E packages, and the P6-F integrated lifecycle, close-race, cleanup, consumer, and boundary-acceptance candidate
- Active work package: P6-F lifecycle integration and acceptance is completed in this candidate; no later milestone is activated
- Accepted Apple surface: PR [#9](https://github.com/maneesh888/universal-ai-connector/pull/9) passed local full verification, independent exact-head review, and exact-head GitHub Actions run [29826390650](https://github.com/maneesh888/universal-ai-connector/actions/runs/29826390650), then merged July 21, 2026
- P1 completion evidence: closing head `fdf33e5d197f13f5ab32f23cfc290ad263451946` passed the complete local gate and independent review; exact-head run [29991895652](https://github.com/maneesh888/universal-ai-connector/actions/runs/29991895652) passed; PR [#12](https://github.com/maneesh888/universal-ai-connector/pull/12) merged July 23, 2026; and resulting `main` run [29993494307](https://github.com/maneesh888/universal-ai-connector/actions/runs/29993494307) passed
- P2 completion: ADRs 0001-0007 and P2-D readiness are accepted; P2-E through P2-J delivered canonical Kotlin and Swift host contracts, 21 authoritative schemas, 173 fixture documents, deterministic host verification, and atomic closeout evidence in the milestone-closing pull request
- P2 closeout authority: the transition in this milestone-closing candidate is accepted only after exact-head review and required checks pass, the pull request merges, and the resulting `main` workflow is inspected; those self-referential identifiers belong in the pull-request brief
- P3 completion: P3-A through P3-E delivered injectable Ktor transport, URL/header/timeout policy, bounded SSE and response metadata, immutable per-client provider registration, transport-bound adapter construction, cancellation/cleanup integration, and authoritative terminal arbitration through deterministic tests and existing host consumers
- P3 closeout authority: the transition in this milestone-closing candidate is accepted only after the full local gate, exact-head review and required checks, guarded merge, and resulting `main` workflow inspection pass; those self-referential identifiers belong in the pull-request brief
- P4 completion: P4-A through P4-E delivered the internal OpenAI Responses adapter, provider-neutral host configuration and credential supply, bounded non-streaming and structured translation, canonical errors and capabilities, incremental streaming, active cancellation, concurrent lifecycle and close-race coverage, fail-closed local live proof, and automated secret and package-boundary audits
- P4 closeout authority: the transition in this milestone-closing candidate is accepted only after the complete deterministic and live exact-head gates, exact-head ordinary CI and secretless live-policy status, independent review, guarded merge, and resulting `main` workflow inspection pass; those self-referential identifiers belong in the pull-request brief
- P5-A completion: activated P5 and recorded the direct Messages protocol, provider-neutral credential boundary, one-file provider-specific environment convention, structured-output decision, provider-aware impact selection, exact-head pre-push routing, and secretless regressions without adding Anthropic network behavior or a live task
- P5-A completion authority: exact head `a7d6fb2833140cbcd26b6a30f603c5c226e7a800` passed deterministic and affected OpenAI live gates, ordinary CI, secretless live-policy status, and independent review; PR [#39](https://github.com/maneesh888/universal-ai-connector/pull/39) merged as `5635ec01d72e7f627a9ad62ca0f97be039fe6b96`, and resulting `main` run [31170045356](https://github.com/maneesh888/universal-ai-connector/actions/runs/31170045356) passed
- P5-B completion authority: exact head `4f05c3b36e1a91761f68e5359950d1a5669bedbf` passed deterministic and affected Anthropic live gates, ordinary CI, secretless live-policy status, and independent review; PR [#42](https://github.com/maneesh888/universal-ai-connector/pull/42) merged as `17349ba41a8888d225f503c7d1ef7082bd42d6b6`, and resulting `main` run [31214200835](https://github.com/maneesh888/universal-ai-connector/actions/runs/31214200835) passed
- P5-C completion authority: exact head `e896dcc5e1504967f4228dae16b60db040869d86` passed deterministic and affected Anthropic live gates, ordinary CI, secretless live-policy status, and independent review; PR [#48](https://github.com/maneesh888/universal-ai-connector/pull/48) merged as `e09d548513449320f648b0be31f7251e8e802342`, and resulting `main` run [31220845292](https://github.com/maneesh888/universal-ai-connector/actions/runs/31220845292) passed
- P5-D completion authority: exact head `0d0b030b72ca204baeb27163d936d788e696292c` passed deterministic and complete delivered-provider live gates, ordinary CI, secretless live-policy status, and independent review; PR [#49](https://github.com/maneesh888/universal-ai-connector/pull/49) merged as `de00a1a9bc7770ab399dd3ae9222944fb54160ae`, and resulting `main` run [31318381641](https://github.com/maneesh888/universal-ai-connector/actions/runs/31318381641) passed
- P5 completion: P5-A through P5-E delivered direct Anthropic Messages readiness, provider-neutral credential supply, bounded non-streaming and structured translation, canonical errors and capabilities, incremental streaming, cancellation, concurrent lifecycle and close-race coverage, targeted live proof, and the existing automated secret and package-boundary audits
- P5 closeout authority: exact head `d0b2a7f97bb12f075ad28e9636306b11c41424dc` passed the complete deterministic and affected Anthropic live gate, ordinary CI, secretless live-policy status, and independent review; PR [#50](https://github.com/maneesh888/universal-ai-connector/pull/50) merged as `d984631ea8de5f3f1c377ac72df155d23b6710da`, and resulting `main` run [31328462444](https://github.com/maneesh888/universal-ai-connector/actions/runs/31328462444) passed
- P6-A completion: activated P6 under the explicit August 7, 2026 sequencing decision, recorded the direct OpenRouter Chat Completions and generic compatibility boundaries, extended the one-file provider-specific input convention, and proved three-provider selection/isolation with stubs while keeping OpenAI as the only delivered live gate
- P6-A completion authority: exact head `759e7db62b9881429f0a81ba0a3a03ce4466f7e5` passed deterministic and affected OpenAI live gates, ordinary CI, secretless live-policy status, and independent review; PR [#40](https://github.com/maneesh888/universal-ai-connector/pull/40) merged as `6feabf364b1a20e0544c4583456cd7bef35bc5dc`, and resulting `main` run [31179405791](https://github.com/maneesh888/universal-ai-connector/actions/runs/31179405791) passed
- P6-B completion: delivered the internal direct OpenRouter Chat Completions registration, bounded non-streaming text request/response and usage translation, host-supplied bearer authentication, deterministic malformed/redaction/cancellation coverage, the exact-head OpenRouter live task, and real `openai,openrouter` provider selection
- P6-B completion authority: exact head `7c8bd034d9c73f7533753b0a52fbbee8413ce077` passed deterministic, OpenAI, and OpenRouter live gates, ordinary CI, secretless live-policy status, and independent review; PR [#41](https://github.com/maneesh888/universal-ai-connector/pull/41) merged as `4a69e73d96a94d76ec47c3517648fe0ef0e23be1`, and resulting `main` run [31209384740](https://github.com/maneesh888/universal-ai-connector/actions/runs/31209384740) passed
- P6-C completion: delivered the internal generic `openai-compatible` registration, conservative non-streaming Chat Completions construction and translation, safe generic errors, deterministic URL/header/unknown-field/cancellation coverage, and representative live compatibility coverage through the existing OpenRouter gate
- P6-C completion authority: exact head `a68528ad5d6ba78fff68d9cdf0c117eb1a19c86c` passed the complete deterministic and delivered-provider live gates, ordinary CI, secretless live-policy status, and independent review; PR [#52](https://github.com/maneesh888/universal-ai-connector/pull/52) merged as `999f9e3410d6a1cdb733fac9c99319002eb2cb18`, and resulting `main` run [31334952957](https://github.com/maneesh888/universal-ai-connector/actions/runs/31334952957) passed
- P6-D completion: delivered strict JSON-schema request/response revalidation, OpenRouter typed errors, fixed generic status errors, bounded request/retry metadata, conservative capability reporting, and targeted deterministic/live coverage
- P6-D completion authority: exact head `02dda3027a59a7805461de8f87a4067a0b69b090` passed the full local gate, complete delivered-provider live gates, ordinary CI, secretless live-policy status, and independent review; PR [#53](https://github.com/maneesh888/universal-ai-connector/pull/53) merged as `a6c63cf4dce4d9a1ee17795c65ce936c9cd637ab`, and resulting `main` run [31337372432](https://github.com/maneesh888/universal-ai-connector/actions/runs/31337372432) passed
- P6-E completion: delivered incremental direct and generic Chat Completions SSE translation, strict ordering and terminal handling, safe mid-stream failures, deterministic cancellation-boundary coverage, and targeted live streaming/cancellation smokes
- P6-E completion authority: exact head `dc80de7dd99a01ef833175a5780c5d5873f3de3c` passed the complete deterministic and delivered-provider live gates, ordinary CI, secretless live-policy status, and independent review; PR [#54](https://github.com/maneesh888/universal-ai-connector/pull/54) merged as `4721d4077ce51f1a4072fe11a7930c7e9ba810ac`, and resulting `main` run [31340246682](https://github.com/maneesh888/universal-ai-connector/actions/runs/31340246682) passed
- P6 completion: P6-A through P6-F delivered direct OpenRouter and conservative generic OpenAI-compatible readiness, provider-neutral credential supply, bounded non-streaming and structured translation, safe errors and metadata, conservative capabilities, incremental streaming, cancellation, cross-adapter concurrent lifecycle and close-race coverage, targeted live proof, and the existing automated secret and package-boundary audits
- P6 closeout authority: the transition in this milestone-closing candidate is accepted only after the complete deterministic and delivered-provider live exact-head gates, exact-head ordinary CI and secretless live-policy status, independent review, guarded merge, and resulting `main` workflow inspection pass; those self-referential identifiers belong in the pull-request brief
- Package version target: `0.1.0-alpha.1`
- Initial host surfaces: Android, iOS, and Kotlin/JVM on Linux, Windows, and macOS
- Gateway and OpenKeyboard integration: deferred

This document is the package repository's source of truth for implementation order. Complete one work package at a time and record verification evidence before advancing. Task modes, lifecycle automation, and reporting are defined in `AGENTS.md` and `docs/DEVELOPMENT_WORKFLOW.md`.

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

Keep normal commits, deterministic suites, pull-request CI, and GitHub Actions secretless. Beginning with P4, any change that can affect live provider or Gateway behavior must also pass the affected live suite locally before the initial pull request is created and before every later push that updates that pull request. The pre-push hook applies this only when the candidate differs from the base in a path classified by `scripts/live-impact.sh`. This includes later changes to the shared P3 transport, authentication, streaming, retry, error-mapping, or log-redaction paths after a live adapter exists.

P4 must introduce a separate `./scripts/check-live.sh` entry point before its first adapter pull request. The live gate must:

- read credentials only from the developer's process environment or an OS-backed credential store; any optional local env file must be ignored by Git and accompanied only by a value-free example;
- use dedicated, revocable, low-quota test credentials and never print credentials, authorization headers, full request bodies containing sensitive input, or unredacted provider responses;
- run the deterministic adapter suite first, then the smallest live response, streaming, error, and cancellation smoke tests needed for the affected provider or Gateway;
- bind its evidence to the exact commit SHA and record the command, provider or Gateway target, model or test fixture, execution date, result, and proof boundaries in the pull-request review brief; and
- treat a missing credential, unavailable provider, rate limit, or failed assertion as a blocked pull-request creation or update for affected live behavior, not as a skipped success.

Any head change invalidates earlier local live evidence and requires the affected live suite to run again before the updated head is pushed. Documentation-only and unrelated deterministic changes do not require live credentials.

After the draft pull request is created, a separate secretless workflow must classify the exact head and enforce retention of the matching local-live result in the pull-request body. The stable status targets the credential-free `live-policy` Environment and is a mandatory readiness and merge condition; its deployment completes automatically after the evidence assertions pass. An affected PR records `Local live verification: passed`, the exact head SHA, `No credential or provider response body retained.`, and `Trust boundary: local execution is contributor-attested; GitHub verifies retained exact-head evidence only.` Any head change invalidates that evidence. GitHub does not rerun provider tests, receive a provider credential, or independently prove local execution. The protected `live-provider` Environment and reviewer protection remain reserved but unused by this local-only policy. Never expose secrets to fork pull requests or use `pull_request_target`. The ordinary `ci.yml` workflow remains read-only and secretless.

## Milestones

| ID | Work package | Status | Evidence |
|---|---|---|---|
| P0 | iOS-Kotlin interoperability POC | Completed | 6 Kotlin tests, 8 Swift tests, XCFramework and sample build passed July 17, 2026 |
| P1 | Cross-platform package and client-sample baseline | Completed | Product Kotlin API, JVM console, Android app, and Apple façade/sample accepted; closing head `fdf33e5d197f13f5ab32f23cfc290ad263451946` passed local verification, independent review, exact-head run 29991895652, PR #12 merge, and resulting `main` run 29993494307 |
| P2 | Canonical core and JSON contracts | Completed | Provider-neutral Kotlin and Swift host contracts, 21 authoritative schemas, 173 fixture documents, deterministic compatibility checks, and host consumers; exact-head closeout evidence belongs in the milestone-closing pull-request brief |
| P3 | HTTP transport and provider registry | Completed | Provider-neutral transport, policy, SSE/metadata, registry, and integrated lifecycle behavior accepted through deterministic tests and existing host consumers; exact-head closeout evidence belongs in the milestone-closing pull-request brief |
| P4 | OpenAI Responses adapter | Completed | Internal Responses request, response, structured-output, error, capability, streaming, cancellation, lifecycle, secret-safety, live-evidence, and package-boundary behavior; exact-head closeout evidence belongs in the milestone-closing pull-request brief |
| P5 | Anthropic adapter | Completed | Internal Messages request, response, structured-output, error, capability, streaming, cancellation, lifecycle, secret-safety, live-evidence, and package-boundary behavior; exact-head closeout evidence belongs in the milestone-closing pull-request brief |
| P6 | OpenRouter and OpenAI-compatible adapters | Completed | Internal direct and generic Chat Completions request, response, structured-output, error, capability, streaming, cancellation, lifecycle, secret-safety, live-evidence, and package-boundary behavior; exact-head closeout evidence belongs in the milestone-closing pull-request brief |
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

P3 verification remains deterministic through Ktor `MockEngine` and local fixtures because no provider adapter is active yet. Once P4 establishes the live suite, any later P3 change that can affect live behavior is subject to the local pre-push live gate and secretless GitHub evidence policy above.

## P4-P7: Adapters

The default adapter delivery order is:

1. OpenAI Responses
2. Anthropic Messages
3. OpenRouter and generic OpenAI-compatible endpoints
4. Universal Gateway V2 canonical protocol

Each adapter owns its provider DTOs, request translation, response translation, structured-output handling, streaming translation, capability reporting, and canonical error mapping. Each adapter milestone must add deterministic mock coverage and targeted live response, streaming, error, and cancellation smoke coverage. A pull request that adds or changes live adapter behavior may not be created or updated until the affected live suite passes locally for its exact head, and it may not merge until the secretless GitHub policy validates that exact-head evidence and completes the required `live-policy` deployment.

An explicitly recorded provider-credential blocker may defer an incomplete adapter milestone and
allow the next adapter milestone to proceed. Deferral is not completion: unimplemented packages
retain their acceptance criteria, no provider proof is claimed, and the deferred adapter remains
a P9 release blocker. Only one non-deferred milestone may be `In progress`.

P4 also establishes the secret-safety baseline required by live testing: ignored local secret files, a value-free environment example, documented credential names and rotation procedure, log-redaction assertions, and the separate `./scripts/check-live.sh` command. Provider credentials are host-supplied test inputs; they must never be embedded in mobile or desktop artifacts, committed configuration, normal CI, samples, or logs.

P4 completed through `openai-responses-adapter.md`. P4-A established the protocol,
provider-neutral configuration decision, secret-safety convention, and protected local-live
evidence foundation; P4-B added non-streaming request and response translation; P4-C added
structured output, errors, and capabilities; P4-D added streaming translation and active
cancellation; and P4-E reconciled concurrent lifecycle, cleanup, host consumption, secretless CI,
and package boundaries. P5 was activated on August 7, 2026; P5-A completed protocol and
authentication-test readiness without adding Anthropic runtime behavior. The remaining P5
packages were then explicitly deferred for lack of a dedicated credential/model, and P6-A was
completed under `openrouter-openai-compatible-adapters.md`. The dedicated Anthropic inputs are now
available, so P5-B resumed and became authoritative through PR #42. P5-C became authoritative
through PR #48, P5-D became authoritative through PR #49, and P5-E completed P5 authoritatively
through PR #50 and resulting-`main` verification. P6-C completed authoritatively through PR #52,
P6-D through PR #53, and P6-E through PR #54. P6-F reconciles lifecycle and acceptance in this
milestone-closing candidate without activating P7.

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
- local pre-push live gates and protected secretless GitHub evidence policy pass for any distribution or sample change that affects live provider or Gateway behavior;
- self-contained desktop distributions built and smoke-tested on their matching macOS, Windows, and Linux hosts;
- documented minimum toolchain and platform versions;
- no manual framework copying, generated artifact commits, or repository-specific build steps for consumers.

## P9: Alpha release

Release `0.1.0-alpha.1` only after:

- deterministic tests pass on JVM, Android, and iOS;
- all initial adapters pass deterministic and live request, response, error, structured-output, streaming, and cancellation tests on the exact release head;
- the complete live suite passes locally before the release pull request is created or updated, and its exact-head evidence passes protected secretless GitHub policy before merge;
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
