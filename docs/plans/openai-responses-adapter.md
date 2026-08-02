# P4 OpenAI Responses Adapter

## Status and activation gate

P0 through P3 are `Completed`. P4 was activated on August 2, 2026 as the only milestone marked
`In progress`. P4-A completed its activation, secret-safety, local runner, live-impact, and
protected-workflow foundation on August 2, 2026. P4-B is now the sole active work package.
P5-P9 remain `Not started`.

The accepted activation transition:

1. keeps P0-P3 `Completed`;
2. marks P4 as the only `In progress` milestone;
3. names P4-A as the only active work package; and
4. does not add OpenAI adapter behavior until P4-A is accepted.

P4-A establishes the secret-safety and live-verification foundation required before the first
OpenAI adapter pull request. It does not make a live request, add provider DTOs, register OpenAI,
or claim provider compatibility.

## Objective

Implement an internal OpenAI Responses adapter that translates the existing provider-neutral
canonical contracts through the P3 transport and registry foundations. The completed milestone
must provide:

- host-supplied OpenAI configuration and credentials without provider DTOs in supported APIs;
- request translation to `POST /v1/responses`;
- unary response and usage translation;
- incremental Responses streaming translation into canonical stream events;
- governed JSON Schema output through Responses `text.format`;
- conservative capability reporting and preflight rejection;
- stable canonical mapping for HTTP, provider, protocol, and transport failures;
- deterministic `MockEngine` and fixture coverage; and
- exact-head local and protected live response, streaming, error, and cancellation smoke proof.

## Official OpenAI requirement sources

Use current official documentation when implementing each package. The activation baseline is:

