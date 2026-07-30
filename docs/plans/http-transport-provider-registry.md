# P3 HTTP Transport and Provider Registry

## Status and activation gate

P2 and P3 are `Completed`. P3 was activated on July 24, 2026 as the only `In progress` milestone,
and P3-A completed its transport-boundary and construction scope on July 24, 2026. P3-B completed its
URL, header, timeout, canonical-error, and redaction scope on July 27, 2026. P3-C completed its SSE,
response-metadata, and response-content-start scope on July 29, 2026. P3-D completed its provider
registration, lookup, and primary-client routing scope on July 30, 2026. P3-E completed integrated
adapter construction, transport cancellation, streaming cleanup, authoritative terminal behavior,
and acceptance reconciliation on July 30, 2026. P4 remains `Not started` and requires separate
activation.

The accepted activation transition:

1. keeps every earlier milestone `Completed`;
2. marks P3 as the only `In progress` milestone;
3. initially named P3-A as the active work package; and
4. leaves P4-P9 `Not started`.

The accepted P3-A transition added only the transport boundary, construction, ownership, cleanup,
and focused lifecycle tests named below. The P3-B transition added only URL, header, timeout,
canonical-error, and redaction policy plus the deterministic tests named below. The P3-C
transition added only SSE framing, response metadata, response-content-start behavior, and the
deterministic tests named below. The P3-D transition added only the internal provider registry,
primary-client lookup, and deterministic tests named below. The P3-E transition bound internal
adapter factories to each connector transport, integrated cancellation and streaming cleanup,
made the first valid terminal authoritative, and reconciled deterministic acceptance evidence.
Provider adapters remain inactive.

## Objective

Establish the provider-neutral transport and provider-registration boundary that P4-P7 adapters
will use. P3 must add deterministic HTTP mechanics without implementing a provider protocol,
making a live request, or exposing Ktor and provider DTOs as ordinary host concerns.

The completed milestone must provide:

- injectable Ktor-backed transport behavior;
- normalized base URLs and safely composed request headers;
- explicit connection and request timeout behavior;
- incremental SSE framing and parsing;
- request-ID and retry-after metadata extraction;
- cancellation propagation through request and stream lifecycles;
- redacted diagnostic output;
- a provider registry for selecting internal adapters without provider-specific host APIs; and
- explicit ownership and cleanup for default and injected resources.

## Design constraints

- Keep platform-neutral transport, registry, lifecycle, and parsing behavior in Kotlin
  `commonMain` where Ktor supports it.
- Preserve one primary Kotlin client and one Swift façade with their existing canonical models,
  native concurrency, stable errors, and cancellation.
- Ordinary consumers use a simple default construction path and do not construct a Ktor
  `HttpClient`.
- Advanced consumers may inject a transport or engine for control and deterministic tests.
- Public construction must distinguish connector-owned resources from caller-owned resources;
  cleanup closes only what the connector owns and is safe to call repeatedly.
- Keep engines, Ktor request/response types, coroutine scopes, callback plumbing, and registry
  implementation details out of the supported Swift surface.
- Keep provider DTOs and protocol translation out of P3. Registry entries expose only
  provider-neutral identity, configuration, capabilities needed for routing, and internal adapter
  factories or handles.
- Generation retries are disabled by default. No reconnect or retry is allowed after any response
  content begins.
- Reject or redact secrets before diagnostics are emitted. Never log authorization, API-key,
  cookie, proxy-authorization, or equivalent sensitive header values.
- Reuse the P1-P2 host targets, samples, packaging, and CI lanes. Do not add a host target, sample,
  provider-specific host method, or CI lane.
- Avoid an incidental Gradle module split. A topology change requires a separate bounded
  architecture decision.

## Scope

### In scope

- Ktor client dependencies required by shared transport and supported platform engines;
- a provider-neutral internal HTTP request, response, byte-stream, and metadata boundary;
- default platform transport selection behind the primary client construction path;
- advanced injection of a connector transport or Ktor engine without transferring caller-owned
  resource ownership;
