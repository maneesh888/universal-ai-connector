# ADR 0006: Contract source-of-truth and drift policy

- Status: Accepted
- Date: 2026-07-24
- Milestone: P2-C

## Context

P2 has custom Kotlin serializers, Swift-native mappings, JSON Schemas, fixtures, and test mirrors.
Without a single authority and deterministic verification, these representations can silently
diverge.

## Decision drivers and considered options

Considered options:

1. Kotlin annotations and serializers generate every other artifact. Rejected because the custom
   cross-event and compatibility semantics are not faithfully derivable from annotations.
2. OpenAPI as an invented API document. Rejected because P2 defines no HTTP operation.
3. A versioned contract bundle with authoritative JSON Schemas for wire shape and a governed
   fixture manifest as conformance evidence. Selected.

## Decision

- The versioned bundle under `contracts/` is the sole authoritative contract representation.
  Within it, `contracts/schemas/v1/*.schema.json` is the sole machine-readable authority for V1
  wire shape. `contracts/fixture-manifest.json` plus tracked V1 fixtures records required
  conformance examples, validation layers, stable codes/paths, and compatibility purposes.
- Accepted ADRs define semantic rules that JSON Schema cannot express, including cross-event
  ordering. They do not create a competing generated model authority.
- Kotlin serializers/validators, Swift mappings, and the embedded multiplatform seed corpus are
  implementations or mechanically checked mirrors, never independent sources of truth.
- P2 uses no OpenAPI artifact. A later transport milestone may decide whether reusable component
  schemas justify one, without inventing paths here.
- Schema identifiers and filenames are stable and versioned. Draft 2020-12 is pinned. Kotlin
  2.4.10, `kotlinx.serialization` 1.11.0, and NetworkNT JSON Schema Validator 3.0.6 are pinned in
  build source.
- Committed schema and fixture files are hand-owned authoritative inputs. Any later generator must
  identify generated files, pin its version, place transient output under build directories, and
  prohibit manual edits to generated output.
- The deterministic entry point is `./scripts/check-contracts.sh`. It is integrated into quick and
  full repository gates, hooks, and CI.
- For the governed schema and fixture corpus, zero drift means all of the following pass:
  - every schema is Draft 2020-12 meta-valid, uses only the governed connector-schema vocabulary,
    has a unique stable ID, and has resolvable governed references;
  - every schema ID has direct fixture coverage;
  - the manifest exactly covers tracked fixtures once, with unique IDs and documented invalid
    boundaries;
  - every source fixture validates or fails at exactly its documented schema/semantic layer;
  - every applicable valid fixture is decoded and re-encoded through the production Kotlin
    implementation and that output remains valid against the same authoritative schema;
  - the embedded common corpus is byte-for-byte and metadata-for-metadata identical to the tracked
    manifest/fixture corpus; and
  - public-surface, generated-artifact, secret, and whitespace audits pass.
- JSON comparisons are semantic except where the contract explicitly preserves raw data, such as
  exact extension-number token spelling. P2 does not require canonical byte encoding.
- This is finite-corpus conformance, not generated-code identity or proof that Kotlin and schemas
  accept exactly the same set of every possible JSON document.

## Kotlin, Swift, and JSON consequences

- Platform-neutral decoding and semantic validation live in Kotlin `commonMain`.
- Repository schema validation uses the pinned JVM-only validator; it is tooling, not a runtime
  dependency or public type.
- The same embedded corpus runs through JVM, Android host, and iOS Simulator common tests.
- Swift does not own wire decoding. Swift tests prove native mapping and validation parity, so no
  duplicate Swift schema engine is introduced.

## Compatibility and failure behavior

- Any authority/mirror drift fails `check-contracts.sh` and therefore the repository gates.
- A new authoritative schema cannot land without direct fixture coverage.
- A production re-encoding that no longer satisfies its source schema fails even when its Kotlin
  round trip remains internally idempotent.
- Generated build output remains ignored and untracked; schemas, fixtures, and manifest remain
  tracked.

## Required deterministic tests

- Schema meta-validation, governed keyword/reference checks, and unique IDs.
- Exact manifest/repository/multiplatform-mirror equality.
- Source and production-reencoded fixture schema validation.
- Exact semantic error code/path assertions.
- A harness regression proving unresolved references and orphan schema IDs fail.
- Provider/vendor signature audit and generated-output hygiene.

## P1 migration

The authority and validator are development inputs only. No schema validator or JSON DOM appears
in the P1-derived supported host API.

## Deferred questions

- P3 may decide whether transport component reuse merits OpenAPI.
- P8 owns release artifact generation, signing, publication, and released compatibility tooling.
- A future version may adopt a generator only through a bounded architecture decision.
