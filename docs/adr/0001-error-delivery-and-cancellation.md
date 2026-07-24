# ADR 0001: Error delivery and cancellation semantics

- Status: Accepted
- Date: 2026-07-24
- Milestone: P2-A

## Context

The canonical contract needs stable, serializable failures without turning coroutine or task
cancellation into a provider failure. P1 already established thrown Kotlin and Swift errors,
caller-owned cancellation, concurrent operations, and exactly-once callback arbitration.

## Decision drivers and considered options

The decision prioritizes idiomatic host APIs, safe messages, raw-value compatibility, and one
observable terminal outcome per operation.

Considered options:

1. Return success and failure as response values. Rejected because it weakens idiomatic
   `suspend`/`async throws` cancellation and permits success/failure ambiguity.
2. Encode failures and cancellation as stream events. Rejected because cancellation is host
   control flow and because a failed stream has no trustworthy final response.
3. Throw host-native failures carrying a serializable canonical error. Selected.

## Decision

- Kotlin response failures throw `UniversalAiException`, which carries one immutable
  `UniversalAiError`. Swift throws `UniversalAiConnectorError`, a Swift-native `Error`.
- Error category and code are validated raw-backed values. Known values have conveniences;
  unknown syntactically valid values remain unchanged and are never converted to a different
  known value.
- `message` is safe for ordinary single-line display and logging but is not a machine
  discriminator. It rejects C0/C1 controls, DEL, Unicode line/paragraph separators, and Unicode
  bidirectional controls; callers still escape it for their output format. `metadata` contains
  only governed JSON-compatible safe data. Namespaced `extensions` remain separate.
- Unexpected implementation failures map to fixed `internal/connector_failure` text without
  copying the source exception message, cause, or unsafe metadata.
- Caller cancellation remains Kotlin `CancellationException` or Swift `CancellationError`.
  It is never a `UniversalAiError`, provider failure, or stream event.
- Kotlin model constructors validate immediately. Canonical JSON decoding performs JSON-shape
  checks, then semantic validation. Swift-native request structs remain nonthrowing value models;
  structural and safety validation occurs before the first suspension, while the private Apple
  adapter performs complete canonical conversion synchronously before it launches a coroutine.
  Either boundary surfaces `UniversalAiConnectorError` with category `validation` and code
  `invalid_request`. In both hosts, client-specific support is validated before connector work,
  response delivery, stream emission, or any future transport side effect.
- Unary success, failure, and cancellation are mutually exclusive. The first terminal outcome
  accepted by the host arbitration state wins; later callbacks are ignored.
- Stream failure or cancellation may follow already-observed partial events. Those events remain
  observable but do not form a successful response. No canonical terminal event is synthesized.
- The private Apple callback adapter emits no callback for cancellation. Swift cancellation still
  completes the waiting task exactly once, including cancellation before handle installation.

## Kotlin, Swift, and JSON consequences

- Kotlin keeps `suspend fun respond(...)` and a failing `Flow`.
- Swift keeps `async throws` and `AsyncThrowingStream`, using only Swift-native `Sendable` models.
- The error JSON representation is provider-neutral and does not include cancellation:

```json
{
  "category": "provider",
  "code": "simulated_failure",
  "message": "The deterministic provider reported a safe failure.",
  "metadata": {
    "retryable": false
  },
  "extensions": {
    "com.example.connector": {
      "trace": "safe-trace"
    }
  }
}
```

- Transport-related categories may exist in the vocabulary, but P2 deterministic behavior does
  not populate transport details.

## Compatibility and failure behavior

- Additive unknown ordinary JSON members are ignored according to ADR 0007.
- Unknown error categories and codes preserve their raw values through Kotlin decoding and Apple
  mapping.
- Invalid category/code syntax, unsafe messages, malformed metadata, and explicit nulls where
  omission is required fail at their documented schema or semantic boundary.
- If cancellation races success or failure, whichever terminal state acquires the host lock first
  is the sole observable outcome.

## Required deterministic tests

- Known and future category/code JSON round trips.
- Invalid syntax, unsafe message, metadata, and extension limits.
- Safe unexpected-failure mapping without source-message leakage.
- Kotlin and Swift response and stream failures.
- A stream that emits partial deltas and then fails: the partial events remain visible, the
  canonical error is thrown, and no `response.completed`, error event, or normal completion is
  observed.
- A stream that emits partial deltas and is then cancelled: the partial events remain visible,
  host cancellation is thrown, and no `response.completed`, error event, or normal completion is
  observed.
- Response and stream cancellation before handle installation and after work begins.
- Cancellation after a partial event, cancellation racing success/error/completion, duplicate
  callbacks, and late callbacks after every terminal kind.
- A valid governed Swift `.jsonSchema` request crosses synchronous Apple conversion before the
  deterministic client reports that structured output is unsupported.
- Concurrent operations prove cancellation isolation.

## P1 migration

The closed three-value P1 error enum is replaced by raw-preserving canonical error models on the
same Kotlin client and Swift façade. No second compatibility client is retained because P1 was
pre-release and no P8 artifact was published.

## Deferred questions

- P3 owns transport request IDs, retry-after data, and redaction behavior.
- P4-P7 own provider-specific failure translation.
- P8 owns released API compatibility guarantees.