- canonical base URL normalization and relative endpoint resolution;
- protected-header composition and validation;
- connect and request timeout configuration with deterministic error mapping;
- incremental SSE line, field, event, comment, delimiter, UTF-8, and end-of-stream handling;
- extraction and normalization of request IDs and `Retry-After` metadata;
- cancellation propagation for pending responses, active streams, parsers, and engine calls;
- redacted, bounded diagnostics and regression tests for sensitive values;
- deterministic provider registration, lookup, duplicate handling, and unknown-provider behavior;
- exactly-once terminal delivery and cleanup under success, error, cancellation, and close races;
- focused construction changes to the Kotlin client and Swift façade only when required for
  injection, ownership, or cleanup; and
- deterministic Ktor `MockEngine` tests and committed local fixtures.

### Out of scope

- OpenAI, Anthropic, OpenRouter, OpenAI-compatible, or Gateway request/response DTOs;
- provider authentication schemes, signing, payload translation, model discovery, or capability
  probing;
- a real provider or Gateway adapter;
- live network calls, credentials, `check-live.sh`, or a protected secret-bearing workflow;
- automatic generation retry, reconnect-after-content, or a general retry policy;
- application storage, credential storage, provider-selection UI, OpenKeyboard, or Gateway V1;
- new host targets, samples, public per-provider APIs, or duplicated platform implementations;
- Maven publication, remote Swift Package distribution, signing, checksums, or released-artifact
  consumers; and
- physical-device, emulator, live-provider, Gateway, or distribution claims.

## Transport contract

### Construction and ownership

- The default client creates and owns the supported platform engine and transport.
- An injected transport remains caller-owned unless an explicit ownership option says otherwise;
  the default injection path never closes caller-owned resources.
- Engine injection creates a connector-owned client around the injected engine only when Ktor's
  ownership contract permits that distinction to be enforced and tested.
- Closing the connector cancels its in-flight work, releases connector-owned resources exactly
  once, and prevents new work with a stable closed-state error.
- Closing one client must not disrupt another client that shares a caller-owned transport.
- Construction failures must release partially created connector-owned resources.

### URLs, headers, and timeouts

- Normalize a configured base URL once, preserving scheme, authority, optional path prefix, and
  explicit port while rejecting credentials, fragments, unsupported schemes, and ambiguous
  traversal.
- Resolve adapter-relative endpoint paths without dropping a base path or permitting the endpoint
  to replace the configured authority.
- Treat header names case-insensitively. Protect transport-owned headers from unsafe duplicates or
  overrides and define deterministic precedence for safe caller and adapter headers.
- Reject control characters and other values that could enable header injection.
- Redaction operates by normalized header name and covers diagnostic errors as well as request
  logs.
- Distinguish connection and whole-request timeouts. Map timeouts to the existing canonical error
  model without converting caller cancellation into a timeout.

### Streaming and metadata

- Parse SSE incrementally across arbitrary byte boundaries and CRLF or LF line endings.
- Combine consecutive `data` fields with newline separators, ignore comments, retain defined
  `event`, `id`, and `retry` fields as transport metadata, and dispatch only on a blank-line event
  delimiter. End-of-stream discards any final unterminated event.
- Reject malformed UTF-8 deterministically without exposing partial response content as a
  retryable pre-content failure. Ignore an invalid, negative, or overflowing SSE `retry` field for
  that event while continuing to process its other fields.
- Bound each decoded SSE line and dispatched event-data value to 1 MiB, and bound a valid SSE
  `retry` value to one day. Exceeding the line or event-data bound is a fixed safe transport
  failure; an excessive optional `retry` field remains absent.
- Response content begins immediately after the body reader obtains its first non-empty byte chunk
  and before that chunk is returned to the response consumer or parser. At and after that point,
  transport reconnect and generation retry are forbidden.
