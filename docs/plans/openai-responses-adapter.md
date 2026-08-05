# P4 OpenAI Responses Adapter

## Status and activation gate

P0-P3 are `Completed`. P4 was activated on August 2, 2026 as the only `In progress`
milestone, and P5-P9 remain `Not started`.

P4-A completed the protocol, configuration, and live-safety readiness foundation without adding
provider request or response behavior. P4-B completed the provider-neutral configuration boundary
and non-streaming request and response translation. P4-C is the sole active work package. It adds
governed structured output, bounded canonical error and incomplete-response mapping, and
conservative capability reporting without changing the accepted host surface.

## Objective

Add one internal OpenAI Responses adapter that translates between the accepted canonical
contracts and the provider protocol while preserving the existing Kotlin client, Swift façade,
transport lifecycle, cancellation, terminal arbitration, and package boundaries.

The completed milestone must provide:

- internal OpenAI request, response, error, and streaming wire models;
- canonical request and structured-output translation;
- canonical response, output, usage, metadata, and error translation;
- incremental provider-stream translation to valid canonical stream sequences;
- conservative provider and model capability reporting;
- host-supplied, non-persistent credential delivery with complete diagnostic redaction;
- deterministic Ktor `MockEngine` and local-fixture coverage;
- targeted live response, structured-output, streaming, error, and cancellation smoke proof; and
- a protected secretless exact-head local-evidence status that gates readiness and merge for
  affected live behavior.

## Design constraints

- Keep provider protocol behavior and DTOs internal to Kotlin `commonMain`.
- Preserve one primary Kotlin client and one Swift façade. Do not add per-provider Kotlin, Swift,
  Android, JVM, or iOS request methods, DTOs, screens, or lifecycle paths.
- Reuse the P3 transport, SSE, response-metadata, registry, ownership, cancellation, redaction, and
  exactly-once terminal boundaries instead of creating an OpenAI-specific transport.
- Keep the canonical contracts authoritative. Provider wire fields that have no governed
  canonical meaning remain internal or are retained only through the accepted extension policy.
- Do not read credentials implicitly from process-global state inside the runtime library. A host
  supplies credentials through the configuration boundary accepted in P4-A; the library neither
  stores nor refreshes them.
- Never place credentials in canonical requests, extensions, URLs, exceptions, logs, fixtures,
  samples, build configuration, generated artifacts, or committed environment files.
- Keep generation retry disabled by default. Never retry or reconnect after response content
  begins, and do not add provider-specific background work.
- Preserve caller cancellation as cancellation. Do not translate cancellation into a provider or
  canonical connector error.
- Treat unknown provider fields and stream events conservatively. Ignore only content proven
  optional by the activated protocol decision; never fabricate canonical success from an
  unrecognized terminal or incomplete response.
- Use the existing host targets, samples, packaging, and ordinary CI lanes. P4 adds no host target,
  sample application, provider-selection UI, or normal-CI credential.
- If usable credential or adapter configuration requires a supported host API change, P4-A must
  record the provider-neutral contract and compatibility cost before implementation. Do not
  smuggle provider configuration through test-only constructors or provider-specific public
  types.

## Scope

### In scope

- one internal registration for the OpenAI provider identifier;
- the Responses endpoint, authentication scheme, required headers, and request options selected
  from the official protocol sources accepted in P4-A;
- translation of canonical target, text input, response format, generation parameters, and
  governed extensions that have an explicit supported mapping;
- translation of provider response items, structured output, usage, request metadata, incomplete
  results, and provider errors into accepted canonical contracts;
- translation of provider SSE records into ordered canonical stream events with one authoritative
  terminal;
- bounded provider payload parsing and fixed safe failures for malformed or unsupported content;
- conservative static or documented model capability declarations without unapproved discovery
  networking;
- value-free credential setup documentation, ignored local-secret conventions, rotation and
  revocation guidance, and redaction regressions;
- a separate `./scripts/check-live.sh` entry point that runs deterministic affected tests first and
  fails closed when required live inputs or assertions are unavailable;
- a secretless exact-head evidence workflow with an automatic required `live-policy` deployment
  and a stable merge-required status before adapter behavior can leave draft; and
- deterministic fixtures plus the smallest targeted live smoke matrix needed for response,
  structured output, streaming, error, and cancellation behavior.

### Out of scope

