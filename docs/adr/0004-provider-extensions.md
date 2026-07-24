# ADR 0004: Provider-extension mechanism

- Status: Accepted
- Date: 2026-07-24
- Milestone: P2-B

## Context

Provider-specific controls and metadata must survive canonical processing without vendor DTOs,
top-level name collisions, serialization-DOM leakage, or unbounded arbitrary data.

## Decision drivers and considered options

Considered options:

1. Vendor-specific request subclasses or DTOs. Rejected because they pollute the primary client.
2. Flat string maps. Rejected because they cannot express governed nested values.
3. Unnamespaced unknown top-level fields. Rejected because ownership and precedence are unclear.
4. Public `kotlinx.serialization` JSON DOM values. Rejected because they leak an implementation
   dependency.
5. Bounded reverse-DNS object bags with native value models. Selected.

## Decision

- An extension bag is keyed by lowercase reverse-DNS owner namespaces. A namespace has at least
  two labels, each label follows DNS-like 1-63-character syntax, and the full value is at most 253
  ASCII bytes. Ownership is governance, not a runtime DNS lookup.
- V1 reserves no invented connector namespace. Connectors and providers use namespaces they
  actually control and document.
- Each namespace owns exactly one JSON object payload. Scalar and array namespace roots are
  invalid. Duplicate namespaces and duplicate object members are invalid.
- Replacing a namespace replaces its entire payload; extension data is never deep-merged.
- Canonical fields are authoritative. Extensions cannot supply required canonical data, relax
  canonical validation, or overwrite canonical meaning. Generic code treats similarly named
  nested extension members as opaque. A typed helper that recognizes conflicting intent rejects
  it rather than silently overriding a canonical field.
- There is no automatic request-to-response extension propagation.
- Extension bags are allowed on request, response, output, usage, error, stream event, capability
  declaration, provider capability profile, and model descriptor.
- Bags are not added to identifiers, targets, input items, generation parameters, response-format
  objects, or model-token-limit leaves. Provider request controls use request extensions.
- Unknown namespaces, members, values, array order, and exact JSON-number spellings are
  preserved. Object order is insignificant and deterministic encoding sorts object keys.

## Governed value model

Allowed values are JSON null, boolean, a well-formed Unicode string, an exact raw JSON-number
token, an ordered array, and an object.

- maximum namespaces per bag: 16;
- maximum compact UTF-8 bag size: 65,536 bytes;
- maximum container depth: 16, with the namespace payload object at depth 1;
- maximum value nodes across the bag: 1,024, including payload roots;
- maximum members per object: 256;
- maximum elements per array: 256;
- member names: nonempty, well-formed Unicode, no C0/DEL controls, at most 256 UTF-8 bytes;
- string values: well-formed Unicode, at most 16,384 UTF-8 bytes; and
- number tokens: JSON number grammar, at most 128 ASCII bytes, never NaN or infinity.

Number spelling is opaque provider data: `1`, `1.0`, `-0`, and `1e0` remain distinct.
JSON Schema checks portable structure and character counts. Semantic validation is authoritative
for UTF-8 size, encoded bag size, depth, node count, and raw token length.

Example:

```json
{
  "extensions": {
    "com.example.responses": {
      "reasoningMode": "balanced",
      "exactBudget": 1.2300e+40,
      "flags": [true, null]
    }
  }
}
```

## Typed helpers and ordinary consumers

- Core Kotlin and Swift helpers provide lookup and whole-namespace replacement/removal using
  native immutable values.
- Provider packages may add typed builders or extensions for namespaces they own.
- A typed read-modify-write helper preserves unknown sibling members and enforces its own
  canonical-field conflict rules.
- Helpers return canonical models and never expose vendor DTOs or serialization DOM types.
- Ordinary consumers use default-empty bags and ignore extension data.

## Kotlin, Swift, and JSON consequences

- Kotlin performs governed value validation in `commonMain`; every failure carries a stable code
  and JSON Pointer.
- Swift mirrors the same validation with native `Sendable` recursive values. Public creation of an
  invalid namespace, number, tree, or bag throws rather than constructing an invalid canonical
  value.
- Duplicate names are rejected by strict JSON parsing before ordinary DOM materialization.
- Empty bags are omitted. Explicit null is invalid at every extension location.

## Compatibility and failure behavior

- Ordinary unknown members outside `extensions` are ignored and dropped.
- Valid unknown extension data is preserved semantically and exact number-token spelling survives
  Kotlin/Swift mapping.
- Extension and canonical fields have no precedence ambiguity: canonical always wins, and a
  recognized conflict fails.
- Invalid namespace, root type, name/string Unicode, number token, count, depth, node, or encoded
  size fails at the documented layer with a stable code and path.

## Required deterministic tests

- Boundary and boundary-plus-one cases for every governed limit.
- Duplicate namespaces and nested members rejected before DOM materialization.
- All nine extension locations, omitted-empty normalization, explicit-null rejection, nested
  round trips, and ignored ordinary unknown members.
- Exact numbers including `-0`, arbitrary precision, exponent spelling, and trailing zeroes.
- Kotlin/Swift parity for every invalid namespace, number, value tree, and aggregate bag.
- Generic whole-namespace replacement/removal preserves untouched namespaces. Typed-helper sibling
  preservation and conflict tests become mandatory in the first P4-P7 package that introduces an
  owned typed namespace helper.
- Minimal, complete, invalid, and future extension fixtures on every configured Kotlin host.

## P1 migration

Extension bags default to empty, so P1 callers gain no required configuration and no provider DTO
appears in either supported host API. Request extension mapping remains private inside the Apple
adapter.

## Deferred questions

- P3 owns transport use and safe transport metadata.
- P4-P7 own provider namespaces, typed helpers, conflict enforcement, and translation.
- P8 owns released compatibility.
- A later version may establish a namespace registry or standardize additional canonical fields.
