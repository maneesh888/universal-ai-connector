# P5 Anthropic Messages Adapter

## Status and activation gate

P0-P4 are `Completed`. P5 remains `Not started`, no work package is active, and P6-P9 remain
`Not started`.

Creating and reviewing this plan does not activate P5 or authorize Anthropic protocol
implementation. P5 may begin only after a separate roadmap transition makes it the sole
`In progress` milestone and names P5-A as the active work package. Current official Anthropic
sources may be selected only after that activation.

## Objective

Add one internal Anthropic Messages adapter that translates between the accepted canonical
contracts and the activated provider protocol while preserving the existing Kotlin client,
Swift façade, provider-neutral configuration, transport lifecycle, cancellation, terminal
arbitration, secret-safety policy, and package boundaries.

The completed milestone must provide:

- internal Anthropic request, response, error, and streaming wire models;
- canonical request and supported structured-output translation;
- canonical response, output, usage, metadata, and error translation;
- incremental provider-stream translation to valid canonical stream sequences;
- conservative provider and model capability reporting;
- host-supplied, non-persistent credential delivery through the accepted provider-neutral
  configuration;
- deterministic Ktor `MockEngine` and local-fixture coverage;
- targeted live response, structured-output, streaming, error, and cancellation smoke proof; and
- exact-head local-live evidence accepted by the existing secretless `live-policy` readiness and
  merge gate.

## Design constraints

- Keep Anthropic protocol behavior and DTOs internal to Kotlin `commonMain`.
- Preserve one primary Kotlin client and one Swift façade. Do not add provider-specific Kotlin,
  Swift, Android, JVM, or iOS request methods, DTOs, screens, or lifecycle paths.
- Reuse the P3 transport, SSE, response-metadata, registry, ownership, cancellation, redaction,
  and exactly-once terminal boundaries instead of creating an Anthropic-specific transport.
- Reuse `UniversalAiProviderConfiguration`; do not introduce a public Anthropic credential or
  configuration type unless an activated protocol decision proves the provider-neutral boundary
  insufficient and records the compatibility cost first.
- Keep canonical contracts authoritative. Provider fields without governed canonical meaning
  remain internal or use only the accepted extension policy.
- Do not read credentials implicitly from process-global state inside the runtime library. A host
  supplies each credential through the existing synchronous supplier, and the library neither
  stores nor refreshes it.
- Never place credentials in canonical requests, extensions, URLs, exceptions, logs, fixtures,
  samples, build configuration, generated artifacts, command-line arguments, or committed
  environment files.
- Preserve the single ignored `.env.live` file for manual local live-test inputs. Use distinct
  provider-specific variables within that file; do not introduce one shared cross-provider API
  key variable or a second provider-specific secret file.
- Keep generation retry disabled by default. Never retry or reconnect after response content
  begins, and do not add provider-specific background work.
- Preserve caller cancellation as cancellation. Do not translate cancellation into a provider or
  canonical connector error.
- Treat unknown provider fields and stream events conservatively. Ignore only content proven
  optional by the activated protocol decision; never fabricate canonical success from an
  unrecognized terminal or incomplete response.
- Use the existing host targets, samples, packaging, ordinary CI lanes, and secretless GitHub
  policy. P5 adds no host target, sample application, provider-selection UI, or normal-CI
  credential.

## Scope

### In scope

- one internal registration for the Anthropic provider identifier;
- the Messages endpoint, authentication scheme, required versioning or feature-negotiation
  headers, and request options selected from official sources after P5 activation;
- translation of canonical target, text input, response format, generation parameters, and
  governed extensions that have an explicit supported mapping;
- translation of provider response content, supported structured output, usage, request metadata,
  incomplete results, and provider errors into accepted canonical contracts;
- translation of provider SSE records into ordered canonical stream events with one authoritative
  terminal;
- bounded provider payload parsing and fixed safe failures for malformed or unsupported content;
- conservative static or documented model capability declarations without unapproved discovery
  networking;
