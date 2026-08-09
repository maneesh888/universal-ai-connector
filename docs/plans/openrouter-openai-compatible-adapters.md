# P6 OpenRouter and OpenAI-Compatible Adapters

## Status and activation gate

P0-P5 are `Completed`. P6 was explicitly activated on August 7, 2026 and completed P6-A/P6-B
before P5 resumed. P5 then completed authoritatively through PR #50 and resulting-`main`
verification. P6 is now the only `In progress` milestone, and this candidate implements P6-D.

P6-A completed protocol, configuration, and live-test authentication readiness. P6-B completed
direct OpenRouter non-streaming request/response translation and exact-head local-live delivery.
P6-C completed authoritatively through PR #52 and resulting-`main` verification. P6-D is `In
progress` in this candidate, adding structured output, complete bounded error/metadata handling,
and conservative capabilities; P6-E remains inactive.

P6-C's accepted scope added no structured output, complete typed provider-error translation,
streaming, or capability claims. Its exact-head deterministic and delivered-provider live gates,
ordinary CI, secretless live-policy status, independent review, guarded merge, and
resulting-`main` verification passed through PR #52.

## Objective

Add internal OpenRouter and generic OpenAI-compatible Chat Completions adapters while preserving
the existing canonical contracts, Kotlin client, Swift façade, provider-neutral configuration,
transport lifecycle, cancellation, terminal arbitration, secret-safety policy, and package
boundaries.

The completed milestone must provide:

- one internal OpenRouter adapter for the direct OpenRouter Chat Completions endpoint;
- one internal generic OpenAI-compatible adapter for a conservative Chat Completions subset;
- shared internal wire translation only where the two protocols have identical governed meaning;
- canonical request, response, structured-output, usage, metadata, error, and streaming mapping;
- host-supplied credentials through the existing synchronous provider-neutral supplier;
- deterministic Ktor `MockEngine` and local-fixture coverage;
- targeted live OpenRouter response, structured-output, streaming, error, and cancellation proof;
- representative live compatibility proof through the generic adapter against the selected
  OpenRouter endpoint, with explicit proof limits for other servers; and
- exact-head local-live evidence accepted by the existing secretless `live-policy` gate.

## Sequencing decision

P5 deferral was not P5 completion. The repository implemented and verified P6-A/P6-B while P5 was
deferred, but it
did not:

- describe the Anthropic adapter as implemented, approved, complete, or live-tested;
- reuse an OpenRouter credential as Anthropic proof;
- weaken P5 acceptance or silently remove Anthropic from the release matrix; or
- have more than one non-deferred milestone marked `In progress`.

That gate is now satisfied. P5 resumed and completed authoritatively through PR #50 and
resulting-`main` verification. The prior milestone-resumption transition then made P6 the only
`In progress` milestone and activated P6-C without implementing it. P6-C then completed through
PR #52 and resulting-`main` verification; this candidate implements the subsequently activated
P6-D package.

## Design constraints

- Keep all provider DTOs and behavior in Kotlin `commonMain`.
- Preserve one primary Kotlin client and one Swift façade. Add no provider-specific host methods,
  screens, DTOs, credential stores, or lifecycle paths.
- Reuse the P3 transport, URL/header/timeout policy, SSE parser, response metadata, immutable
  registry, ownership, cancellation, redaction, and exactly-once terminal boundary.
- Reuse `UniversalAiProviderConfiguration`; do not add a public OpenRouter credential or
  configuration type.
- Use canonical provider identifiers `openrouter` and `openai-compatible` behind the existing
  provider-neutral configuration.
- Keep provider wire fields internal. A shared translator must not erase OpenRouter-specific error
  or routing semantics or claim that every nominally compatible endpoint behaves identically.
- Do not read process-global environment values from the runtime library. Only the dedicated live
  runner reads provider-specific process inputs and passes the credential through the same
  host-owned supplier used by production code.
- Never put credentials in URLs, canonical requests, extensions, errors, logs, fixtures, samples,
  Gradle properties, command-line arguments, generated artifacts, or committed files.
- Preserve one ignored `.env.live` file with distinct provider-specific variables. Do not add a
  shared cross-provider key variable or a provider-specific secret file.
- Keep generation retry disabled by default. Do not reconnect or replay after response content
  begins.
- Preserve caller cancellation as cancellation and release response bodies promptly.
- Keep existing host targets, samples, packaging, and ordinary CI credential-free.

## P6-A readiness decisions

### Authoritative OpenRouter protocol

The following official OpenRouter sources were consulted on August 7, 2026 and revalidated for
P6-D on August 10, 2026; they govern the P6 subset:

- [Chat Completions request reference](https://openrouter.ai/docs/api/api-reference/chat/create-a-chat-completion)
- [Bearer-key authentication example and current-key endpoint](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key)
- [model identifiers and supported-parameter metadata](https://openrouter.ai/docs/guides/overview/models)
- [structured outputs](https://openrouter.ai/docs/guides/features/structured-outputs)
- [streaming and SSE keep-alive behavior](https://openrouter.ai/docs/api/reference/streaming)
- [errors, typed error codes, and mid-generation failures](https://openrouter.ai/docs/api/reference/errors-and-debugging)
- [provider routing](https://openrouter.ai/docs/guides/routing/provider-selection)
- [optional application-attribution headers](https://openrouter.ai/docs/app-attribution)

The direct OpenRouter adapter uses base URL `https://openrouter.ai/api/v1`, sends
`POST /chat/completions`, authenticates with `Authorization: Bearer <credential>`, and sends
`content-type: application/json` and `accept: application/json`.