- [Text generation](https://developers.openai.com/api/docs/guides/text)
- [Streaming API responses](https://developers.openai.com/api/docs/guides/streaming-responses)
- [Structured model outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
- [Responses API reference](https://developers.openai.com/api/docs/api-reference/responses)
- [Responses streaming reference](https://developers.openai.com/api/docs/api-reference/responses-streaming)
- [Error codes](https://developers.openai.com/api/docs/guides/error-codes)
- [Production best practices](https://developers.openai.com/api/docs/guides/production-best-practices)

The implementation pull request for each package must record the documentation date and any
request-only delta from this plan. Model identifiers remain host configuration; the connector
must not silently migrate a caller's selected model.

## Design constraints

- Keep OpenAI wire DTOs, translation, and stream state in Kotlin `commonMain`.
- Reuse the P3 `ConnectorTransport`, SSE reader, response metadata, registry, ownership, and
  cancellation boundaries.
- Preserve one primary Kotlin client and one Swift façade. Do not add per-provider client methods,
  platform implementations, samples, or credential stores.
- Keep API keys out of request models, extensions, logs, errors, artifacts, normal CI, and sample
  configuration.
- Credentials are host-supplied through a bounded provider configuration path and are copied only
  into the protected transport-owned `Authorization` header.
- Keep the default deterministic provider available without configuration, credentials, or
  networking.
- Provider DTOs remain internal and never appear in public Kotlin, generated Objective-C, Swift,
  JVM, or Android signatures.
- Reject unsupported canonical intent before networking. Do not silently discard inputs,
  generation parameters, response-format requirements, or recognized OpenAI extension members.
- Preserve caller cancellation as cancellation. Do not map it to an OpenAI, timeout, or provider
  error.
- Generation retries remain disabled. Never reconnect or retry after response content begins.
- Keep live proof separate from deterministic behavior, packaging, consumer, and distribution
  proof.

## Scope

### In scope

- one internal OpenAI provider identifier and adapter registration path;
- host-supplied base URL, API-key provider, and selected model identifier;
- Bearer authentication and JSON request/response content negotiation through protected headers;
- canonical text inputs supported by the current contract;
- canonical generation parameters that Responses supports without semantic loss;
- plain-text and governed JSON Schema response formats;
- response IDs, request IDs, text or structured outputs, token usage, and governed metadata;
- Responses SSE event translation with bounded state and exactly-once canonical terminal behavior;
- provider refusal, incomplete response, malformed payload, HTTP status, request-ID, retry-after,
  and error-object mapping;
- conservative streaming and structured-output capability declarations;
- typed OpenAI extension helpers only for approved, documented intent not represented canonically;
- deterministic fixtures, adversarial redaction tests, and live smoke tests;
- local `check-live.sh` and a protected GitHub `live-provider` Environment; and
- durable exact-head live evidence in affected pull-request briefs.

### Out of scope

- Chat Completions, Assistants, Realtime, Batch, uploads, files, conversations, or webhooks;
- hosted tools, function calling, local tool execution, MCP, computer use, or agent frameworks;
- image, audio, video, or other multimodal input or output;
- background responses, polling, response persistence, or `previous_response_id` state;
- automatic model discovery, model aliases owned by the connector, or capability probing;
- generation retry, automatic rate-limit retry, or reconnect-after-content;
- OpenAI organization administration, billing, quotas, project creation, or key provisioning;
- mobile or desktop credential storage, provider-selection UI, OpenKeyboard, or Gateway contracts;
- public provider DTOs or per-provider Swift, Android, JVM, or sample implementations;
- new host targets, publication, signing, released-artifact consumers, or distribution claims; and
- P5-P9 behavior.

## Configuration and credential contract

- The provider ID is the canonical lowercase value `openai`.
- The default API base URL is `https://api.openai.com/v1/`; a custom base URL is an advanced
  configuration and remains subject to the P3 URL policy.
- The host supplies an API-key callback or immutable credential value through provider
  configuration. Ordinary request objects never carry credentials.
- The adapter creates exactly one `Authorization: Bearer …` field through the transport-owned
  protected-header path. Callers and request extensions cannot override it.
- The host selects the OpenAI model using the canonical target. Tests and docs must not imply that
  a mutable model alias is a stable package default.
- Optional OpenAI organization or project routing is deferred unless a package proves a current
  official requirement and adds bounded, redacted configuration without changing ordinary hosts.
- Credential callbacks must not run during construction, capability inspection, or deterministic
  no-network use. Each network operation resolves its credential only for that operation.
- Empty, malformed, or unavailable credentials fail before transport execution with a fixed safe
  message that contains no credential material.

## Translation contract

### Requests

- Use `POST` with the adapter-relative `responses` endpoint.
- Map the canonical model identifier to `model`.
- Map supported canonical text input items to Responses `input` without provider DTO exposure.
- Map high-level instructions only from canonical intent; do not reinterpret user input as a
  higher-authority role.
- Map governed JSON Schema response intent through `text.format` with `type: "json_schema"`,
  a deterministic bounded name, the canonical schema, and strict mode when the official API and
  governed subset support it.
- Reject unknown response-format kinds and unsupported schema features before networking.
- Map only generation parameters whose OpenAI meaning is compatible. Unsupported or conflicting
  parameters fail preflight rather than being ignored.
- Set `stream` only from the selected connector operation.
- Keep provider-extension mapping namespaced, typed, bounded, conflict-checked, and subordinate to
  canonical fields.

### Unary responses

- Accept only a successful completed Responses object for unary completion.
- Map the provider response ID to the canonical response ID and the P3 response-header request ID
  to canonical request metadata.
- Preserve output order. Map supported message text to canonical text output and structured JSON
  to canonical structured output after strict parse and governed-value validation.
- Treat refusal, incomplete response, unsupported output kinds, missing required output, malformed
  usage, or contradictory fields as stable provider or protocol failure according to the accepted
  error matrix.
- Map input, cached-input, output, and reasoning token counts only where canonical aggregates and
  detail invariants remain true. Unsupported provider-only counters remain in governed extensions
  or are omitted; they never corrupt canonical totals.

### Streaming

- Parse the P3 SSE records as typed internal Responses events using the event `type` discriminator.
- Translate the minimum lifecycle needed by the canonical stream contract: response start, output
  start, text delta, output completion, usage update, and response completion.
- Validate response, output, content, and sequence correlation before emitting canonical events.
- Emit nonempty ordered deltas with backpressure; never coalesce across output boundaries.
- Treat `response.completed` as success only after all required output state can produce one valid
  canonical response. Emit exactly one canonical terminal event.
- Treat provider `error`, `response.failed`, incomplete completion, malformed SSE JSON,
  unsupported required output, premature EOF, and invalid correlation as out-of-band failure.
- Cancellation stops body reads and suppresses late events, late errors, and duplicate terminal
  delivery.

## Error and redaction contract

- Map HTTP 400-class validation failures, authentication, authorization, not-found, and rate-limit
  responses to their matching canonical categories. Preserve the OpenAI error code only as a
  governed provider code when it satisfies canonical bounds.
- Map HTTP 500-class responses to a safe provider failure, and preserve P3 transport timeout and
  transport-failure mappings.
- Retain bounded request ID and retry-after metadata from P3 without interpreting it as permission
  to retry.
- Provider messages are untrusted. Normalize them to bounded, single-line, safe text or use a
  fixed message; never include request bodies, credentials, headers, raw responses, or arbitrary
  HTML.
- Malformed success payloads and invalid stream state are protocol failures, not successful empty
  responses.
- Diagnostics and test failures must redact API keys, authorization fields, request input, and raw
  provider output.

## Work packages

Execute packages in order and keep only one active package at a time.

### P4-A: Activation, secret safety, and live-gate foundation

Status: `Completed` on August 2, 2026.

- Add this detailed plan and activate P4 without adapter behavior.
- Add ignored local live-environment patterns and a tracked value-free example.
- Document credential names, rotation, least-privilege use, and evidence boundaries.
- Add a fail-closed `./scripts/check-live.sh openai` entry point that requires a clean exact head,
  explicit model, process-environment credential, deterministic tests first, and a dedicated live
  test task.
- Add deterministic shell regressions proving clean-head binding, missing-credential failure,
  model requirement, command order, and output redaction.
- Add live-impact classification and deterministic tests.
- Add a separate protected live workflow with a stable `Required live verification` status.
- Create the `live-provider` GitHub Environment with required approval and add the stable live
  status to branch protection before the first adapter pull request.
- Keep the live command fail closed until P4-B adds the dedicated adapter live task.

Completion record:

- Candidate branch: `feature/p4-live-verification-foundation`; the exact reviewed head, CI run,
  merge, and resulting `main` evidence belong in the pull-request brief because embedding a
  candidate SHA in its own commit would change that SHA.
- Added a clean-exact-head OpenAI live runner that requires a process-environment credential and
  explicit model, strips live values from its deterministic prerequisite, uses no reusable daemon
  for the live task, and fails closed while the P4-B task is absent.
- Added deterministic regressions for missing configuration, stale or dirty heads, deterministic-
  before-live ordering, bounded success evidence, credential redaction, live-impact activation,
  documentation-only changes, adapter changes, adapter removal, and invalid revisions.
- Added a separate pinned-action workflow that keeps impact classification secretless, blocks
  affected fork code from the protected job, and exposes `Required live verification`.
- Added ignored local-environment patterns, a value-free example, credential/rotation guidance,
  and a protected GitHub `live-provider` Environment with required approval but no stored
  credential or model value.
- The stable live context is added to branch protection after this workflow reaches `main` and
  before any P4-B pull request. P4-B may not create a PR until its exact-head local live suite
  passes.
- Proof is secretless and deterministic. No OpenAI adapter, DTO, registration, credential value,
  model value, network request, provider response, stream, error, or cancellation behavior is
  proven.

### P4-B: Configuration, DTOs, and unary Responses

Status: `In progress`.

- Add bounded OpenAI provider configuration and registration behind existing clients.
- Add internal request, unary response, usage, and error DTOs.
- Implement text request translation, unary response translation, safe errors, and redaction.
- Add the deterministic OpenAI adapter suite and dedicated JVM live test task.
- Run exact-head local live response and error smoke tests before creating or updating the PR.

### P4-C: Responses streaming and cancellation

Status: `Not started`.

- Add typed Responses streaming event DTOs and translation state.
- Cover output correlation, deltas, completion, malformed events, premature EOF, and exactly-once
  terminal behavior.
- Prove response streaming, cancellation, cleanup, and redaction deterministically and live.

### P4-D: Structured output and capabilities

Status: `Not started`.

- Map governed JSON Schema intent to Responses `text.format`.
- Validate provider-compatible schema constraints without weakening the canonical governed subset.
- Map structured results, refusal, incomplete output, and schema-related provider errors.
- Publish conservative OpenAI streaming and structured-output capability declarations.
- Add any approved typed OpenAI extension helper with sibling-preservation and conflict tests.
- Prove plain and structured response paths deterministically and live.

### P4-E: Integration and acceptance

Status: `Not started`.

- Reconcile unary, streaming, structured output, error, cancellation, ownership, and cleanup.
- Audit public/package boundaries, credentials, diagnostics, artifacts, samples, and ordinary CI.
- Compile existing Kotlin and Swift consumers without provider-specific host behavior.
- Run the full exact-head deterministic matrix and all required exact-head live smoke tests.
- Complete independent review, required CI, protected live verification, and milestone closeout.

## Live verification

Local affected live verification:

```bash
OPENAI_API_KEY=... \
OPENAI_LIVE_MODEL=... \
./scripts/check-live.sh openai
```

The command:

1. requires a clean checked-out commit and binds execution to its exact SHA;
2. validates provider selection, credential presence, and explicit model selection without
   printing credential material;
3. runs the deterministic OpenAI adapter suite first;
4. runs the smallest live response, streaming, error, and cancellation tests required by the
   active package; and
5. prints only bounded evidence metadata after success.

The protected workflow reruns the same command for the exact pull-request head through the
`live-provider` Environment. Affected same-repository heads require approval. Fork code never
receives live credentials; a maintainer must move an affected contribution to a trusted
same-repository head before protected execution.

Every affected head change invalidates earlier local and protected evidence. Missing credentials,
missing model configuration, unavailable provider, rate limit, environment rejection, skipped
protected execution, or failed assertion blocks PR creation or update locally and blocks readiness
and merge remotely.

## Deterministic test matrix

- provider configuration, credential callback timing, missing credentials, and redacted failures;
- exact OpenAI request JSON and protected headers through `MockEngine`;
- plain text, multiple outputs, structured JSON, refusal, incomplete, and malformed responses;
- usage aggregates and compatible detail counters;
- every accepted HTTP error class plus malformed and oversized provider errors;
- request-ID and retry-after metadata without automatic retry;
- SSE chunk boundaries, event ordering, output correlation, deltas, completion, failure, EOF, and
  unsupported event kinds;
- cancellation before headers, after headers, during a delta, and before completion;
- exactly-once terminal delivery, late-event suppression, and connector-close cleanup;
- capability declarations, schema preflight, extension conflicts, and unknown sibling retention;
- public-signature and artifact scans excluding credentials and provider DTOs; and
- live-runner clean-head, credential, model, ordering, impact, and redaction regressions.

## Verification

Use focused deterministic checks while implementing:

```bash
./gradlew :bridge:jvmTest
./gradlew :bridge:testAndroidHostTest
./gradlew :bridge:iosSimulatorArm64Test
```

Every committed package passes the quick gate through the mandatory pre-commit hook. Every push
passes the full gate through the mandatory pre-push hook. P4-B through P4-E additionally run the
affected local live suite on the exact clean head before initial PR creation and before every
later push:

```bash
./scripts/check.sh --quick
./scripts/check.sh --full
./scripts/check-live.sh openai
```

P4 completion requires exact-head deterministic CI, the protected exact-head live status,
independent review, guarded merge, and resulting `main` inspection. P4-A is secretless foundation
work and does not claim a successful live suite.

## Acceptance criteria

- P4 is the only milestone `In progress`, with exactly one active package.
- OpenAI remains behind the existing Kotlin client and Swift façade.
- Provider credentials remain host-supplied, redacted, absent from canonical requests and
  artifacts, and unavailable to ordinary CI or untrusted heads.
- Requests, unary responses, streams, structured output, capabilities, and errors satisfy this
  plan through deterministic fixtures.
- Cancellation reaches transport/body work and preserves exactly-once terminal behavior.
- Unsupported or malformed provider behavior fails safely rather than producing partial canonical
  success.
- Existing deterministic samples and consumers remain zero-configuration and provider-neutral.
- The local and protected live gates pass for the exact P4 closing head.
- No provider DTO, credential type, raw response, Ktor implementation, or callback plumbing leaks
  through supported host APIs.

## Proof limits

P4 can prove deterministic and targeted live compatibility with the exercised OpenAI Responses
paths, model, account, region, network, and execution dates recorded in the exact-head PR evidence.
It can prove package and existing-consumer compatibility through the repository gates.

P4 does not prove:

- every OpenAI model, account tier, region, response item, event type, hosted tool, or future API
  revision;
- Chat Completions, Assistants, Realtime, Batch, background mode, tools, multimodal behavior, or
  model discovery;
- automatic retry, reconnect, provider failover, Gateway behavior, or P5-P7 compatibility;
- physical-device or Android-emulator live execution unless separately run and recorded;
- production credential storage, application UI, OpenKeyboard integration, or Gateway V1;
- remote Maven or Swift Package distribution, signing, checksums, or released-artifact
  consumption; or
- alpha-release readiness before P8-P9 complete.