- Extract request IDs case-insensitively using `x-request-id`, `request-id`, then
  `x-correlation-id` precedence. Within one name, the first valid field wins. Normalize surrounding
  optional whitespace, reject control characters, and bound the retained value to 256 UTF-8 bytes.
- Parse the first valid `Retry-After` delta-seconds or HTTP-date field, whose raw value is bounded
  to 128 bytes, into a non-negative delay bounded to one day. Invalid, negative, past, oversized,
  or overflowing values remain absent rather than driving a retry.
- Preserve backpressure and cancel the underlying response body when a stream consumer cancels.

## Provider registry contract

- Register internal provider adapter descriptors by normalized provider identifier.
- Reject duplicate identifiers deterministically instead of silently replacing an entry.
- Resolve unknown identifiers during client-specific preflight validation to the existing canonical
  `validation/invalid_request` mapping. P3 does not add a new canonical error category or code for
  registry lookup.
- Preserve deterministic registration and lookup behavior under concurrent reads.
- Keep registry mutation confined to construction or an explicitly synchronized internal
  lifecycle; ordinary requests do not mutate global state.
- Default construction registers only adapters delivered by completed milestones. During P3 that
  set is empty, so deterministic fixtures may install fake internal entries without claiming a
  supported provider.
- Registry descriptors must not contain credentials, host UI state, provider wire DTOs, or
  platform-specific implementations.

## Work packages

Execute the packages in order and keep only one active package at a time. P3-A through P3-E are
complete.

### P3-A: Transport boundary and construction

Status: `Completed` on July 24, 2026.

- Add the minimum Ktor dependencies and supported engine wiring.
- Define the internal provider-neutral transport boundary.
- Implement default and injected construction with explicit ownership.
- Add close, partial-construction failure, shared-injection, and use-after-close tests.
- Preserve the current deterministic client behavior while the transport is not yet connected to
  an adapter.

### P3-B: URL, header, timeout, and redaction policy

Status: `Completed` on July 27, 2026.

- Implement base URL validation, normalization, and endpoint resolution.
- Implement protected-header composition and injection rejection.
- Implement connect and request timeout configuration and canonical mapping.
- Add bounded diagnostic redaction and adversarial secret-leak fixtures.

### P3-C: SSE and response metadata

Status: `Completed` on July 29, 2026.

- Implement incremental SSE framing and parsing.
- Extract request-ID and retry-after metadata.
- Define and enforce the response-content-start boundary.
- Add chunk-boundary, line-ending, malformed-input, cancellation, and end-of-stream fixtures.

### P3-D: Provider registry

Status: `Completed` on July 30, 2026.

- Implement deterministic internal registration and lookup.
- Cover duplicate, unknown, normalized-identifier, concurrent-read, and lifecycle cases.
- Use fake internal descriptors only; do not add a real provider or provider DTO.
- Connect registry lookup to the primary client without exposing provider-specific host APIs.

### P3-E: Lifecycle integration and acceptance

Status: `Completed` on July 30, 2026.

- Integrate transport cancellation, streaming cleanup, and exactly-once terminal behavior.
- Apply only construction, injection, ownership, and cleanup changes required in the supported
  Kotlin and Swift entry points.
- Compile all affected existing consumers through public package boundaries.
- Complete redaction, artifact-boundary, provider-DTO, and no-live-network audits.
- Run the complete deterministic exact-head matrix and reconcile plan and roadmap evidence.
- Mark P3 complete only in a separate milestone-closing change after every acceptance criterion
  has durable evidence.

## Test matrix

### Shared transport tests

- default, injected, shared, failed, closed, and repeatedly closed resource lifecycles;
- base URL normalization and endpoint resolution, including traversal and authority-replacement
  attempts;