`HTTP-Referer`, `X-OpenRouter-Title`, and `X-OpenRouter-Categories` are attribution inputs, not
authentication requirements. P6 does not synthesize them, read them from process state, or expose
new public configuration for them. A later activated package may accept a governed
provider-extension mapping only if it preserves validation, protected-header precedence, and
host/API compatibility.

The accepted initial request subset is text-only:

- canonical `system`, `user`, and `assistant` text inputs map in order to Chat Completions
  messages;
- unknown roles, multimodal content, tools, reasoning, plugins, presets, transforms, debug echo,
  web search, and provider-routing overrides are unsupported;
- one explicit canonical model maps to the OpenRouter model slug; model fallback arrays and
  automatic model selection are unsupported;
- `maxOutputTokens`, stop sequences, temperature, and top-p map only within canonical bounds;
- the OpenRouter adapter requests parameter-compatible routing when an activated implementation
  can do so without changing canonical intent; and
- the generic adapter sends no OpenRouter-only routing field.

The response subset accepts one successful choice with ordered text, a supported finish reason,
model identifier, usage, and bounded response metadata. Multiple choices, tool calls, reasoning,
images, audio, unknown required content, empty or incomplete results, and provider errors embedded
in a nominally successful response do not become canonical success.

Structured output uses `response_format.type = json_schema` only for the faithfully representable
intersection of the governed canonical schema subset and the selected model/endpoint capability.
The adapter must reject semantic weakening, parse returned content, and revalidate it against the
original governed schema. Model or endpoint support remains conservative until deterministic
metadata and the selected live model prove it.

Streaming uses SSE data records and recognizes `[DONE]` only after a valid supported response
sequence. SSE comments, including documented processing keep-alives, may be ignored. Mid-stream
error objects, invalid ordering, unsupported finish state, malformed data, or end-of-stream
without a valid terminal produce fixed safe failure. No retry or reconnection occurs after
content begins.

OpenRouter can return request errors through HTTP status/error envelopes and generation errors in
a response body or SSE event after processing starts. P6 must map typed error categories
conservatively, preserve bounded request/retry metadata, and never expose provider messages or
metadata verbatim.

### Generic OpenAI-compatible boundary

The generic adapter targets a host-configured HTTPS base URL and the relative
`chat/completions` endpoint through P3 URL policy. It uses Bearer authentication and the same
strict text-only wire subset, but sends no OpenRouter-specific headers, routing object, metadata,
or extensions.

Compatibility is behavioral, not nominal:

- the generic adapter accepts only fields implemented and covered by deterministic fixtures;
- capabilities default to unknown or unsupported unless the adapter and configured endpoint have
  explicit evidence;
- unknown fields may be ignored only when they cannot alter the supported result;
- server-specific errors remain fixed safe canonical failures unless a governed mapping exists;
  and
- representative live proof against OpenRouter demonstrates only that endpoint and model at the
  recorded time, not every OpenAI-compatible server.

### Configuration and credentials

The existing `UniversalAiProviderConfiguration` is sufficient:

