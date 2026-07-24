# P2 Canonical Core and JSON Contracts

## Status and activation gate

P1 and P2 are `Completed`. P2 was separately authorized and activated on July 24, 2026, P2-A was its first package, and P2-J closed the milestone. P3 remains `Not started` and requires separate activation.

Creating and reviewing this plan does not authorize canonical-contract implementation. Do not add serialization dependencies, canonical models, schemas, fixtures, generated contract artifacts, or canonical host APIs until all of the following are true:

1. the P1 closing package has removed the temporary POC Swift and callback surfaces;
2. every P1 acceptance criterion has evidence for the exact closing head;
3. the complete local gate, independent review, and every required GitHub check pass for that same head;
4. the P1 pull request merges and the resulting `main` workflow run is inspected;
5. the roadmap records P1 as `Completed`; and
6. the roadmap marks P2 as the only `In progress` milestone and names the first active ADR package.

Items 1-4 were satisfied by P1 closing head `fdf33e5d197f13f5ab32f23cfc290ad263451946`, exact-head run [29991895652](https://github.com/maneesh888/universal-ai-connector/actions/runs/29991895652), PR [#12](https://github.com/maneesh888/universal-ai-connector/pull/12), and resulting `main` run [29993494307](https://github.com/maneesh888/universal-ai-connector/actions/runs/29993494307). Item 5 was recorded by roadmap-closeout PR [#14](https://github.com/maneesh888/universal-ai-connector/pull/14) at `main` head `260345f1cd3d2f05faff1bdd6361b9ce58db1ddf`; resulting `main` run [30075847578](https://github.com/maneesh888/universal-ai-connector/actions/runs/30075847578) passed. Item 6 was satisfied by the separate July 24, 2026 authorization that made P2 the sole `In progress` milestone and named P2-A first. P1 completion and P2 activation remain separate status changes.

## Objective

Define the provider-neutral, serializable contract that later transport, provider, and Gateway adapters will implement without exposing vendor DTOs to applications.

P2 owns:

- provider and model identifiers;
- provider-neutral targets;
- ordered text input;
- response-format intent;
- generation parameters;
- requests, responses, outputs, and usage;
- canonical errors;
- streaming events and terminal semantics;
- provider and model capabilities;
- model descriptors;
- provider extensions; and
- forward-compatible JSON representations.

The milestone must preserve one primary Kotlin client entry point and one Swift façade. It defines contracts and deterministic behavior only; it does not introduce networking or claim compatibility with a live provider.

## Design constraints

- Keep platform-neutral contracts and behavior in Kotlin `commonMain`.
- Keep Kotlin consumers on idiomatic `suspend` and `Flow` APIs.
- Keep Swift consumers on Swift-native `Sendable` models, `async`, `AsyncThrowingStream`, Swift errors, and Swift cancellation.
- Keep Kotlin implementation types, serialization DOM types, coroutine scopes, callback adapters, generated Objective-C names, and packaging details out of the supported Swift API.
- Preserve P1 cancellation propagation, concurrency, exactly-once terminal behavior, and documented cleanup ownership.
- Keep one simple default deterministic construction path.
- Do not add provider-specific host methods, controls, DTOs, or lifecycle paths.
- Treat Android, iOS, and JVM samples as external consumers of supported package boundaries.
- Keep provider credentials, live calls, and secret-bearing configuration absent.
- Avoid an incidental Gradle module split. Any module-topology change requires a separate bounded architecture decision.

## Scope

### In scope

- `kotlinx.serialization` for canonical contract encoding and decoding;
- canonical models and semantic validation in common Kotlin;
- an authoritative schema or source representation selected by ADR;
- mechanically derived or verified contract artifacts;
- deterministic schema, serialization, semantic-validation, and compatibility checks;
- committed valid, invalid, and compatibility fixtures;
- deterministic fake behavior using canonical requests, responses, and events;
- the minimum Kotlin and Swift public-surface migration required by the settled contract; and
- synchronized updates to the JVM, Android, and iOS samples when the canonical host API is stable.

### Out of scope

- Ktor, HTTP engines, URL normalization, headers, timeouts, SSE parsing, retry metadata, provider registry behavior, and transport ownership, which belong to P3;
- OpenAI, Anthropic, OpenRouter, compatible-provider, or Gateway DTOs and translation, which belong to P4-P7;
- credentials, live tests, protected secret environments, or `check-live.sh`, which begin with P4;
- Maven publication, remote Swift Package distribution, signing, checksums, or released-artifact consumers, which belong to P8;
- tool execution, agents, RAG, and multimodal input;
- OpenKeyboard behavior;
- new host targets or samples;
- provider or model discovery over a network; and
- invented HTTP operations merely to justify an OpenAPI document. If OpenAPI is selected, P2 may define reusable schema components without paths.

## ADR prerequisite gate

No implementation package begins until the decisions below are accepted and cross-checked for consistency. Prefer one ADR per decision; combine decisions only when their coupling and review boundary are explicit.

Every ADR must include:

- context and problem statement;
- considered options and decision drivers;
- selected outcome and rejected alternatives;
- Kotlin, Swift, and JSON consequences;
- representative wire examples;
- compatibility and failure behavior;
- required deterministic tests;
- migration impact on the P1 public surface; and
- deferred questions with an owning later milestone.

### ADR 1: Error delivery and cancellation semantics

Decide:

- thrown host-native errors versus value-based failures;
- validation timing;
- canonical error categories, codes, messages, and safe metadata;
- whether cancellation may ever appear as a canonical error or event;
- partial-output behavior;
- exactly-once success, failure, completion, and cancellation rules; and
- mapping to Kotlin `CancellationException` and Swift `CancellationError`.

The decision must not turn caller cancellation into a provider failure.

### ADR 2: Streaming event and terminal-event model

Decide:

- event taxonomy and envelope fields;
- delta versus snapshot representation;
- ordering and sequence guarantees;
- response and output correlation;
- placement of final response and usage;
- explicit terminal events versus normal `Flow` completion;
- error and cancellation terminal behavior; and
- late-event and duplicate-terminal suppression.

### ADR 3: Provider and model capability representation

Decide:

- supported, unsupported, and unknown states;
- unknown capability names or values;
- provider-level and model-level declarations;
- model override or refinement rules;
- capability parameters or limits; and
- representation of streaming and structured-output support.

Unknown support must remain distinguishable from known lack of support.

### ADR 4: Provider-extension mechanism

Decide:

- namespacing and collision rules;
- allowed JSON value types and limits;
- request, response, output, error, capability, and descriptor extension locations;
- preservation of unknown extension values;
- typed convenience helpers;
- precedence between canonical fields and extensions; and
- how ordinary consumers ignore extensions without handling vendor DTOs.

### ADR 5: JSON Schema subset and validation policy

Decide:

- schema draft and supported keywords;
- `$ref`, composition, recursion, depth, and size behavior;
- null, omission, default, and additional-property rules;
- request-time structured-output validation and error reporting; and
- the distinction between schemas describing connector contracts and user-supplied structured-output schemas.

### ADR 6: Schema, OpenAPI, and Kotlin source-of-truth policy

Decide:

- the authoritative artifact;
- generation or verification direction;
- committed versus generated outputs;
- drift detection;
- generator and validator version pinning;
- generated-file ownership; and
- whether OpenAPI is used only as a component-schema container.

There must be exactly one authority. All other representations are generated or mechanically verified against it.

### ADR 7: Contract versioning and compatibility

Decide:

- whether contract and package versions are independent;
- version marker placement;
- additive, breaking, deprecated, renamed, and removed changes;
- field omission, null, and default semantics;
- unknown-field and unknown-value guarantees;
- fixture retention and compatibility windows; and
- migration policy for the pre-release P1 API.

Unknown values must never be silently converted into a semantically different known value.

## Canonical model inventory

Exact public names and serialized representations remain ADR and implementation outcomes. Every family below is required.

| Family | Contract responsibility |
|---|---|
| Identity and version | Provider ID, model ID, target and request/response identifiers where applicable, plus contract-version representation |
| Target | Provider-neutral selection of provider and model without configuration objects or vendor DTOs |
| Input | Ordered, text-only initial-alpha input with role and content semantics |
| Response format | Plain text and governed structured JSON/schema intent with forward-compatible future-format handling |
| Generation parameters | Common provider-neutral controls, validation, and omission/null/default rules; transport retry is not a generation parameter |
| Request | Target, ordered input, response format, generation parameters, and optional extensions |
| Response | Identity and target metadata, ordered outputs, usage, completion status or reason, and extensions |
| Output | Provider-neutral text or structured output with safe handling for future output kinds |
| Usage | Input, output, and total accounting plus optional extensible breakdowns and consistency rules |
| Error | Stable category, code, message, and optional safe metadata; transport fields may be defined but remain unpopulated until P3 |
| Stream event | Correlation, ordering, output deltas or snapshots, usage/final/error information, and the terminal model selected by ADR |
| Capability | Known support state, unknown support, forward-compatible capability values, and optional canonical limits |
| Model descriptor | Provider/model identity, display metadata, canonical limits, capabilities, and extensions; no discovery behavior |
| Extension value | Governed JSON-compatible provider extension data that ordinary consumers can omit or ignore |

For every family, its implementation package must define:

- invariants and semantic validation;
- serialized field names and optionality;
- unknown-field behavior;
- unknown discriminator or value behavior;
- extension behavior;
- Kotlin and Swift public mapping; and
- minimal, complete, invalid, and forward-compatibility fixtures.

The P1 echo string, `UniversalAiStreamEvent(sequence, text)`, and closed three-value error enum are migration inputs, not automatically the final P2 design.

## Schema and source governance

After the ADR gate:

- keep stable, versioned schema identifiers and filenames;
- pin generator and validator versions;
- add one deterministic contract entry point such as `./scripts/check-contracts.sh`;
- integrate the contract check into `check.sh`, hooks, and CI once it becomes authoritative;
- fail when authoritative and derived representations drift;
- clearly mark generated artifacts and prohibit manual edits;
- validate schema shape, JSON decoding, and semantic model invariants as separate layers;
- compare JSON semantically unless the ADR requires canonical byte encoding;
- audit public signatures for provider or vendor types; and
- keep source schemas and fixtures tracked while excluding generated build output.

An illustrative layout, subject to the source-of-truth ADR, is:

```text
docs/adr/
contracts/
  schemas/<contract-version>/
  fixtures/<contract-version>/
    valid/
    invalid/
    compatibility/
bridge/src/commonMain/kotlin/com/maneesh/universalai/connector/contract/
bridge/src/commonTest/kotlin/com/maneesh/universalai/connector/contract/
scripts/check-contracts.sh
```

## Fixture and compatibility policy

Maintain a manifest that identifies each fixture's contract version, model family, expected validation result, and compatibility purpose. Invalid entries must also name the expected validation layer and stable reason or error code so failure at the wrong boundary does not satisfy the fixture.

The corpus must cover:

- minimal and complete request, response, error, event, capability, and descriptor examples;
- omitted optional fields, explicit nulls, and defaults according to the accepted ADR;
- unknown top-level and nested fields;
- future raw values or discriminators for every extensible value family;
- nested extension values;
- malformed identifiers and out-of-range parameters;
- invalid stream ordering and terminal sequences;
- invalid structured-output schemas;
- older fixtures decoded by the current implementation; and
- future additive fixtures proving the documented forward-compatibility behavior.

Run the same corpus through common Kotlin tests on JVM, Android host tests, and iOS Simulator. If Swift owns direct JSON decoding, exercise equivalent fixtures in Swift. Otherwise verify Swift-native mapping parity without claiming Swift wire-format ownership.

Retain compatibility fixtures after release. Removal follows the versioning ADR rather than ordinary test cleanup.

## Work packages

P2-A through P2-J are complete. No P3 work package is active.

Final package: P2-J compatibility hardening and acceptance. ADRs 0001-0007 and the P2-D
readiness decision are accepted; implementation packages P2-E through P2-I and the P2-J
acceptance corpus, audits, host builds, and status reconciliation are complete.

### P2-A: Error, cancellation, and streaming ADRs

- Complete ADRs 1 and 2.
- Include Kotlin and Swift lifecycle examples.
- Define observable exactly-once and cancellation acceptance cases.
- Make no production-code or dependency change.

### P2-B: Capability and extension ADRs

- Complete ADRs 3 and 4.
- Demonstrate supported, unsupported, unknown, and future capability values.
- Demonstrate namespaced extensions without vendor DTOs.
- Make no production-code or dependency change.

### P2-C: Schema and compatibility ADRs

- Complete ADRs 5, 6, and 7.
- Select the authority, validation boundary, drift policy, and versioning rules.
- Include fixture and migration examples.
- Make no production-code or dependency change.

### P2-D: Cross-ADR readiness review

- Reconcile terminology, nullability, unknown-value, extension, error, and terminal rules across all seven ADRs.
- Resolve contradictions rather than deferring them into code.
- Freeze the first implementation package's observable acceptance criteria.
- Record an implementation-readiness decision.

No implementation package begins unless P2-D concludes that all prerequisite decisions are complete and consistent.

### P2-E: Serialization and contract harness

- Add the approved serialization plugin and dependencies.
- Establish authoritative and derived artifact locations.
- Add pinned generator or validator tasks.
- Seed the fixture manifest and smallest representative fixtures.
- Add deterministic generation and drift checks.
- Do not migrate the public client yet.

### P2-F: Request-side contracts

- Implement identity, target, input, response format, generation parameters, and request models.
- Add invariants, serialization, schemas, and fixtures.
- Prove unknown-field and unknown-value behavior.

### P2-G: Response, output, usage, error, and stream contracts

- Implement the accepted error and terminal semantics.
- Add response, output, usage, error, and event invariants.
- Add ordering, duplicate-terminal, late-event, and compatibility fixtures.
- Preserve host-native cancellation.

### P2-H: Capabilities, model descriptors, and extensions

- Implement supported, unsupported, and unknown capability behavior.
- Implement the governed extension mechanism.
- Add provider-neutral descriptors and fixtures without discovery or registry behavior.

### P2-I: Primary-client and deterministic-sample migration

- Make the deterministic implementation consume and produce canonical contracts.
- Keep one Kotlin client and one Swift façade.
- Update the private Apple adapter and Swift-native models.
- Apply the compatibility ADR to the P1 string methods instead of creating a permanent second client path.
- Update the JVM, Android, and iOS samples together after the canonical host shape is stable.

### P2-J: Compatibility hardening and acceptance

Status: `Completed`.

- Complete the fixture corpus and public-surface audits.
- Compile all documented first-use paths.
- Run the complete exact-head deterministic matrix.
- Reconcile roadmap, README, and plan evidence.
- Mark P2 complete only after every acceptance criterion has durable evidence.

## Host API migration rules

- Kotlin retains one reusable primary client with idiomatic `suspend` and `Flow`.
- Swift retains one façade with Swift-native `Sendable` models, `async`, `AsyncThrowingStream`, stable Swift errors, and Swift cancellation.
- The private Apple callback adapter converts canonical objects internally and preserves exactly-once delivery.
- Kotlin models, serialization types, and generated Objective-C names remain outside the supported Swift surface.
- Samples construct a minimal canonical request and render canonical responses and events without provider configuration, networking, credentials, or duplicated connector behavior.
- Update all three samples only when the settled canonical contract requires it.
- Re-run header and artifact-boundary scans so Apple adapters stay absent from JVM and Android artifacts.
- Preserve P1 concurrency, ownership, cleanup, cancellation, and late-callback regression coverage.

## Test matrix

### Common contract tests

- constructors and semantic validation;
- serialization, deserialization, and schema validation;
- every valid, invalid, and compatibility fixture;
- unknown fields and values at top-level and nested locations;
- extension round trips;
- request, response, output, and usage invariants;
- error mapping and streaming order/terminal invariants; and
- deterministic response, streaming, concurrency, cancellation, and late-terminal suppression.

Run on:

```bash
./gradlew :bridge:jvmTest
./gradlew :bridge:testAndroidHostTest
./gradlew :bridge:iosSimulatorArm64Test
```

### Contract tooling

- authoritative-to-derived generation or verification;
- zero-drift check;
- schema validation for every fixture;
- provider/vendor public-signature audit;
- committed-artifact hygiene;
- secret scan; and
- whitespace validation.

### Host integration

- JVM console compiles and runs with the canonical API;
- Android controller tests and APK assembly use the same Kotlin API;
- Swift Package tests cover canonical response, stream, error, unknown-value, and cancellation behavior;
- iOS simulator and generic-device sample builds pass;
- XCFramework headers contain no unsupported Kotlin public contract or `Flow`; and
- Apple adapter classes remain absent from JVM and Android artifacts.

### Required gates

```bash
./scripts/check.sh --quick
./scripts/check.sh --full
```

The P2 acceptance candidate requires exact-head Linux, Windows, and macOS CI plus independent exact-head review. Intermediate implementation uses the Fast or Standard mode until it becomes a Release candidate.

No live-provider gate applies in P2. Physical iOS-device execution is not required. Repeat the Android emulator proof only when Android-specific lifecycle or UI behavior changes; otherwise report it as unexercised.

## Acceptance criteria

- P1 was completed before P2 implementation activation.
- All seven ADR decisions are accepted and consistent with one another.
- Every roadmap model family is implemented in common Kotlin.
- Authoritative and derived contract artifacts have a deterministic zero-drift check.
- Every valid fixture is schema-valid.
- Every invalid fixture fails for its documented reason.
- Unknown fields and future unknown values satisfy the accepted compatibility rules.
- Provider extensions require no vendor DTO or provider-specific request object for ordinary use.
- No vendor DTO appears in a canonical Kotlin or Swift public signature.
- One Kotlin client and one Swift façade expose canonical operations.
- Kotlin and Swift retain native cancellation, stable errors, concurrency, and exactly-once terminal behavior.
- JVM, Android, and iOS samples compile through supported package boundaries with the canonical API.
- The complete local and exact-head CI matrix passes.
- README, roadmap, and plan evidence distinguish contract, packaging, and consumer proof.
- No networking, credentials, provider claim, publication claim, or new host surface is introduced.

## Proof limits

P2 proves provider-neutral contract shape, serialization, schema and fixture compatibility, deterministic behavior, and local host consumption.

P2 does not prove:

- compatibility with a real provider or Gateway payload;
- HTTP, authentication, SSE, retry, request-ID, retry-after, or redaction behavior;
- provider capability discovery;
- structured-output support by a live model;
- live response, error, stream, or cancellation behavior;
- remote Maven or Swift Package distribution;
- physical-device behavior; or
- released-version binary or source compatibility.

## Completion evidence

For every P2 package, record:

- exact branch and head SHA;
- accepted ADRs or changed contract families;
- authoritative and derived artifacts;
- fixture inventory changes;
- commands executed and results;
- host surfaces compiled;
- compatibility behavior proven;
- proof limits and unexercised surfaces; and
- the next incomplete P2 package.

P2-J proposed completion after the roadmap and plan acceptance criteria, mandatory exact-head local checks, independent review, and required GitHub checks agreed. Merge made that transition authoritative; resulting `main` verification was a post-merge closeout assertion rather than a candidate prerequisite. Exact self-referential evidence remains in the milestone-closing pull-request brief. P3 is the next incomplete milestone but remains `Not started`.