- deterministic authentication fixtures that verify credential resolution, required-header
  construction, protected-header precedence, pre-dispatch failure, and complete redaction;
- extension of the existing local live runner to `./scripts/check-live.sh anthropic`;
- provider-specific `ANTHROPIC_API_KEY` and `ANTHROPIC_LIVE_MODEL` process inputs documented in
  the existing value-free `.env.live.example`;
- a dedicated Anthropic Gradle live task excluded from deterministic tests, normal `check.sh`
  gates, samples, and ordinary CI; and
- the smallest targeted live smoke matrix needed for response, supported structured output,
  streaming, error, and cancellation behavior.

### Out of scope

- P5 activation as part of plan authoring;
- selecting or freezing the supported protocol subset before current official sources are
  reviewed after activation;
- OpenAI, OpenRouter, generic OpenAI-compatible, or Universal Gateway V2 behavior changes;
- provider SDK dependencies when the shared Ktor transport and internal wire models are
  sufficient;
- automatic model discovery, billing, quota management, credential creation, credential refresh,
  OAuth, end-user authentication, organization administration, or server-side secret brokering;
- application credential storage, Keychain, App Group storage, Android keystore integration,
  runtime environment-file loading, or provider-selection UI;
- a shared `API_KEY` variable reused across providers or separate committed/provider-specific
  secret files;
- provider DTOs, credential types, authorization headers, or transport types in supported host
  signatures or exported Apple headers;
- changes to canonical schemas solely to mirror provider fields;
- automatic retry, reconnect-after-content, or a general retry policy;
- physical-device or Android-emulator proof, performance/load claims, remote publication,
  distribution, OpenKeyboard, or Gateway V1 integration; and
- P6-P9 implementation or alpha-release readiness.

## P5-A readiness decisions

P5-A must close the following decisions after separate P5 activation and before provider behavior
is implemented.

### Authoritative provider protocol

- Record the retrieval date and current official sources for the selected Messages request,
  response, streaming, structured-output, authentication, versioning, error, and credential-safety
  behavior.
- Define the smallest protocol subset required by the accepted canonical contracts.
- Record which provider content blocks, roles, generation parameters, stop reasons, usage fields,
  metadata, errors, and stream events are required, optional, unsupported, or safely ignorable.
- Determine whether the provider's documented structured-output mechanism can satisfy the
  governed canonical schema and value rules without exposing provider tool or DTO concepts. If
  not, report the capability as unsupported rather than broadening the public contract.
- Keep committed fixtures synthetic and credential-free. Official examples may guide fixture
  shape but are not copied as an ungoverned compatibility corpus.

### Configuration and credentials

- Reuse the immutable provider-neutral configuration keyed by canonical provider identifier,
  validated base URL, and synchronous host-owned credential supplier.
- Invoke the supplier exactly once while constructing each network request. Do not invoke it
  during client construction, capability lookup, deterministic fake execution, or sample startup,
  and do not retain the returned value.
- Convert missing, blank, malformed, or throwing suppliers into one fixed safe authentication
  failure before transport dispatch. Preserve supplier cancellation as caller cancellation.
- Map provider authentication and permission rejection to existing canonical categories and codes
  with fixed safe messages and no provider body, credential, or header leakage.
- Compose the activated authentication, versioning, content, and accept headers through P3
  protected-header policy. Reject caller overrides and injection characters before dispatch.
- Keep the runtime library independent of process environment and application storage. Only the
  dedicated live-test process reads provider-specific environment values and passes the credential
  into the same host-owned supplier used by production code.

### One local environment file, provider-specific variables

- `.env.live` remains the only repository-defined local environment file for manual live-provider
  inputs. It remains Git-ignored, is never opened or sourced automatically, and should retain
  owner-only permissions.
- `.env.live.example` remains tracked and value-free. P5-A adds empty
  `ANTHROPIC_API_KEY` and `ANTHROPIC_LIVE_MODEL` entries alongside the existing OpenAI entries.