- `providerId` selects `openrouter` or `openai-compatible`;
- `baseUrl` remains immutable and validated by P3 policy;
- the synchronous host-owned credential supplier is invoked exactly once per network request and
  its result is not retained; and
- missing, blank, malformed, throwing, or cancelling suppliers retain the existing safe
  authentication/cancellation behavior.

The runtime library has no environment-file dependency. The dedicated OpenRouter live process
uses:

- `OPENROUTER_API_KEY`: a dedicated, revocable, conservatively spend-limited test credential; and
- `OPENROUTER_LIVE_MODEL`: an explicit bounded-cost model slug enabled for that credential.

`.env.live` remains the only repository-defined local environment file. The tracked
`.env.live.example` remains value-free and contains the OpenAI, Anthropic, and OpenRouter names.

### Live verification and merge protection

- OpenAI remains the only delivered real gate in P6-A.
- The classifier understands OpenRouter paths, fails an undelivered OpenRouter change closed to
  all currently delivered providers, and returns stable order `openai,anthropic,openrouter` when
  synthetic tests enable all three.
- Pre-push regression stubs prove OpenAI-only, Anthropic-only, OpenRouter-only, and shared
  three-provider routing without calling a nonexistent OpenRouter task.
- Deterministic gates remove every documented provider key/model input.
- Each selected provider gate removes every non-selected provider input.
- Secret scanning, ordinary CI, and the secretless live-policy workflow recognize the OpenRouter
  names without receiving their values.
- P6-B atomically added `./scripts/check-live.sh openrouter`, the non-cacheable OpenRouter Gradle
  live task, and `openrouter` to the real delivered-provider set. The same exact head passed the
  real OpenRouter gate before its first push or pull-request update.
- After P6-B, OpenAI and OpenRouter became delivered real gates. P5-B subsequently added Anthropic
  as the third delivered gate during resumed P5 implementation.
- GitHub remains credential-free and validates only the retained exact-head evidence statements.

## Work packages

Execute one package at a time.

### P6-A: Protocol, configuration, and authentication-test readiness

Status: `Completed` and accepted August 7, 2026 through PR #40.

- Record the P5 deferral without a false completion claim and activate P6.
- Bind the supported OpenRouter and generic compatibility subsets to current official sources.
- Confirm reuse of provider-neutral configuration and credential supply.
- Extend the one-file value-free convention with OpenRouter key/model names.
- Extend classifier, pre-push, runner isolation, secret scanning, ordinary CI, and secretless
  policy regressions for a third provider.
- Keep OpenAI as the only real delivered provider gate.
- Add no OpenRouter/generic DTO, registry entry, runtime adapter, Gradle live task, or provider
  request.

### P6-B: OpenRouter non-streaming request and response translation

Status: `Completed`.

- Add the internal OpenRouter provider registration and bounded wire models.
- Implement authentication/header construction, text request translation, response translation,
  usage, metadata, and safe malformed/incomplete handling.
- Add deterministic `MockEngine` authentication, request/response, cancellation, and redaction
  fixtures.
- Atomically add the OpenRouter live route/task and real delivered-provider selection.
- Pass exact-head OpenRouter response and pending-cancellation smoke tests before first push.

### P6-C: Generic OpenAI-compatible construction and translation

Status: `Completed`; accepted August 10, 2026 through PR #52 and resulting-`main` verification.

- Add the internal `openai-compatible` registration without adding a provider-specific public
  configuration type.
- Reuse only the wire behavior proven identical to P6-B; keep OpenRouter-specific semantics out.
- Add deterministic base-URL, protected-header, request/response, unknown-field, error, and
  cancellation fixtures.
- Add representative live compatibility coverage against the selected OpenRouter model with
  explicit proof limits.

### P6-D: Structured output, errors, metadata, and capabilities

Status: `In progress`; implementation is complete in this candidate and awaits exact-head Release
verification and guarded merge.

- Implement the strict supported JSON-schema intersection and revalidation.
- Complete OpenRouter typed-error and generic safe-error mapping.
- Add conservative provider/model capability reporting.
- Cover schema, model-support, mid-generation error, request ID, retry metadata, and secret
  redaction deterministically and through the smallest affected live smokes.

### P6-E: Streaming translation and cancellation

Status: `Not started`.

- Implement incremental Chat Completions SSE translation through P3.
- Handle keep-alive comments, supported deltas, `[DONE]`, mid-stream errors, ordering, one
  authoritative terminal, and missing-terminal failure.