- Anthropic, OpenRouter, generic OpenAI-compatible, or Universal Gateway V2 adapters;
- Chat Completions or another OpenAI protocol unless a separately approved compatibility
  requirement changes the milestone;
- provider SDK dependencies when the shared Ktor transport and internal wire models are
  sufficient;
- file upload, image, audio, realtime, batch, background, tool execution, computer use, hosted
  tools, conversation storage, or retrieval behavior;
- automatic model discovery, billing, quota management, credential creation, credential refresh,
  organization administration, or server-side secret brokering;
- application credential storage, Keychain, App Group storage, Android keystore integration,
  environment-file loading in the runtime library, or provider-selection UI;
- provider DTOs, credential types, authorization headers, or transport types in supported host
  signatures or exported Apple headers;
- changes to canonical schemas solely to mirror provider fields;
- automatic retry, reconnect-after-content, or a general retry policy;
- physical-device or Android-emulator proof, performance/load claims, remote publication,
  distribution, OpenKeyboard, or Gateway V1 integration; and
- P5-P9 implementation or alpha-release readiness.

## P4-A readiness decisions

P4-A closed the following decisions before provider behavior was activated.

### Authoritative provider protocol

The following official OpenAI sources were consulted on August 2, 2026 and govern the P4 subset:

- [Text generation and Responses request/response guidance](https://developers.openai.com/api/docs/guides/text)
- [Streaming Responses and semantic event guidance](https://developers.openai.com/api/docs/guides/streaming-responses)
- [Structured Outputs with `text.format`](https://developers.openai.com/api/docs/guides/structured-outputs)
- [API error and status guidance](https://developers.openai.com/api/docs/guides/error-codes)
- [Production API-key safety guidance](https://developers.openai.com/api/docs/guides/production-best-practices)

P4 implements only the protocol subset required by the accepted canonical contracts. The
out-of-scope provider features listed above fail through existing validation or capability
boundaries rather than acquiring provider-specific public APIs. Committed fixtures are synthetic
and credential-free; official examples may guide their shape but are not copied as an ungoverned
compatibility corpus.

### Configuration and credentials

- P4-B adds one provider-neutral, immutable per-client configuration keyed by canonical provider
  identifier. It carries a validated provider base URL and a synchronous host-owned credential
  supplier; no provider DTO or credential value enters a canonical request.
- The supplier is invoked once when constructing each network request. It is not invoked during
  client construction, capability lookup, deterministic fake execution, or sample startup, and
  its return value is not retained in client, request, canonical, diagnostic, or evidence state.
- Missing or blank credentials and supplier failures become fixed safe authentication failures
  before transport dispatch. Provider rejection remains a bounded canonical provider error.
  Secret values never appear in the resulting message or cause chain.
- The runtime library never reads process-global environment or application storage. Only the
  separate live-test runner reads the documented process environment and passes a credential into
  the same host-owned boundary exercised by production code.
- The existing zero-configuration deterministic client and samples remain unchanged. Adding this
  provider-neutral construction option is an additive compatibility cost needed by every live
  adapter. P4-B must update the Kotlin client and Swift-native façade together and compile all
  documented first-use paths without exposing Kotlin callback plumbing in Swift.

### Live verification and merge protection

- `.env.live.example` documents the value-free `OPENAI_API_KEY` and `OPENAI_LIVE_MODEL` inputs;
  `.env.live` and its local variants are ignored. The credential belongs to a dedicated,
  least-privilege, revocable test project and the selected bounded-cost model remains an
  environment-owned input rather than committed account configuration.
- `./scripts/check-live.sh openai` requires a clean exact commit, optionally verifies
  `UAC_LIVE_EXPECTED_SHA`, removes every live input from the deterministic JVM test, then invokes
  the dedicated OpenAI live Gradle task. A missing credential, model, task, provider result, or
  assertion fails rather than skips.
- P4-B supplies one minimal non-streaming response smoke and one pending-response cancellation
  smoke. The intentional error and active-stream cases remain P4-C and P4-D work respectively.
- `.github/workflows/live.yml` classifies the exact pull-request head with read-only repository
  permission and validates bounded local-live evidence in the pull-request body. It does not use
  `pull_request_target`, execute provider tests, receive provider credentials, or make ordinary CI
  depend on credentials.
- Once this P4-A foundation reaches the default branch, the trusted classifier conservatively
  requires protected live verification for every bridge source, bridge build, Swift package,
  repository build-infrastructure, or live-gate change. It never infers adapter activation from an
  internal package path or sentinel file. The one-time bootstrap rejects bridge source, Swift
  package, and build behavior while the trusted classifier is not yet present.
- The stable status job targets the credential-free `live-policy` Environment. An active branch
  ruleset requires a successful deployment to that Environment before merge. The deployment has
  no required reviewer and completes automatically after the secretless evidence assertions pass.
  Affected PR text must contain `Local live verification: passed`, the exact head SHA, and
  `No credential or provider response body retained.` The protected
  `live-provider` Environment retains reviewer protection but is not requested by this local-only
  policy.
- `Required live verification` becomes a required branch-protection status immediately after this
  foundation reaches `main`, before an adapter-behavior pull request is opened or leaves draft.

## Adapter contract

### Request translation

- Select the internal adapter through the immutable per-client P3 registry and canonical provider
  identifier.
- Resolve only the relative Responses endpoint through P3 URL policy; adapter input cannot replace
  the configured authority or inject credentials into a URL.
- Compose authentication and provider-required headers through P3 protected-header policy.
- Map canonical text input in stable order and preserve supported role semantics.
- Omit optional provider fields when the corresponding canonical value is absent; do not invent
  provider defaults as canonical intent.
- Map supported generation parameters with explicit range and capability checks.
- Map plain-text and governed structured-output intent without exposing the provider schema shape.
- Reject unsupported canonical features before network dispatch with a fixed validation or
  capability error.

### Response and structured-output translation

- Parse provider payloads through bounded internal wire models.
- Preserve provider output ordering when constructing canonical outputs.
- Translate supported text and structured output without flattening malformed or unsupported
  items into plausible text.
- Validate structured output against the governed canonical schema and value rules at the accepted
  boundary.
- Map provider identifiers, model information, usage, request ID, and retry metadata only where
  the canonical contract defines their meaning.
- Treat an incomplete or malformed provider response as a fixed safe failure, not a successful
  empty response.
- Ignore unknown optional fields only where the activated protocol decision establishes that they
  do not change the meaning of the supported subset.

### Streaming translation

- Use the P3 incremental SSE reader and preserve backpressure.
- Decode provider stream records incrementally and map the supported sequence to canonical
  started, delta, output, usage, completed, and failed semantics as applicable.
- Require a valid provider terminal that can produce one valid canonical terminal.
- Let the first valid terminal remain authoritative; suppress duplicate terminals, late records,
  and late failures through the accepted P3 boundary.
- Convert malformed JSON, invalid ordering, contradictory item state, unsupported required events,
  and end-of-stream without a terminal into fixed safe failures.
- Propagate caller or connector cancellation to the response body immediately and emit no later
  canonical event or terminal.
- Never reconnect or replay a stream after content begins.

### Errors, metadata, and capabilities

- Map provider authentication, permission, validation, not-found, rate-limit, timeout, server, and
  unavailable failures only to existing canonical categories and codes with fixed safe messages.
- Preserve P3 caller-cancellation and transport-failure behavior when no provider response exists.
- Parse provider error bodies defensively; malformed, oversized, HTML, or unknown payloads must not
  appear verbatim in a supported error.
- Apply P3 request-ID precedence and bounded retry-after parsing without treating either field as
  authenticated truth.
- Redact authorization, API-key, cookies, proxy credentials, provider payload fragments selected
  as sensitive, and adversarial secret values from diagnostics and failure chains.
- Report capabilities conservatively from documented adapter support. Unknown model behavior
  remains unknown unless P4 adds explicit, deterministic model metadata.

## Work packages

Execute these packages in order after separate P4 activation, with only one active package at a
time.

### P4-A: Protocol, configuration, and live-safety readiness

Status: `Completed` on August 2, 2026.

- Bind the supported protocol subset to current official sources.
- Resolve the provider-neutral configuration and credential-supplier boundary.
- Add the value-free local setup and ignored-secret policy.
- Add fail-closed live-gate infrastructure, protected-workflow safety checks, and regression tests.
- Configure the stable protected live status before adapter behavior can leave draft.
- Add no provider request/response implementation.

Completion record:

- added the value-free local input convention, exact-head fail-closed runner, deterministic
  runner and impact-classifier regressions, and protected live workflow;
- created the protected `live-provider` GitHub Environment without storing a repository
  credential or model value;
- created the credential-free `live-policy` Environment and required-deployment branch rule that
  server-enforces completion of the stable policy result;
- activated P4-B only after recording the provider-neutral credential/configuration decision; and
- limited proof to secret-safety and gate behavior because the dedicated live Gradle task and all
  provider protocol behavior begin in P4-B.

P4-B operational refinement:

- the real `:bridge:openAiLiveTest` task replaces the P4-A missing-task bootstrap and remains
  excluded from ordinary deterministic tasks;
- the pre-push hook applies `scripts/live-impact.sh` against `origin/main` and invokes the local
  exact-head live gate only for affected branches;
- missing local inputs fail with value-free `.env.live` setup guidance rather than skipping; and
- GitHub verification remains secretless and uses the automatic `live-policy` deployment to
  validate retained exact-head evidence instead of rerunning provider tests.

### P4-B: Non-streaming request and response translation

Status: `Completed` on August 5, 2026.

- Add internal provider registration and wire DTOs for the accepted non-streaming subset.
- Implement authentication, request translation, response translation, usage, and metadata.
- Add deterministic `MockEngine` request/response, malformed-payload, redaction, and cancellation
  fixtures.
- Run the affected local live response and cancellation smoke tests before creating or updating
  the package pull request.

Completion record:

- added immutable provider-neutral configuration, host-owned credential supply, internal OpenAI
  registration and wire DTOs, non-streaming request/response translation, usage and metadata
  mapping, cancellation, bounded parsing, fixed safe failures, and Kotlin/Swift host parity;
- added deterministic `MockEngine`, configuration, live-runner, hook, secretless-policy, and
  package-boundary coverage while keeping credentials and provider payloads out of retained
  evidence and normal CI;
- exact head `ea9bdc9cb3515affcbf52e28703261e46dac90a3` passed the mandatory quick hook,
  focused adapter and Swift checks, and `./scripts/check-live.sh openai` response and pending
  cancellation smoke cases on August 5, 2026;
- pull request [#32](https://github.com/maneesh888/universal-ai-connector/pull/32) passed exact-head
  ordinary CI and the required secretless live-evidence status, merged as
  `074ec3cca2e045793d22c1189280ff088f5c9353`, and resulting `main` run
  [31015641479](https://github.com/maneesh888/universal-ai-connector/actions/runs/31015641479)
  passed; and
- proof remains limited to the accepted non-streaming request, response, usage, metadata, safe
  failure, pending cancellation, and host-configuration paths. P4-C structured output, complete
  error/incomplete mapping, and capabilities; P4-D streaming; and P4-E acceptance remain
  unproven.

### P4-C: Structured output, errors, and capabilities

Status: `In progress`.

- Implement governed structured-output request and response translation.
- Complete canonical provider-error and incomplete-response mapping.
- Add conservative provider/model capability reporting.
- Add deterministic schema/value, error-envelope/status, unknown-field, metadata, and secret-leak
  fixtures.
- Run the affected local live structured-output and intentional-error smoke tests before creating
  or updating the package pull request.

### P4-D: Streaming translation and cancellation

Status: `Not started`.

- Implement provider SSE event translation through the P3 parser.
- Enforce ordering, backpressure, one authoritative terminal, and missing-terminal failure.
- Cover cancellation before headers, during content, between records, and after provider terminal.
- Add deterministic malformed, unknown, duplicate, late, truncated, and oversized stream
  fixtures.
- Run the affected local live streaming and active-cancellation smoke tests before creating or
  updating the package pull request.

### P4-E: Lifecycle integration and acceptance

Status: `Not started`.

- Reconcile configuration, credential, adapter, registry, transport, stream, error, and cleanup
  behavior across concurrent requests and close races.
- Compile all affected existing consumers through supported package boundaries without adding
  credentials or live calls to deterministic samples.
- Complete provider-DTO, credential, artifact-boundary, log-redaction, and no-secret-normal-CI
  audits.
- Run the complete deterministic and live exact-head matrices and reconcile plan and roadmap
  evidence.
- Mark P4 complete only in a separate milestone-closing change after every acceptance criterion
  has durable evidence.

## Test matrix

### Deterministic request fixtures

- target provider/model mapping and rejection of a non-OpenAI target by this adapter;
- ordered canonical text input and every supported role;
- absent, default, zero, boundary, and unsupported generation parameters;
- plain-text and governed structured-output request bodies;
- governed extensions with supported, unknown, and rejected provider mappings;
- required header composition, caller override rejection, control characters, and URL safety;
- missing, blank, rejected, and throwing credential suppliers; and
- proof that request bodies, URLs, diagnostics, and exceptions contain no credential value.

### Deterministic response and error fixtures

- text, multiple outputs, stable ordering, structured output, usage, and provider metadata;
- empty, incomplete, unknown optional, unknown required, malformed, oversized, and truncated
  payloads;
- successful status with invalid content and error status with valid, malformed, HTML, or absent
  error bodies;
- authentication, permission, validation, not-found, rate-limit, timeout, server, unavailable,
  unknown-status, and transport failures;
- request-ID precedence plus valid, invalid, past, negative, and oversized retry-after values;
- fixed safe messages and bounded causes with adversarial credentials and payload fragments; and
- conservative capabilities for documented, unknown, and overridden models.

### Deterministic stream fixtures

- supported response lifecycle, text deltas, multiple output items, structured output, usage, and
  successful terminal translation;
- provider-declared failure and incomplete terminal translation;
- one-byte and arbitrary chunks, LF/CRLF records, comments, optional fields, and split UTF-8;
- unknown optional events, unknown required events, invalid order, duplicate state, duplicate
  terminal, late content, and late failure;
- malformed JSON, oversized records, truncated records, and end-of-stream without terminal;
- cancellation before headers, before content, during a partial event, between events, after
  content, and during terminal arbitration;
- backpressure, concurrent streams, isolated cancellation, connector close races, and underlying
  body cleanup; and
- proof that no retry, reconnect, event, error, or terminal occurs after content cancellation or
  authoritative completion.

### Secret-safety and live-gate fixtures

- secret-scan regression recognizes every documented credential input without printing its value;
- local secret files remain ignored and only a value-free example may be committed;
- `check-live.sh` runs affected deterministic tests first and stops before network dispatch when
  they fail;
- missing credential, missing model input, unavailable provider, rate limit, stale SHA, and failed
  assertion are failures rather than skipped successes;
- command output and retained evidence contain only bounded result metadata, never authorization
  headers, full sensitive requests, or unredacted provider responses;
- secretless pull-request policy requires exact-head local proof text and a successful automatic
  `live-policy` Environment deployment without receiving provider credentials; and
- documentation-only or deterministically unrelated changes can satisfy the stable required
  status without obtaining provider credentials while affected live behavior cannot.

### Targeted live smoke matrix

- one minimal non-streaming text response;
- one minimal governed structured-output response;
- one streaming response with observable ordered content and a valid terminal;
- one safe intentional provider error with canonical mapping and redacted diagnostics;
- cancellation of an active live stream after observable content, with no later consumer event;
  and
- exact commit SHA, provider target, selected test model, UTC execution date, command result, and
  proof limits recorded without secret or response-body retention.

### Host and package integration

- common JVM, Android host, and iOS Simulator tests execute the deterministic adapter fixtures;
- JVM and Android consumers retain the same primary Kotlin client;
- Swift Package tests retain native models, errors, cancellation, and stream cleanup through the
  single façade;
- XCFramework headers contain no provider DTO, credential, Ktor, registry implementation,
  coroutine scope, or generated callback plumbing in the supported API;
- deterministic JVM, Android, and iOS samples remain zero-configuration, credential-free, and
  network-free; and
- ordinary Linux, Windows, and macOS CI remains read-only and secretless.

## Verification

During adapter implementation, use focused deterministic checks:

```bash
./gradlew :bridge:jvmTest
./gradlew :bridge:testAndroidHostTest
./gradlew :bridge:iosSimulatorArm64Test
```

Run affected consumer, Swift, framework, and sample checks when P4-A approves a construction or
supported-host boundary change. Every committed package passes the quick gate, and P4 acceptance
passes the full gate:

```bash
./scripts/check.sh --quick
./scripts/check.sh --full
```

Every exact head that adds or changes live adapter behavior must pass the affected local live suite
before its initial pull request is created and before every later push:

```bash
./scripts/check-live.sh openai
```

The live command must run deterministic affected tests first. Missing credentials, unavailable
provider service, rate limiting, stale-head evidence, or a failed assertion blocks pull-request
creation or update; none is a skipped success.

P4 completion requires the complete local deterministic and live gates, exact-head Linux, Windows,
and macOS ordinary CI, the protected secretless local-evidence status, independent exact-head
review, guarded merge, and resulting `main` workflow inspection.

For documentation-only changes to this plan, run:

```bash
./scripts/check.sh --hygiene
```

Plan authoring does not exercise runtime or provider behavior.

## Acceptance criteria

- P4 was separately activated after P3 completion and remained the only `In progress` milestone
  during implementation.
- The supported protocol subset and fixtures are traceable to dated official OpenAI sources
  accepted after activation.
- Hosts provide credentials through the accepted provider-neutral boundary without runtime-library
  storage, implicit process-global loading, or provider-specific request types.
- The zero-configuration deterministic client and samples remain credential-free and network-free.
- Canonical text, generation, plain-text, and governed structured-output requests translate
  deterministically without exposing provider DTOs.
- Provider text, structured output, usage, metadata, incomplete results, and errors map to existing
  canonical contracts with stable ordering and fixed safe failures.
- Streaming translation is incremental, preserves backpressure, produces a valid canonical
  sequence, terminates exactly once, and rejects missing or invalid terminal state.
- Caller cancellation and connector close cancel pending responses and active streams, release the
  body, and suppress late provider or canonical events.
- Generation retry remains disabled by default and no retry or reconnect occurs after response
  content begins.
- Provider and model capabilities are conservative and do not claim behavior outside the
  implemented and verified subset.
- Credentials, authorization headers, sensitive provider payloads, and unredacted errors are absent
  from Git, artifacts, public APIs, fixtures, samples, normal CI, diagnostics, and retained
  evidence.
- `check-live.sh` fails closed, binds evidence to the exact head, and passes the affected local
  response, structured-output, streaming, error, and cancellation smoke matrix.
- The secretless local-live policy status passes for the same exact head as ordinary CI and
  independent review before readiness or merge.
- Existing JVM, Android, and iOS consumers compile through supported package boundaries without new
  provider-specific host controls or duplicated platform implementations.
- Deterministic fixtures, full local verification, exact-head ordinary CI, local live proof,
  secretless policy deployment, and independent review pass for the P4 closing head.

## Proof limits

P4 can prove deterministic OpenAI Responses translation, bounded provider parsing, authentication
header construction, structured-output mapping, capability declarations, error mapping,
stream-event translation, cancellation, cleanup, redaction, packaging, and existing-host
consumption against committed fixtures and Ktor `MockEngine`.

The targeted live suite can additionally prove that the exact tested head completed the documented
response, structured-output, streaming, intentional-error, and active-cancellation smoke cases
against the selected provider endpoint and model at the recorded time.

P4 does not prove:

- every OpenAI model, account tier, organization, region, endpoint revision, or future protocol
  event;
- Chat Completions or another provider API outside the accepted Responses subset;
- tool execution, hosted tools, file, image, audio, realtime, batch, background, conversation
  storage, or retrieval behavior;
- provider service availability, latency, throughput, load tolerance, billing accuracy, quota
  policy, or credential rotation performed by the provider;
- every DNS, TLS, proxy, HTTP/2, network-loss, rate-limit, or cancellation timing;
- Anthropic, OpenRouter, generic OpenAI-compatible, Universal Gateway, or OpenKeyboard behavior;
- physical-device or Android-emulator execution;
- remote Maven or Swift Package distribution, signing, checksums, or released-artifact
  consumption; or
- alpha-release readiness for `0.1.0-alpha.1`.

## Completion evidence

For every activated P4 package, record:

- candidate branch and exact head SHA;
- active package and changed adapter, configuration, credential, or live-gate surfaces;
- authoritative provider sources and retrieval dates used by the package;
- deterministic fixtures and focused tests added;
- translation, capability, error, streaming, cancellation, cleanup, and redaction paths exercised;
- local deterministic and affected live commands with results;
- protected exact-head local-evidence status and ordinary CI results where required;
- host consumers and package boundaries compiled;
- proof limits and unexercised provider behavior; and
- the next incomplete P4 package.

P4 completion becomes authoritative only after the closing pull request passes the full exact-head
local deterministic and live gates, exact-head ordinary CI, protected secretless evidence policy,
independent review, guarded merge, and resulting `main` workflow inspection required by
repository policy. Until then, the roadmap remains the authority for P4 status.