- Each provider owns distinct variables. `OPENAI_API_KEY` is never reused as an Anthropic
  credential, and no generic cross-provider `API_KEY` variable is introduced.
- A developer explicitly exports the chosen file into the local process before running a live
  gate. The runner validates only presence and safe shape and never prints the credential.
- The selected Anthropic credential must be dedicated, revocable, least-privilege, and
  conservatively quota-limited. The selected bounded-cost model remains a separate
  environment-owned input rather than credential material or committed account configuration.

### Live verification and merge protection

- Extend `check-live.sh` with an explicit `anthropic` provider route while retaining the existing
  `openai` route.
- Require a clean committed checkout, bind the run to exact `HEAD`, and reject a mismatched
  `UAC_LIVE_EXPECTED_SHA`.
- Run the deterministic Anthropic adapter prerequisites with every Anthropic live input removed
  from their process environment before starting the provider task.
- Pass `ANTHROPIC_API_KEY`, `ANTHROPIC_LIVE_MODEL`, and the exact-head binding only to the dedicated
  non-cacheable, no-daemon Anthropic live task. Do not pass the credential through Gradle
  properties, command-line arguments, reusable daemon state, configuration cache, or retained
  test output.
- Keep the live task excluded from ordinary `jvmTest`, `check.sh`, samples, and CI. Missing inputs,
  provider access, quota, selected-model access, task wiring, or assertions fail rather than skip.
- Extend runner, pre-push, secret-scan, and secretless-policy regressions so every documented
  Anthropic input is recognized without embedding or printing a real value.
- Continue using the existing credential-free `live-policy` Environment and required status.
  GitHub receives no provider credential and does not rerun provider tests.
- Record only bounded exact-head evidence: command, provider, selected model, UTC date, result,
  proof limits, and the existing statement `No credential or provider response body retained.`

## Adapter contract

### Request translation

- Select the internal adapter through the immutable per-client P3 registry and canonical provider
  identifier.
- Resolve only the activated relative Messages endpoint through P3 URL policy; adapter input
  cannot replace the configured authority or inject credentials into a URL.
- Compose authentication and provider-required headers through P3 protected-header policy.
- Map canonical text input in stable order and preserve only role behavior accepted during P5-A.
- Omit optional provider fields when canonical values are absent; do not invent provider defaults
  as canonical intent.
- Map supported generation parameters with explicit range and capability checks.
- Map plain-text and any accepted governed structured-output intent without exposing provider wire
  shapes.
- Reject unsupported canonical features before network dispatch with a fixed validation or
  capability error.

### Response and structured-output translation

- Parse provider payloads through bounded internal wire models.
- Preserve supported provider content order when constructing canonical outputs.
- Translate text and any accepted structured output without flattening malformed or unsupported
  content into plausible text.
- Validate structured output against the governed canonical schema and value rules at the accepted
  boundary.
- Map provider identifiers, model information, usage, request ID, and retry metadata only where
  the canonical contract defines their meaning.
- Treat an incomplete or malformed provider response as a fixed safe failure, not a successful
  empty response.
- Ignore unknown optional fields only where P5-A establishes that they cannot change the meaning
  of the supported subset.

### Streaming translation

- Use the P3 incremental SSE reader and preserve backpressure.
- Decode provider stream records incrementally and map the accepted sequence to canonical started,
  delta, output, usage, completed, and failed semantics as applicable.
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
  remains unknown unless P5 adds explicit, deterministic model metadata.

## Work packages

Execute these packages in order only after separate P5 activation, with one active package at a
time.

### P5-A: Protocol and live-test authentication readiness

Status: `Not started`.

- Bind the supported protocol subset to current official sources.
- Confirm reuse of the provider-neutral configuration and credential-supplier boundary.
- Extend the single value-free local environment-file convention with Anthropic-specific inputs.
- Extend live-runner routing, task isolation, secret scanning, and policy regressions without
  adding provider request or response behavior.
- Record the structured-output capability decision and activate P5-B only after these decisions
  and gates are accepted.