- protected-header precedence, case normalization, control-character rejection, and redaction;
- connection timeout, request timeout, caller cancellation, and close races;
- response and stream success, transport failure, cancellation, and exactly-once terminal delivery;
- request-ID precedence and retry-after delta-seconds, date, invalid, negative, and overflow cases;
- disabled generation retry and the no-reconnect-after-content boundary; and
- concurrent requests and streams with isolated cancellation.

### SSE fixtures

- LF and CRLF records;
- comments, blank events, repeated fields, multi-line data, and optional event IDs;
- BOM and UTF-8 code points split across arbitrary byte chunks;
- delimiters and field names split across chunks;
- malformed UTF-8, invalid retry fields, truncated records, and end-of-stream;
- cancellation before headers, before content, between events, and during a partial event; and
- proof that no event or terminal signal is emitted after cancellation or terminal completion.

### Registry tests

- empty default registry during P3;
- fake descriptor registration and normalized lookup;
- duplicates and unknown identifiers;
- deterministic ordering where observable;
- concurrent lookup;
- registry/client close interaction; and
- public-signature scans excluding registry implementations and provider DTOs.

### Host and package integration

- JVM and Android consumers continue to use the same primary Kotlin client;
- Swift Package tests cover default construction, injected test behavior where supported, close,
  cancellation, stable errors, and stream cleanup through the Swift façade;
- XCFramework headers contain no Ktor, engine, registry implementation, coroutine scope, or
  generated callback plumbing in the supported Swift API;
- Apple-only adapters remain absent from JVM and Android artifacts; and
- existing samples require no provider credentials and make no live request.

## Verification

During implementation, use focused deterministic checks:

```bash
./gradlew :bridge:jvmTest
./gradlew :bridge:testAndroidHostTest
./gradlew :bridge:iosSimulatorArm64Test
```

Run the existing affected consumer, Swift, framework, and sample checks when a construction or
public lifecycle surface changes. Every committed implementation package passes the repository
quick gate; the P3 acceptance candidate passes the full gate:

```bash
./scripts/check.sh --quick
./scripts/check.sh --full
```

P3 completion requires exact-head Linux, Windows, and macOS CI plus independent exact-head review
and guarded merge. All P3 verification is deterministic and secretless through Ktor `MockEngine`
and local fixtures.

No live-provider gate applies while P3 is first implemented because no provider adapter is active.
After P4 establishes the live suite, any later P3 change that can affect authentication, transport,
streaming, retry, error mapping, cancellation, or redaction must also pass the affected local
pre-PR live gate and protected exact-head GitHub live gate required by the roadmap.

For documentation-only changes to this plan, run:

```bash
./scripts/check.sh --hygiene
```

Plan authoring does not exercise runtime behavior.

## Acceptance criteria

- P3 was separately activated after P2 completion and remained the only `In progress` milestone
  during implementation.
- Ordinary Kotlin and Swift consumers can use default construction without creating a Ktor
  `HttpClient`.
- Advanced deterministic tests can inject a transport or engine with documented ownership.
- Connector-owned resources close exactly once; caller-owned resources remain usable after a
  connector closes.
- Base URLs and relative endpoints resolve deterministically without authority replacement,
  credential embedding, or path traversal.
- Header composition is case-insensitive, rejects injection, protects transport-owned values, and
  emits no sensitive header value through diagnostics.
- Connection and request timeouts map to stable canonical errors while caller cancellation remains
  cancellation.
- SSE parsing is incremental across arbitrary chunks, line endings, and UTF-8 boundaries and
  preserves defined field and delimiter semantics.
- Request-ID and retry-after metadata extraction is deterministic, bounded, and tolerant of
  invalid optional metadata.
- Pending responses and active streams propagate cancellation to Ktor and suppress late events or
  duplicate terminal delivery.
- Generation retries remain disabled by default and no reconnect or retry occurs after response
  content begins.
- The registry handles fake registration, duplicates, unknown identifiers, concurrent lookup, and
  cleanup deterministically without global mutable provider state.
