# P3 HTTP Transport and Provider Registry

## Status and activation gate

P2 is `Completed`. P3 is `Not started`, no work package is active, and this plan does not activate
P3 or authorize product-code changes.

P3 implementation may begin only through a separate roadmap transition that:

1. keeps every earlier milestone `Completed`;
2. marks P3 as the only `In progress` milestone;
3. names P3-A as the active work package; and
4. leaves P4-P9 `Not started`.

Until that transition is accepted, do not add Ktor dependencies, transport or registry production
types, HTTP engines, SSE parsers, network configuration, or provider adapters.

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

- activating P3 as part of this planning change;
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
  `event`, `id`, and `retry` fields as transport metadata, and dispatch only on a complete event
  delimiter or defined end-of-stream rule.
- Reject malformed UTF-8 and invalid numeric metadata deterministically without exposing partial
  response content as a retryable pre-content failure.
- Define the exact point at which response content has begun. At and after that point, transport
  reconnect and generation retry are forbidden.
- Extract request IDs from a documented, case-insensitive allowlist with deterministic precedence.
- Parse `Retry-After` delta-seconds and HTTP-date forms into bounded metadata. Invalid, negative, or
  overflowing values remain absent rather than driving a retry.
- Preserve backpressure and cancel the underlying response body when a stream consumer cancels.

## Provider registry contract

- Register internal provider adapter descriptors by normalized provider identifier.
- Reject duplicate identifiers deterministically instead of silently replacing an entry.
- Resolve unknown identifiers to the existing stable canonical unsupported-provider or
  configuration error.
- Preserve deterministic registration and lookup behavior under concurrent reads.
- Keep registry mutation confined to construction or an explicitly synchronized internal
  lifecycle; ordinary requests do not mutate global state.
- Default construction registers only adapters delivered by completed milestones. During P3 that
  set is empty, so deterministic fixtures may install fake internal entries without claiming a
  supported provider.
- Registry descriptors must not contain credentials, host UI state, provider wire DTOs, or
  platform-specific implementations.

## Work packages

All packages remain inactive until the roadmap activates P3. Execute them in order and keep only
one active package at a time.

### P3-A: Transport boundary and construction

- Add the minimum Ktor dependencies and supported engine wiring.
- Define the internal provider-neutral transport boundary.
- Implement default and injected construction with explicit ownership.
- Add close, partial-construction failure, shared-injection, and use-after-close tests.
- Preserve the current deterministic client behavior while the transport is not yet connected to
  an adapter.

### P3-B: URL, header, timeout, and redaction policy

- Implement base URL validation, normalization, and endpoint resolution.
- Implement protected-header composition and injection rejection.
- Implement connect and request timeout configuration and canonical mapping.
- Add bounded diagnostic redaction and adversarial secret-leak fixtures.

### P3-C: SSE and response metadata

- Implement incremental SSE framing and parsing.
- Extract request-ID and retry-after metadata.
- Define and enforce the response-content-start boundary.
- Add chunk-boundary, line-ending, malformed-input, cancellation, and end-of-stream fixtures.

### P3-D: Provider registry

- Implement deterministic internal registration and lookup.
- Cover duplicate, unknown, normalized-identifier, concurrent-read, and lifecycle cases.
- Use fake internal descriptors only; do not add a real provider or provider DTO.
- Connect registry lookup to the primary client without exposing provider-specific host APIs.

### P3-E: Lifecycle integration and acceptance

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

For this plan-only change, run:

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

This plan-only change proves no runtime behavior and makes no P3 implementation claim.

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

P3 completion becomes authoritative only after the closing pull request passes the full exact-head
local gate, exact-head CI, independent review, guarded merge, and the resulting `main` workflow
inspection required by repository policy. Until then, the roadmap remains the authority for P3
status.