### P5-B: Non-streaming request and response translation

Status: `Not started`.

- Add internal provider registration and wire DTOs for the accepted non-streaming subset.
- Implement authentication-header construction, request translation, response translation, usage,
  and metadata.
- Add deterministic `MockEngine` request/response, malformed-payload, credential-resolution,
  protected-header, redaction, and cancellation fixtures.
- Add the dedicated Anthropic live task and run the affected response and pending-cancellation
  smoke tests before creating or updating the package pull request.

### P5-C: Structured output, errors, and capabilities

Status: `Not started`.

- Implement governed structured-output translation only if P5-A accepts a compatible provider
  mechanism; otherwise declare the capability unsupported.
- Complete canonical provider-error and incomplete-response mapping.
- Add conservative provider/model capability reporting.
- Add deterministic schema/value, error-envelope/status, unknown-field, metadata, authentication,
  permission, and secret-leak fixtures.
- Run the affected structured-output smoke when supported and one intentional safe provider-error
  smoke before creating or updating the package pull request.

### P5-D: Streaming translation and cancellation

Status: `Not started`.

- Implement provider SSE event translation through the P3 parser.
- Enforce ordering, backpressure, one authoritative terminal, and missing-terminal failure.
- Cover cancellation before headers, during content, between records, and after provider terminal.
- Add deterministic malformed, unknown, duplicate, late, truncated, and oversized stream
  fixtures.
- Run the affected streaming and active-cancellation smoke tests before creating or updating the
  package pull request.

### P5-E: Lifecycle integration and acceptance

Status: `Not started`.

- Reconcile configuration, credential, adapter, registry, transport, stream, error, and cleanup
  behavior across concurrent requests and close races.
- Compile affected existing consumers through supported package boundaries without adding
  credentials or live calls to deterministic samples.
- Complete provider-DTO, credential, artifact-boundary, log-redaction, and no-secret-normal-CI
  audits.
- Run the complete deterministic and live exact-head matrices and reconcile plan and roadmap
  evidence.
- Mark P5 complete only in a separate milestone-closing change after every acceptance criterion
  has durable evidence.

## Test matrix

### Deterministic request and authentication fixtures

- target provider/model mapping and rejection of a non-Anthropic target by this adapter;
- ordered canonical text input and every supported role;
- absent, default, zero, boundary, and unsupported generation parameters;
- plain-text and any accepted governed structured-output request bodies;
- governed extensions with supported, unknown, and rejected provider mappings;
- required authentication, versioning, content, and accept-header composition;
- case-insensitive protected-header override rejection, control characters, and URL safety;
- missing, blank, malformed, throwing, and cancelling credential suppliers;
- exactly one credential-supplier invocation per network request and zero calls during client
  construction, capability lookup, deterministic fake use, or sample startup; and
- proof that request bodies, URLs, diagnostics, exceptions, fixtures, and retained observations
  contain no credential value.

### Deterministic response and error fixtures

- text, multiple supported content items, stable ordering, any accepted structured output, usage,
  and provider metadata;
- empty, incomplete, unknown optional, unknown required, malformed, oversized, and truncated
  payloads;
- successful status with invalid content and error status with valid, malformed, HTML, or absent
  error bodies;
- authentication, permission, validation, not-found, rate-limit, timeout, server, unavailable,
  unknown-status, and transport failures;
- request-ID precedence plus valid, invalid, past, negative, and oversized retry-after values;
- fixed safe messages and bounded causes with adversarial credentials and provider fragments; and
- conservative capabilities for documented and unknown model behavior.

### Deterministic stream fixtures

- supported response lifecycle, text deltas, multiple content items, accepted structured output,
  usage, and successful terminal translation;
- provider-declared failure and incomplete terminal translation;
- one-byte and arbitrary chunks, LF/CRLF records, comments, optional fields, and split UTF-8;
- unknown optional events, unknown required events, invalid order, duplicate state, duplicate
  terminal, late content, and late failure;
