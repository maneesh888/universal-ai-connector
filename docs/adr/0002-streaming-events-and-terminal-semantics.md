# ADR 0002: Streaming events and terminal semantics

- Status: Accepted
- Date: 2026-07-24
- Milestone: P2-A

## Context

P2 needs a provider-neutral stream that preserves correlation, validates ordering, exposes a
complete final response, and retains P1 cancellation and exactly-once behavior.

## Decision drivers and considered options

Considered options:

1. Snapshot-only events. Rejected because they duplicate growing content and obscure provider
   delta behavior.
2. Deltas with normal flow completion as the only success terminal. Rejected because consumers
   need an explicit, correlated final response and usage.
3. Deltas plus one explicit semantic success terminal, followed by normal host-stream completion.
   Selected.
4. Error and cancellation terminal events. Rejected by ADR 0001.

## Decision

- Known event types are `response.started`, `output.started`, `output.delta`,
  `output.completed`, `usage.updated`, and `response.completed`.
- Sequence numbers start at 1, are contiguous, and describe observation order for one stream.
- `requestId` is optional but, once established by the first event, remains stable.
  `responseId` is required and stable. Output events carry a stable `outputId`/`outputIndex` pair.
- Text and structured output use deltas followed by an authoritative output snapshot.
  Concatenated portable deltas must equal the completed output content.
- Usage events are cumulative and non-decreasing. If usage was emitted, the final response
  includes usage at least as large as the last update.
- `response.completed` is the only known terminal event. It has `terminal: true`, carries the
  complete canonical response, and is followed by normal `Flow`/`AsyncThrowingStream` completion.
  Every other known event has `terminal: false`.
- Unknown syntactically valid event types may be retained only as nonterminal events. An unknown
  terminal type fails with `unsupported_terminal_event` because V1 cannot safely infer its
  terminal meaning.
- A stream sequence validator rejects missing start/terminal events, gaps, correlation changes,
  duplicate starts or terminals, output events before start or after completion, mismatched final
  outputs, usage regression, and events after the terminal.
- Failure and cancellation are thrown out of band and produce no terminal event. Partial events
  remain observable but are not success.
- Kotlin performs sequence validation in deterministic production behavior. Every producer or
  adapter must stop consuming or ignore upstream frames after the first accepted terminal. The
  Apple adapter and Swift locked state suppress duplicate terminals and late events; the first
  terminal outcome wins.

## Kotlin, Swift, and JSON consequences

- Kotlin exposes one cold `Flow<UniversalAiStreamEvent>` per call.
- Swift exposes one `AsyncThrowingStream<UniversalAiStreamEvent, Error>` per call. Each returned
  stream supports one consuming task; separate calls provide concurrency.
- A successful sequence has this shape:

```json
[
  {
    "contractVersion": "1",
    "type": "response.started",
    "terminal": false,
    "sequence": 1,
    "responseId": "resp_1"
  },
  {
    "contractVersion": "1",
    "type": "output.started",
    "terminal": false,
    "sequence": 2,
    "responseId": "resp_1",
    "outputId": "out_0",
    "outputIndex": 0
  },
  {
    "contractVersion": "1",
    "type": "output.completed",
    "terminal": false,
    "sequence": 3,
    "responseId": "resp_1",
    "outputId": "out_0",
    "outputIndex": 0,
    "output": {
      "id": "out_0",
      "index": 0,
      "kind": "text",
      "text": "ok"
    }
  },
  {
    "contractVersion": "1",
    "type": "response.completed",
    "terminal": true,
    "sequence": 4,
    "responseId": "resp_1",
    "response": {
      "contractVersion": "1",
      "id": "resp_1",
      "target": {
        "providerId": "example",
        "modelId": "model-v1"
      },
      "outputs": [
        {
          "id": "out_0",
          "index": 0,
          "kind": "text",
          "text": "ok"
        }
      ],
      "completionReason": "stop"
    }
  }
]
```

## Compatibility and failure behavior

- Unknown nonterminal event values preserve their raw type and governed extensions.
- Unknown ordinary members are ignored; extension data is preserved under ADR 0004.
- A stream that ends without `response.completed` is `incomplete_stream`.
- Duplicate terminal and late-event attempts are suppressed at the host callback boundary and
  rejected by the sequence validator when validating decoded sequences.

## Required deterministic tests

- Minimal and complete sequences, multiple outputs, deltas, cumulative usage, and final response.
- Gaps, duplicate starts/terminals, missing terminal, correlation changes, output ordering,
  delta/snapshot mismatch, usage regression, and late events.
- Unknown nonterminal acceptance and unknown terminal rejection.
- Kotlin cancellation and Swift consuming-task cancellation before handle installation, after an
  event, and racing completion.
- Exactly-once completion/error/cancellation and late-callback suppression.
- Partial-delta-then-error and partial-delta-then-cancellation paths, proving no semantic terminal,
  error event, or normal completion is synthesized.

## P1 migration

The P1 `sequence/text` event is replaced on the existing clients with the correlated canonical
event. The deterministic sample emits a six-event sequence and all three samples migrate together.

## Deferred questions

- P3 owns SSE framing and transport termination.
- P4-P7 own provider-event translation and partial-provider-failure mapping.
- Resume tokens, reconnect, and retry after content begins are not P2 behavior.