- Cover cancellation before headers, during content, between records, and after provider
  terminal.
- Pass affected live streaming and active-cancellation smokes.

### P6-F: Lifecycle integration and acceptance

Status: `Not started`.

- Reconcile registry, configuration, transport, concurrent requests, close races, errors,
  streaming, cleanup, and package boundaries.
- Compile affected existing consumers without adding provider-specific host controls.
- Complete DTO, credential, artifact, redaction, and secretless-CI audits.
- Pass full deterministic, OpenAI-selected shared, and complete OpenRouter live exact-head gates.
- Mark P6 complete only in a separate milestone-closing change with durable evidence.

## Verification

During implementation, use focused deterministic checks:

```bash
./gradlew :bridge:jvmTest
./gradlew :bridge:testAndroidHostTest
./gradlew :bridge:iosSimulatorArm64Test
```

Every committed package passes the quick gate; P6 activation/acceptance candidates pass the full
Release gate:

```bash
./scripts/check.sh --quick
./scripts/check.sh --full
```

Every exact head that adds or changes real OpenRouter behavior must pass:

```bash
./scripts/check-live.sh openrouter
```

P6-A changes shared local-live routing and therefore must pass the delivered OpenAI gate for its
exact head. It cannot claim or substitute OpenRouter live proof.

Missing credentials, absent model configuration, unavailable service, insufficient credit, rate
limits, stale SHA, task wiring failure, or assertion failure block provider-behavior PR creation
or update rather than becoming a skipped success.

## Acceptance criteria

- P5's temporary deferral is recorded without a false completion claim; after P6-B, P5 resumed at
  P5-B and completed authoritatively through P5-E, PR #50, and resulting-`main` verification.
- P6-A through P6-C are completed, P6 is the only `In progress` milestone, and the P6-D candidate
  adds structured output, bounded errors/metadata, and capabilities without activating P6-E.
- The supported direct and generic subsets are traceable to dated official sources.
- Existing provider-neutral configuration and credential supply remain the only host boundary.
- One ignored `.env.live` file holds distinct value-free provider names in the tracked example.
- Deterministic tests and ordinary CI receive no provider inputs.
- Provider selection and pre-push isolation cover all three providers without silently omitting a
  selected gate.
- OpenRouter and generic provider behavior remain internal and use existing host entry points.
- Requests, responses, structured output, errors, streaming, cancellation, cleanup, and
  capabilities pass deterministic acceptance when their packages activate.
- The exact P6 closing head passes full local, affected provider live, secretless policy, ordinary
  CI, independent review, guarded merge, and resulting-main verification.

## Proof limits

P6-A proves only the recorded protocol/configuration decision, value-free input convention,
provider-set routing, environment isolation, and secretless policy regressions. It does not prove
OpenRouter credential validity, credit, model access, authentication, request/response behavior,
structured output, errors, streaming, cancellation, generic endpoint compatibility, or provider
network behavior.

P6-B deterministic tests prove only the committed non-streaming fixtures and `MockEngine`
behavior. Its targeted live tests prove only the selected OpenRouter account, model, endpoint,
network, exact head, minimal response, and pending-cancellation paths at the recorded time. They do
not prove structured output, complete typed errors, streaming, generic compatibility, every
OpenRouter model/upstream provider, routing outcome, future protocol revision, or third-party
OpenAI-compatible endpoint.

P6-C deterministic tests prove only the committed conservative generic Chat Completions subset.
Its representative live test proves only that the selected OpenRouter endpoint and model accepted
that generic request and produced a supported response at the recorded exact head and time. It
does not prove compatibility with other servers, models, protocol variants, structured output,
complete error semantics, capabilities, or streaming.

P6-D deterministic tests prove the committed strict schema intersection, post-response
revalidation, documented OpenRouter typed-error fixtures, fixed generic HTTP-status mapping,
bounded transport metadata, redaction, and conservative capability declarations. Its targeted
live tests prove only the selected OpenRouter account, endpoint, model, direct/generic structured
requests, and invalid-model error path at the recorded exact head and time. They do not prove
every JSON Schema dialect, model, upstream provider, third-party compatible endpoint, provider
error variant, or streaming behavior.

P6 work does not prove Anthropic behavior, Gateway behavior, physical-device execution, remote
distribution, credential management, or alpha-release readiness.