- malformed JSON, oversized records, truncated records, and end-of-stream without terminal;
- cancellation before headers, before content, during a partial event, between events, after
  content, and during terminal arbitration;
- backpressure, concurrent streams, isolated cancellation, connector close races, and underlying
  body cleanup; and
- proof that no retry, reconnect, event, error, or terminal occurs after cancellation or
  authoritative completion.

### Secret-safety and live-gate fixtures

- `.env.live.example` contains only empty documented variables and `.env.live` remains ignored;
- the live runner never opens, reads, or sources `.env.live`;
- the single local file may supply both providers while the selected live route receives only its
  provider-specific key, model, and exact-head inputs;
- secret-scan regression recognizes every documented Anthropic input without printing its value;
- the deterministic prerequisite runs with Anthropic live inputs removed;
- missing credential, missing model input, malformed input, unavailable provider, rate limit,
  stale SHA, task misconfiguration, and failed assertions fail rather than skip;
- no Gradle property, command-line argument, reusable daemon, configuration cache, normal test,
  sample, ordinary CI job, or retained evidence receives the credential;
- command output and retained evidence contain only bounded result metadata, never authentication
  headers, full sensitive requests, or unredacted provider responses; and
- secretless pull-request policy requires exact-head local proof text and the existing successful
  automatic `live-policy` deployment without receiving provider credentials.

### Targeted live smoke matrix

- one minimal non-streaming text response;
- one minimal governed structured-output response when P5-A accepts support;
- one streaming response with observable ordered content and a valid terminal;
- one safe intentional provider error with canonical mapping and redacted diagnostics;
- cancellation of a pending response after credential resolution;
- cancellation of an active live stream after observable content, with no later consumer event;
  and
- exact commit SHA, provider target, selected test model, UTC execution date, command result, and
  proof limits recorded without secret or response-body retention.

### Host and package integration

- common JVM, Android host, and iOS Simulator tests execute deterministic adapter fixtures;
- JVM and Android consumers retain the same primary Kotlin client;
- Swift Package tests retain native models, errors, cancellation, and stream cleanup through the
  single façade;
- XCFramework headers contain no provider DTO, credential, transport, registry implementation,
  coroutine scope, or generated callback plumbing in the supported API;
- deterministic JVM, Android, and iOS samples remain zero-configuration, credential-free, and
  network-free; and
- ordinary Linux, Windows, and macOS CI remains read-only and secretless.

## Verification

During activated adapter implementation, use focused deterministic checks:

```bash
./gradlew :bridge:jvmTest
./gradlew :bridge:testAndroidHostTest
./gradlew :bridge:iosSimulatorArm64Test
```

Run affected consumer, Swift, framework, and sample checks only if an activated package changes a
supported construction or host boundary. Every committed package passes the quick gate, and P5
acceptance passes the full gate:

```bash
./scripts/check.sh --quick
./scripts/check.sh --full
```

Every exact head that adds or changes Anthropic live behavior must pass:

```bash
./scripts/check-live.sh anthropic
```

The live command must run deterministic affected tests first. Missing credentials, unavailable
provider service, rate limiting, stale-head evidence, task wiring failure, or a failed assertion
blocks pull-request creation or update; none is a skipped success.

P5 completion requires the complete local deterministic and live gates, exact-head Linux,
Windows, and macOS ordinary CI, the protected secretless local-evidence status, independent
exact-head review, guarded merge, and resulting `main` workflow inspection.

For documentation-only changes to this plan, run:

```bash
./scripts/check.sh --hygiene
```

Plan authoring does not exercise runtime, provider, authentication, or live behavior.

## Acceptance criteria

- P5 was separately activated after P4 completion and remained the only `In progress` milestone
  during implementation.
- The supported protocol subset and fixtures are traceable to dated official Anthropic sources
  accepted after activation.
- Hosts provide credentials through the accepted provider-neutral boundary without runtime-library
  storage, implicit process-global loading, or provider-specific request types.