- No provider DTO, engine, Ktor request/response type, registry implementation, coroutine scope, or
  callback plumbing appears in an ordinary supported host API.
- Existing JVM, Android, and iOS consumers compile through package boundaries without credentials,
  live calls, new targets, or provider-specific controls.
- Deterministic `MockEngine` and fixture tests, the full local gate, exact-head CI, and independent
  review pass for the P3 closing head.

## Proof limits

P3 can prove deterministic HTTP construction, URL and header policy, timeout and cancellation
mapping, SSE parsing, response metadata extraction, redaction, registry selection, ownership,
cleanup, packaging, and existing-host consumption against Ktor `MockEngine` and local fixtures.

P3 does not prove:

- compatibility with an OpenAI, Anthropic, OpenRouter, compatible-provider, or Gateway endpoint;
- provider authentication, request translation, response translation, structured output,
  capability reporting, or canonical provider error mapping;
- DNS, TLS, proxy, HTTP/2, network-loss, rate-limit, or server behavior on a live network;
- live response, streaming, error, retry, cancellation, or redaction behavior;
- a correct provider-advertised request ID or retry-after value;
- physical-device or Android-emulator execution;
- remote Maven or Swift Package distribution, signing, checksums, or released-artifact
  consumption;
- OpenKeyboard or Gateway V1 integration; or
- release readiness for `0.1.0-alpha.1`.

The completed P3-A package proves deterministic provider-neutral request/response forwarding,
platform-default engine construction, caller-owned engine injection, chunked response reads,
callback failure cleanup, connector-owned exactly-once cleanup, shared borrowed-resource survival,
partial-construction cleanup, active-operation cancellation, stable use-after-close errors, and
existing Kotlin and Swift behavior through local `MockEngine`, host, package, and sample checks.

The completed P3-B package proves deterministic base-URL validation and endpoint resolution,
case-insensitive protected-header composition, header-injection rejection, applied connect and
whole-request timeout configuration, canonical timeout and transport-failure mapping, caller
cancellation preservation, and bounded sensitive-header redaction through common-code and
`MockEngine` tests. It does not prove P3-C SSE or response metadata, P3-D registry behavior, P3-E
integrated adapter lifecycles, any provider or Gateway behavior, or any live-network behavior.

The completed P3-C package proves deterministic incremental SSE framing across arbitrary chunks,
LF/CRLF/CR line endings, split UTF-8 code points, BOM, defined fields, blank delimiters, comments,
malformed UTF-8, bounded input, cancellation, and unterminated end-of-stream behavior. It also
proves case-insensitive bounded request-ID and retry-after extraction plus the exact first-body-byte
content-start boundary through common-code and `MockEngine` tests. It does not prove P3-D registry
behavior, P3-E integrated adapter lifecycles, provider or Gateway protocol behavior, automatic
retry or reconnect behavior, or any live-network behavior.

The completed P3-D package proves immutable per-client registration and lookup by canonical
provider identifier, deterministic ordering and duplicate rejection, concurrent reads, canonical
unknown-provider preflight, fake response and stream routing through the primary client, and
registry construction and client-close behavior through common-code tests. It does not prove P3-E
integrated transport and adapter lifecycles, a real provider or Gateway adapter, provider
capability discovery, or any live-network behavior.

## Completion evidence

For every activated P3 package, record:

- branch and exact head SHA;
- active package and changed transport or registry surfaces;
- deterministic fixtures and tests added;
- construction and resource-ownership paths exercised;
- commands executed and results;
- host consumers and package boundaries compiled;
- retry, cancellation, terminal, and redaction behavior proven;
- proof limits and unexercised surfaces; and
- the next incomplete P3 package.

### P3-A completion record

- Candidate branch: `feature/p3-transport-boundary`; the exact reviewed head, CI run, merge, and
  resulting `main` evidence belong in the pull-request brief because embedding a candidate SHA in
  its own commit would change that SHA.
