# Canonical contract authority

The versioned bundle in this directory is the authoritative representation selected by
[ADR 0006](../docs/adr/0006-contract-source-of-truth.md).

- `schemas/v1/` is the sole machine-readable authority for V1 wire shape.
- `fixture-manifest.json` records every tracked fixture, expected result, validation layer, stable
  reason, and compatibility purpose.
- `fixtures/v1/valid/`, `invalid/`, and `compatibility/` are deterministic conformance evidence.

Kotlin serializers and validators, Swift-native mappings, and the embedded common-test fixture
corpus are implementations or mechanically checked mirrors. Generated build output is not tracked.
Run `./scripts/check-contracts.sh` from the repository root to verify layout, drift, schemas,
fixtures, semantic boundaries, and supported host tests.