- One ignored `.env.live` file holds manual local live inputs, while OpenAI and Anthropic use
  distinct provider-specific key and model variables.
- Deterministic tests use synthetic credentials and `MockEngine`; only the dedicated local live
  task receives a real credential from its process environment.
- The zero-configuration deterministic client and samples remain credential-free and network-free.
- Canonical text, generation, plain-text, and any accepted governed structured-output requests
  translate deterministically without exposing provider DTOs.
- Provider text, supported structured output, usage, metadata, incomplete results, and errors map
  to existing canonical contracts with stable ordering and fixed safe failures.
- Streaming translation is incremental, preserves backpressure, produces a valid canonical
  sequence, terminates exactly once, and rejects missing or invalid terminal state.
- Caller cancellation and connector close cancel pending responses and active streams, release the
  body, and suppress late provider or canonical events.
- Generation retry remains disabled by default and no retry or reconnect occurs after response
  content begins.
- Provider and model capabilities are conservative and do not claim behavior outside the
  implemented and verified subset.
- Credentials, authentication headers, sensitive provider payloads, and unredacted errors are
  absent from Git, artifacts, public APIs, fixtures, samples, normal CI, diagnostics, and retained
  evidence.
- `check-live.sh anthropic` fails closed, binds evidence to exact `HEAD`, and passes the affected
  local response, supported structured-output, streaming, error, and cancellation smoke matrix.
- The secretless local-live policy status passes for the same exact head as ordinary CI and
  independent review before readiness or merge.
- Existing JVM, Android, and iOS consumers compile through supported package boundaries without
  new provider-specific host controls or duplicated platform implementations.
- Deterministic fixtures, full local verification, exact-head ordinary CI, local live proof,
  secretless policy deployment, and independent review pass for the P5 closing head.

## Proof limits

Plan authoring proves only that P5 requirements, work-package order, authentication-test boundary,
verification expectations, and proof limits are recorded. It does not prove Anthropic protocol
compatibility, credential validity, authentication success, model access, adapter behavior, live
provider behavior, host integration, distribution, or release readiness.

After implementation, deterministic tests may prove bounded Anthropic translation,
authentication-header construction, parsing, error mapping, stream-event translation,
cancellation, cleanup, redaction, packaging, and existing-host consumption only against committed
synthetic fixtures and Ktor `MockEngine`.

The targeted live suite may additionally prove that the exact tested head completed the documented
smoke cases against the selected provider endpoint and model at the recorded time. It cannot prove:

- every Anthropic model, account tier, region, endpoint revision, beta or version header, or future
  protocol event;
- credential creation, rotation, revocation, organization policy, billing, or quota correctness;
- provider service availability, latency, throughput, load tolerance, or every network timing;
- unsupported tools, files, images, audio, prompt caching, batches, administration, or model
  discovery behavior;
- OpenAI, OpenRouter, generic OpenAI-compatible, Universal Gateway, or OpenKeyboard behavior;
- physical-device or Android-emulator execution;
- remote Maven or Swift Package distribution, signing, checksums, or released-artifact
  consumption; or
- alpha-release readiness for `0.1.0-alpha.1`.

## Completion evidence

For every activated P5 package, record:

- candidate branch and exact head SHA;
- active package and changed adapter, credential, live-gate, or shared surface;
- authoritative provider sources and retrieval dates used by the package;
- deterministic fixtures and focused tests added;
- authentication, translation, capability, error, streaming, cancellation, cleanup, and redaction
  paths exercised;
- local deterministic and affected live commands with results;
- protected exact-head local-evidence status and ordinary CI results where required;
- host consumers and package boundaries compiled;
- proof limits and unexercised provider behavior; and
- the next incomplete P5 package.

P5 completion becomes authoritative only after the closing pull request passes the full exact-head
local deterministic and live gates, exact-head ordinary CI, protected secretless evidence policy,
independent review, guarded merge, and resulting `main` workflow inspection required by repository
policy. Until then, the roadmap remains the authority for P5 status.