- Added Ktor 3.5.1 core, CIO, Android, Darwin, and MockEngine dependencies in the existing
  `:bridge` module, with explicit platform-default client construction.
- Added an internal provider-neutral callback-scoped request, response, raw-header, and byte-chunk
  transport plus default-owned and caller-engine-injected construction.
- Added deterministic forwarding, multi-chunk read, callback cleanup, owned/borrowed/shared
  lifecycle, partial-construction failure, concurrent close, active cancellation, use-after-close,
  Swift continuation/stream race, and public-header leak tests.
- Updated Kotlin, Android, JVM, Apple bridge, Swift façade, and sample lifecycle boundaries without
  connecting the transport to a provider adapter or making a live request.
- Focused JVM, Android host, iOS Simulator, consumer, Swift Package, XCFramework, and iOS sample
  checks passed; the commit/push hooks, exact-head CI, independent review, and guarded merge remain
  the authoritative Release gates for the package transition.
- Proof remains deterministic and secretless. No P3-B policy, P3-C parsing/metadata, P3-D registry,
  provider, Gateway, physical-device execution, live-network behavior, or distribution is proven.
- Next package: P3-B URL, header, timeout, and redaction policy.

### P3-B completion record

- Candidate branch: `feature/p3-transport-policy`; the exact reviewed head, CI run, merge, and
  resulting `main` evidence belong in the pull-request brief because embedding a candidate SHA in
  its own commit would change that SHA.
- Added common-code base-URL validation and normalization that rejects credentials, fragments,
  unsupported schemes, ambiguous traversal, encoded separators, and authority-replacing
  endpoints while preserving optional path prefixes and explicit ports.
- Added deterministic case-insensitive caller, adapter, and transport header composition with
  protected transport-owned names, fixed safe validation failures, strict control-character and
  size rejection, and repeated-field ordering for the winning source.
- Installed Ktor's timeout plugin on every supported transport construction path, applied bounded
  connect and whole-request timeout settings per request, and mapped connect timeout, request
  timeout, and pre-response I/O failure to fixed canonical transport errors while preserving
  caller cancellation.
- Added bounded diagnostic redaction by normalized sensitive header name, including adversarial
  fixtures that place sensitive values in diagnostic text, non-sensitive header values, malformed
  header fields, and oversized diagnostics.
- Focused JVM, Android host, and iOS Simulator transport-policy and lifecycle tests passed. The
  commit/push hooks, exact-head CI, independent review, and guarded merge remain the authoritative
  Release gates for this package transition.
- Proof remains deterministic and secretless. No SSE parsing or response metadata, provider
  registry, provider or Gateway adapter, live networking, physical-device execution, or
  distribution is proven.
- Next package: P3-C SSE and response metadata.

### P3-C completion record

- Candidate branch: `feature/p3c-sse-metadata`; the exact reviewed head, CI run, merge, and
  resulting `main` evidence belong in the pull-request brief because embedding a candidate SHA in
  its own commit would change that SHA.
- Added a pull-based common-code SSE reader that preserves backpressure, handles arbitrary byte
  boundaries and LF/CRLF/CR delimiters, validates split UTF-8 and BOM input, combines `data`
  fields, retains `event`, `id`, and bounded valid `retry` metadata, ignores comments and invalid
  optional fields, and discards unterminated end-of-stream records.
- Added fixed safe malformed-stream mapping, 1 MiB line and event-data bounds, cancellation-terminal
  parser behavior, and deterministic fixtures for one-byte chunks, multi-byte Unicode, repeated
  fields, blank events, malformed UTF-8, excessive input, partial records, and cancellation during
  or between events.
- Added case-insensitive request-ID extraction with documented precedence and a 256-byte bound,
  plus delta-seconds and HTTP-date `Retry-After` extraction bounded to one day.
- Defined response content as started immediately after the first non-empty body chunk is obtained
  and before it reaches the response consumer or parser. Generation retries remain disabled, and
  the retained boundary makes any future post-content retry or reconnect ineligible.
