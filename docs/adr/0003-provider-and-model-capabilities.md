# ADR 0003: Provider and model capability representation

- Status: Accepted
- Date: 2026-07-24
- Milestone: P2-B

## Context

Applications need to reason about portable provider and model behavior without discovery,
provider DTOs, or false certainty. The contract must distinguish known lack of support from no
reliable answer, preserve future values, and define how provider defaults become effective model
descriptors.

## Decision drivers and considered options

The decision prioritizes conservative feature decisions, lossless future-value handling,
deterministic refinement, and identical Kotlin and Swift semantics.

Considered options:

1. Boolean capability flags. Rejected because they collapse unsupported, unknown, absent, and
   future states.
2. Closed enums. Rejected because they cannot preserve future capability or support values.
3. Deep or per-field merging of provider and model declarations. Rejected because it leaves stale
   limits and extensions when a model intentionally changes support.
4. Raw-backed declarations with whole-entry refinement. Selected.

## Decision

- Capability names, support values, and limit names are validated raw-backed tokens. V1 knows
  `streaming`, `structured_output`, `supported`, `unsupported`, and `unknown`.
- Unknown valid names remain distinct and round-trip. A future support value remains raw-distinct
  but projects conservatively to semantic `unknown`.
- Absence differs from an explicit `unknown` declaration. Exact lookup preserves that distinction;
  a convenience semantic-state lookup may project both to unknown for conservative feature
  branching.
- A provider capability profile supplies defaults. A sparse model set refines it by raw capability
  name. An absent model entry inherits the provider declaration; a present entry replaces the
  entire support, limits, and extensions entry.
- Provider/profile identity must match the model target. A serialized model descriptor carries
  the fully resolved effective capability set, never sparse refinement instructions.
- `streaming` means the canonical event-stream operation, independently of P3 transport.
  `structured_output` means governed schema intent and canonical structured JSON output. Neither
  value claims live-provider behavior.
- A set contains at most 64 declarations. Each declaration contains at most 64 integer limits.
  Limit values are JSON-safe integers in `0...9_007_199_254_740_991`.
- `max_schema_bytes` and `max_schema_depth` are positive, apply only to
  `structured_output`, and describe accepted governed-schema intent. Unknown limit names remain
  raw and preserved.
- `unsupported` declarations cannot carry limits. `unknown` and future support values may retain
  limits, but those limits never imply support.
- Rich or provider-specific capability parameters belong in the declaration's namespaced
  extensions until a later contract version standardizes them.
- Descriptor token ceilings are separate from capability limits. They are positive JSON-safe
  integers; `maxOutputTokens` is at most 1,048,576; known input/output maxima cannot exceed a known
  context window; their sum need not equal the context window.

## Kotlin, Swift, and JSON consequences

- Kotlin uses immutable validated `commonMain` values, defensive map snapshots, and an explicit
  whole-entry resolver.
- Swift uses only native `Sendable` values and provides equivalent throwing validation and
  refinement behavior. It does not expose Kotlin maps or serializers.
- Capability declarations require `support`; omitted `limits` and `extensions` mean empty.
  Explicit null is invalid.
- Map ordering is insignificant; deterministic encoding sorts raw keys.
- Canonical descriptors and profiles are data only. P3 owns production, discovery, and freshness.

Representative provider profile:

```json
{
  "contractVersion": "1",
  "providerId": "example",
  "capabilities": {
    "streaming": {
      "support": "supported"
    },
    "structured_output": {
      "support": "supported",
      "limits": {
        "max_schema_bytes": 65536,
        "max_schema_depth": 16
      }
    },
    "future_reasoning": {
      "support": "preview",
      "limits": {
        "max_steps": 64
      },
      "extensions": {
        "com.example.capability": {
          "mode": "beta"
        }
      }
    }
  }
}
```

`preview` is preserved as raw data and interpreted semantically as unknown.

Whole-entry refinement:

| Capability | Provider default | Model refinement | Effective descriptor |
|---|---|---|---|
| `streaming` | supported | absent | inherited supported |
| `structured_output` | supported with limits | explicit unknown, no limits | unknown; provider limits removed |
| `embeddings` | absent | unsupported | unsupported |

## Compatibility and failure behavior

- Unknown ordinary JSON members are ignored and dropped; extensions are the preservation lane.
- Unknown capability, support, and limit tokens are retained when they satisfy the token grammar.
- A future raw support value is never rewritten as literal `unknown`.
- Wrong-known-limit/capability combinations, unsupported declarations with limits, invalid
  refinement identity, and invalid token ceilings fail with stable validation codes and paths.
- Swift construction and refinement reject every state that the Kotlin canonical model rejects.

## Required deterministic tests

- Supported, unsupported, explicit unknown, absent, and future raw values.
- Provider inheritance, whole replacement, removal of provider limits/extensions, and provider
  identity mismatch.
- Known schema limits on `structured_output`, their rejection on other capabilities, unknown limit
  preservation, count limits, and JSON-safe integer bounds.
- Descriptor ceiling invariants and boundary values.
- Omitted-empty normalization and explicit-null rejection.
- Equivalent Kotlin and Swift invalid-state behavior and `Sendable` model coverage.
- Minimal, complete, invalid, and forward-compatibility profile, capability, and descriptor
  fixtures on every configured Kotlin host.

## P1 migration

Capability profiles and model descriptors are additive to the P1 host surface. They do not create
a registry, a second client, or a required configuration path.

## Deferred questions

- P3 owns profile production, registry behavior, freshness, caching, and discovery.
- P4-P7 own provider-specific capability translation and evidence.
- P8 owns released API compatibility.
- Later contract versions may standardize more names, parameters, conditional states, or
  provenance.
