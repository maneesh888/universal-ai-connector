# ADR 0007: Contract versioning and compatibility

- Status: Accepted
- Date: 2026-07-24
- Milestone: P2-C

## Context

The canonical wire contract must evolve independently of package releases, accept safe additive
data, preserve future semantic values, and reject changes whose meaning V1 cannot infer.

## Decision drivers and considered options

Considered options:

1. Couple contract and package versions. Rejected because host packaging and wire evolution have
   different lifecycles.
2. Silently map unknown values to a known fallback. Rejected because it changes semantics.
3. Raw-preserving extensible values with major-version envelopes and explicit compatibility
   rules. Selected.

## Decision

- Contract version and package version are independent. V1 uses the string major marker
  `"contractVersion":"1"`.
- The marker is required on independently encoded request, response, stream-event, provider
  capability profile, and model-descriptor envelopes. Component values such as target, output,
  usage, error, and extension bags inherit the version of their containing envelope; standalone
  component fixtures validate component shape without inventing another marker.
- A reader accepts only its supported major. A missing, null, malformed, or future major fails;
  it is never guessed from fields.
- Within V1, compatible change is additive: an optional ordinary field, an optional governed
  extension member, a valid raw-backed value, or a relaxed constraint that does not reinterpret
  old data.
- Breaking change includes removing or renaming a field/value, changing requiredness or meaning,
  narrowing an accepted bound, adding a required field, or reusing a raw value for different
  semantics. It requires a new major unless the pre-release migration policy below applies.
- Unknown ordinary object members are accepted and ignored, then dropped on re-encoding.
  Preservation belongs only in governed `extensions` or explicitly raw-backed value families.
- Unknown valid raw identifiers, roles, formats, output kinds, completion reasons, error
  categories/codes, event types, capability names/support values, and limit names remain distinct.
  They are never silently converted to a semantically different known value.
- Unknown nonterminal stream events may pass through. Unknown terminal events fail because V1
  cannot safely infer completion semantics.
- Unknown provider-extension namespaces and values are preserved, including exact number token
  spelling.
- Omitted request `responseFormat` means plain text; omitted `generation` means no controls; and
  omitted `extensions` means empty. Encoders omit those defaults. Explicit null is rejected unless
  null is itself a value inside an extension/structured JSON value or is permitted inside
  user-supplied schema intent.
- Required collections and fields never acquire an implicit null/default through decoding.
- The strict V1 canonical JSON codec bounds each encoded or decoded document to 1,048,576 UTF-8
  bytes, container depth 64, and 100,000 value nodes before family-specific validation. A
  composition of individually valid model values may still fail this document boundary; that is
  a serialization/preflight failure, not silent truncation or semantic normalization.
- Deprecation is documented before removal. Renamed wire fields are not accepted as aliases unless
  a later ADR explicitly makes the alias additive and deterministic.
- Compatibility fixtures are retained for the supported major. Removing one follows this ADR and
  the release support policy, never routine test cleanup.

## Kotlin, Swift, and JSON consequences

- Kotlin raw-backed wrappers and custom serializers preserve supported future values.
- Swift-native raw wrappers preserve the same values through Apple mapping without exposing
  Kotlin serialization types.
- Strict parsing rejects duplicate object members, malformed Unicode/JSON, trailing data, and
  configured document limits before semantic decoding.
- Semantic JSON equality is used unless exact raw preservation is explicitly required.

Representative additive V1 request:

```json
{
  "contractVersion": "1",
  "target": {
    "providerId": "example",
    "modelId": "model-v1",
    "futureTargetMember": true
  },
  "input": [
    {
      "role": "critic_v2",
      "content": "Review this."
    }
  ],
  "responseFormat": {
    "kind": "future_binary"
  },
  "futureRequestMember": {
    "ignored": true
  },
  "extensions": {
    "com.example.request": {
      "preserved": true
    }
  }
}
```

The future ordinary members are dropped, the raw role/format stay distinct, and the extension is
preserved.

## Compatibility and failure behavior

- Current implementations decode retained older V1 fixtures.
- Future additive fixtures prove ignored ordinary members, raw-value retention, and extension
  preservation.
- V1 schemas reject unsupported majors at the `contractVersion` `const` boundary. Direct
  production decoding without a preceding schema pass rejects the same input explicitly with
  `unsupported_contract_version` at `/contractVersion`; it never accepts or guesses the major.
  Unsafe terminal values and malformed raw tokens also fail explicitly.
- No compatibility claim is made for unaccepted pre-release working-tree models.

## Required deterministic tests

- Missing/null/future version markers for every independently encoded envelope.
- Omitted/default/explicit-null behavior at top-level and nested locations.
- Unknown ordinary fields at every family, proving intentional drop on re-encode.
- Every raw-backed family with a future valid value, proving distinct round trip.
- Unknown nonterminal acceptance and unknown terminal rejection.
- Exact extension preservation and semantic JSON comparisons.
- Older/current/future-additive fixture windows in the retained corpus.

## P1 migration

P1 was pre-release and no P8 artifact was published. P2 replaces the temporary P1 string request,
string response, two-field stream event, and closed error enum atomically on the same Kotlin client
and Swift façade. No permanent second compatibility client or deprecated transport-free string
path remains.

## Deferred questions

- P3-P7 own compatibility of transport/provider translations with this canonical major.
- P8 sets released source/binary support windows and package-version policy.
- A future major defines cross-major negotiation or migration; V1 performs none.