- Focused JVM, Android host, and iOS Simulator transport tests passed. The commit/push hooks,
  exact-head CI, independent review, and guarded merge remain the authoritative Release gates for
  the package transition.
- Proof remains deterministic and secretless. No provider registry, provider or Gateway adapter,
  automatic retry or reconnect, live networking, physical-device execution, or distribution is
  proven.
- Next package: P3-D provider registry.

### P3-D completion record

- Candidate branch: `feature/p3d-provider-registry`; the exact reviewed head, CI run, merge, and
  resulting `main` evidence belong in the pull-request brief because embedding a candidate SHA in
  its own commit would change that SHA.
- Added an immutable per-client internal registry whose canonical `ProviderId` keys retain their
  validated raw values, whose observable ordering is deterministic, and whose duplicate detection
  runs during construction without request-time or global mutation.
- Added internal primary-client routing that selects registered fake adapter handles, preserves
  the accepted zero-network deterministic mode, and maps an unknown provider to the existing
  canonical `validation/invalid_request` error.
- Added deterministic empty-registry, canonical-identifier, ordering, duplicate, concurrent-read,
  fake response and stream routing, client-close, and failed-construction cleanup tests.
- Focused JVM, Android host, and iOS Simulator tests passed. The commit/push hooks, exact-head CI,
  independent review, and guarded merge remain the authoritative Release gates for the package
  transition.
- Proof remains deterministic and secretless. No real provider or Gateway adapter, provider DTO,
  authentication, capability discovery, live networking, physical-device execution, or
  distribution is proven.
- Next package: P3-E lifecycle integration and acceptance.

### P3-E completion record

- Candidate branch: `feature/p3e-lifecycle-acceptance`; the exact reviewed head, CI run, merge, and
  resulting `main` evidence belong in the pull-request brief because embedding a candidate SHA in
  its own commit would change that SHA.
- Bound each internal provider adapter factory exactly once to its connector's transport after
  deterministic duplicate validation, without exposing transport or registry implementation
  types through supported host APIs.
- Made the common Kotlin stream boundary validate successful canonical sequences, deliver the
  first valid terminal exactly once, suppress duplicate terminals, late frames, and late failures,
  and convert a normal end without a terminal into a fixed safe connector failure. The Swift
  façade retains its native cancellation and terminal arbitration over this shared behavior.
- Added deterministic integrated `MockEngine` coverage for pending-response cancellation through a
  registered adapter, active response-body cancellation and cleanup, missing-terminal rejection,
  and authoritative-terminal behavior with late upstream data. Existing concurrency, shared
  ownership, close-race, SSE cancellation, redaction, and registry tests remain in the matrix.
- Focused JVM, Android host, and iOS Simulator lifecycle suites passed. The mandatory commit/push
  hooks, complete exact-head local gate, exact-head CI, independent review, guarded merge, and
  resulting `main` inspection remain the authoritative Release evidence in the pull-request brief.
- Redaction remains bounded by normalized sensitive-header names; existing artifact/header and
  provider-neutral public-surface checks exclude Ktor, registry implementations, provider DTOs,
  coroutine plumbing, and Apple-only bridge types from unsupported boundaries. Deterministic
  samples retain zero-network behavior and require no credentials.
- Proof remains deterministic and secretless. No real provider or Gateway adapter,
  authentication, provider DTO, live networking, physical-device or Android-emulator execution,
  remote distribution, or alpha-release readiness is proven.
- Next milestone: P4 OpenAI Responses adapter, which remains `Not started` until separately
  activated.

This atomic P3 closeout is accepted only after the roadmap acceptance criteria, this plan's
acceptance criteria, the full exact-head local gate, independent exact-head review, required
GitHub checks, guarded merge, and resulting `main` verification agree. Exact self-referential
evidence remains in the milestone-closing pull-request brief. P4 is the next incomplete milestone
but remains `Not started`.
