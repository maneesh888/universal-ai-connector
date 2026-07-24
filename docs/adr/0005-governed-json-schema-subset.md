# ADR 0005: Governed JSON Schema subset and validation policy

- Status: Accepted
- Date: 2026-07-24
- Milestone: P2-C

## Context

P2 uses JSON Schema in two different roles: repository schemas describe connector wire contracts,
while a caller may supply schema intent for structured model output. Treating the latter as an
unbounded general-purpose schema creates recursion, resource, and cross-host compatibility risks.

## Decision drivers and considered options

Considered options:

1. Accept arbitrary JSON Schema. Rejected because implementations, recursion, external resources,
   and evaluation limits would vary across hosts.
2. Accept only a bespoke non-schema shape language. Rejected because it loses familiar portable
   JSON Schema concepts.
3. Accept a deterministic, bounded Draft 2020-12 subset as request intent. Selected.

## Decision

- User-supplied structured-output schemas use JSON Schema Draft 2020-12 and are distinct from the
  connector-contract schema bundle selected by ADR 0006.
- A user schema is an object or Boolean schema. Supported keywords are:
  `$schema`, `$defs`, `$ref`, `$comment`, `title`, `description`, `default`, `deprecated`,
  `examples`, `format`, `type`, `enum`, `const`, `minimum`, `exclusiveMinimum`, `maximum`,
  `exclusiveMaximum`, `minLength`, `maxLength`, `properties`, `required`,
  `additionalProperties`, `minProperties`, `maxProperties`, `items`, `prefixItems`, `minItems`,
  `maxItems`, `allOf`, `anyOf`, `oneOf`, and `not`.
- `$schema`, when present, is exactly `https://json-schema.org/draft/2020-12/schema`.
- `$ref` is limited to an existing local `#/$defs/<name>` target. External references, anchors,
  arbitrary pointers, percent-encoded targets, and recursive reference cycles are rejected.
- Maximum compact UTF-8 size is 65,536 bytes, schema-container depth is 32, and schema-node count
  is 512. `$defs`, `properties`, `prefixItems`, `required`, and `enum` contain at most 256
  entries; composition arrays contain at most 32 branches.
- Type arrays are nonempty, contain no duplicates, and use only valid member types. A `required`
  list may be empty as permitted by Draft 2020-12, but contains no duplicate names. `enum` is
  nonempty and has no semantically duplicate values.
- Defaults, examples, constants, enum members, and annotations are literal instance data rather
  than nested schemas. They are retained as intent but are never applied by the connector.
  `format` is annotation only. P2 does not fetch resources or execute custom vocabularies.
- A schema may express null through `"type":"null"`, a type union containing `null`, or literal
  null inside `const`, `default`, `enum`, or `examples`. The subset validator does not perform
  cross-keyword instance evaluation, and these literals do not change canonical-contract
  omission/null rules.
- Validation occurs when Kotlin constructs/decodes the schema and when a Swift request begins
  conversion at the async operation boundary. Swift errors report the same stable validation code
  and full canonical request path, including `/responseFormat/schema`.
- Validation proves the schema document is governed request intent. P2 does not claim that a live
  provider honors it and does not validate deterministic output instances against it because the
  P2 deterministic client supports only plain text.

## Kotlin, Swift, and JSON consequences

- Kotlin keeps schema DOM and validator types internal; consumers pass ordinary schema JSON into a
  typed wrapper.
- Swift uses native recursive values and receives a stable
  `UniversalAiConnectorError(validation/invalid_request)` if operation-start conversion fails.
- The connector wire schema describes where this user schema appears but does not replace the
  governed semantic validator.

Representative intent:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$defs": {
    "answer": {
      "type": "string",
      "minLength": 1
    }
  },
  "type": "object",
  "properties": {
    "answer": {
      "$ref": "#/$defs/answer"
    }
  },
  "required": [
    "answer"
  ],
  "additionalProperties": false
}
```

## Compatibility and failure behavior

- Unsupported keywords and reference forms fail rather than being silently ignored.
- Future schema behavior requires a later contract version or an additive accepted ADR change; a
  reader never pretends to enforce a keyword it does not implement.
- Invalid shape, keyword values, bounds, references, and recursion report a stable semantic code
  and JSON Pointer.
- Unknown ordinary fields in connector contracts remain governed by ADR 0007; they are unrelated
  to unknown schema keywords, which fail closed.

## Required deterministic tests

- Boolean, object, minimal, and complete schemas.
- Every supported keyword category and every documented bound.
- Unsupported keyword/dialect, external/unresolved/recursive reference, malformed type,
  duplicate enum/required/type values, and invalid keyword shapes.
- Full Kotlin and Apple request-path prefixes for every schema failure.
- Swift request conversion tests for a valid schema, an unsupported keyword, missing schema, and
  plain-text-with-schema.
- Semantic-number equality in enum values and no default application.

## P1 migration

Structured-output intent is additive to the canonical request. The deterministic P2 client rejects
it as unsupported before work begins; it does not introduce a second operation.

## Deferred questions

- P3 owns transport limits and serialization into transport requests.
- P4-P7 own provider translation and live structured-output support.
- P9 owns live conformance evidence.
- Output-instance validation may be standardized in a later milestone or contract version.
