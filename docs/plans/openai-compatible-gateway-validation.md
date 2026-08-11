# P7 OpenAI-Compatible Gateway Validation

## Status and activation gate

Status: `In progress` at P7-A.

P0-P6 are completed. P7 was activated on August 11, 2026 after the separately maintained LLM
Gateway's stable, tested OpenAI-compatible contract was pinned for P7-A, and P7 became the sole
roadmap milestone marked `In progress`.

P7-A freezes the deterministic compatibility boundary and adds representative fixtures. It does
not claim that a Gateway deployment or the connector-to-Gateway network path has been verified.

## Objective

Validate that the existing generic `openai-compatible` adapter can consume the LLM Gateway as an
independently selectable AI endpoint. Reuse the accepted canonical contracts, provider-neutral
configuration, transport, lifecycle, and host façades without adding a proprietary Gateway
protocol or a fourth runtime adapter.

The intended configuration is:

- provider ID `openai-compatible`;
- a host-configured Gateway base URL whose accepted path prefix is documented;
- an explicit Gateway model identifier; and
- a synchronous host-owned supplier for the Gateway's bearer credential.

The connector treats the Gateway as an external OpenAI-compatible endpoint. It does not know or
depend on which model backend the Gateway uses and does not assume that OpenAI, Anthropic,
OpenRouter, or other provider credentials are stored there.

## Architecture decision

P6 already delivered the generic OpenAI-compatible Chat Completions adapter. P7 therefore adds
compatibility evidence and only those correctness fixes that are required by the Gateway's
finalized standard contract.

P7 must not add:

- a `gateway` provider identifier or provider-specific public configuration type;
- Gateway-specific request, response, error, stream, or model DTOs;
- a separate registry entry, adapter lifecycle, Kotlin host API, or Swift host API;
- knowledge of Gateway administration, storage, rate-limit policy, model routing, or upstream
  implementation; or
- OpenKeyboard actions, prompts, App Group storage, Keychain storage, UI, or migration logic.

If the finalized Gateway requires proprietary fields for basic Chat Completions behavior, pause
P7 and reconcile the Gateway contract first. Optional Gateway extensions used by another client
do not become connector requirements.

## Compatibility boundary

The authoritative Gateway contract must be frozen from its implementation and deterministic tests
before P7 runtime work begins. The expected conservative intersection is:

- bearer authentication through `Authorization`;
- `POST /v1/chat/completions`, expressed to the connector as a base URL ending in `/v1` plus the
  existing relative `chat/completions` endpoint;
- text-only ordered `system`, `user`, and `assistant` messages;
- one explicit model identifier;
- the generation parameters already supported by the generic adapter;
- one supported non-streaming choice with text, finish state, and bounded usage;
- the accepted strict JSON-schema structured-output intersection where the selected Gateway model
  supports it;
- OpenAI-compatible SSE data records and `[DONE]` termination for streaming; and
- HTTP-status-based safe canonical error mapping, cancellation propagation, response cleanup,
  redaction, and no retry after content begins.

`GET /v1/models` may remain part of the Gateway's client contract, but P7 does not add model
discovery unless a separate canonical host requirement is approved. Gateway-specific operation
fields are outside the connector's generic contract.

## Work packages

Execute one package at a time after activation.

### P7-A: External contract freeze and deterministic compatibility fixtures

Status: `Completed in the current candidate`.

- Bind the supported subset to the finalized Gateway implementation, documentation, and tests.
- Record exact base-URL, authentication, request, response, error, streaming, structured-output,
  and cancellation expectations.
- Add Gateway-representative `MockEngine` fixtures to the existing generic adapter tests.
- Add no live task, runtime adapter, provider identifier, host API, or sample behavior.

Completion becomes authoritative only after the candidate passes the repository gates, ordinary
CI, independent review, guarded merge, and resulting-`main` verification. Exact-head evidence
belongs in the pull-request brief.

### P7-B: Compatibility corrections and local live gate

Status: `Not started`.

- Implement only compatibility or correctness fixes demonstrated by P7-A fixtures.
- Preserve safe generic error handling when Gateway error envelopes include optional extensions.
- Add a dedicated, fail-closed local Gateway validation command using ignored, host-supplied base
  URL, Gateway credential, and model inputs.
- Prove non-streaming, structured output where supported, streaming, error, and active
  cancellation against the selected local or deployed Gateway without retaining secrets or
  response bodies.

### P7-C: Lifecycle integration and acceptance

Status: `Not started`.

- Reconcile concurrent response/stream behavior, close races, cleanup, redaction, and the existing
  Kotlin and Swift package boundaries.
- Document the copy-paste `openai-compatible` Gateway configuration for supported hosts.
- Pass the complete deterministic gate, exact-head Gateway live gate, secretless policy, ordinary
  CI, independent review, guarded merge, and resulting-`main` verification.
- Mark P7 complete only in its milestone-closing change.

## P7-A external contract freeze

### Authoritative source identity

The supported Gateway intersection is bound to
[`maneesh888/open-keyboard-llm-gateway`](https://github.com/maneesh888/open-keyboard-llm-gateway)
commit `ed4cf92056df69d670b30f959ec192c8de742e41`, pinned for P7-A on August 11, 2026. The following
files at that commit define the standard client-facing behavior used by P7-A:

- `README.md` and `docs/OPEN_KEYBOARD_CLIENT.md` document bearer authentication and the
  `/v1/chat/completions` endpoint;
- `src/server.ts`, `src/middleware/auth.ts`, and `src/middleware/rateLimit.ts` define route,
  authentication, HTTP error, and retry-header behavior;
- `src/proxy/ollama.ts` defines standard request forwarding, model allowlisting, unmodified normal
  response forwarding, SSE pass-through, and upstream failure statuses; and
- `tests/auth.test.ts`, `tests/integration.test.ts`, `tests/middleware/rateLimit.test.ts`, and
  `tests/proxy.test.ts` provide the deterministic source contract.

The four targeted Gateway suites contain 56 tests and passed locally on August 11, 2026 against
identical contract and test blobs. The local checkout also contained a later admin-UI-only commit;
that commit did not change any source or test file listed above. The exact Gateway commit remains
the compatibility identity so later Gateway changes do not silently widen this package.

### Supported standard intersection

- Configure provider ID `openai-compatible` with a base URL ending in `/v1`; the existing relative
  endpoint produces `POST /v1/chat/completions` without a Gateway-specific path or registry entry.
- Send `Authorization: Bearer <gateway-api-key>`, `Content-Type: application/json`, and the existing
  response-mode `Accept` header. Credentials remain host supplied and never enter request bodies,
  errors, fixtures, or retained evidence.
- Send one explicit model and ordered text-only `system`, `user`, and `assistant` messages. The
  existing bounded `max_tokens`, `temperature`, `top_p`, and `stop` fields are forwarded as part of
  the standard Chat Completions body.
- Accept one standard non-streaming choice with assistant text, a supported finish reason, optional
  bounded usage, and harmless unknown fields. Gateway administration and OpenKeyboard operation
  extensions are not part of this intersection.
- Use standard `response_format.type = json_schema` only for the connector's already-governed
  strict schema subset. The pinned Gateway forwards ordinary Chat Completions request bodies and
  successful response bodies; actual structured-output support remains selected-model dependent,
  and the connector revalidates returned JSON before producing canonical structured output.
- Map Gateway authentication, model-allowlist, rate-limit, upstream-unavailable, and timeout
  statuses through the existing safe status-based generic mapping. Optional Gateway `error`,
  `detail`, `retryAfter`, `limit`, and `remaining` members are untrusted and are not surfaced;
  bounded `Retry-After` metadata remains supported.
- Accept OpenAI-compatible `text/event-stream` data records with standard
  `chat.completion.chunk` payloads and `[DONE]`. The pinned Gateway proves streaming response-body
  pass-through, while the connector owns framing, ordering, terminal validation, cleanup, and the
  no-retry-after-content rule.
- Caller cancellation must cancel the connector's in-flight HTTP request and remain cancellation,
  not a canonical provider error. The pinned Gateway does not document or deterministically prove
  that a disconnected client cancels its own upstream fetch, so P7 does not make that stronger
  server-side resource claim.

### Deterministic fixture boundary

`OpenAiCompatibleGatewayP7ATests` covers the pinned standard intersection through the existing
generic adapter, Ktor `MockEngine`, and a cancellation-tracking transport: request path and bearer
authentication, non-streaming response translation, strict structured output, safe Gateway
status/envelope handling, SSE and `[DONE]` translation, and in-flight caller cancellation. The
accepted generic-adapter lifecycle tests continue to cover cleanup and close races. These are
Gateway-representative protocol examples, not a second adapter or an assertion that every Gateway
backend/model supports every optional feature.

## Verification

During implementation, use focused generic-adapter tests and affected host checks. Every committed
package passes the repository's mandatory gates. Gateway-affecting runtime work must also pass the
dedicated exact-head local Gateway validation added by P7-B.

Normal CI remains secretless. Missing Gateway configuration, an unavailable Gateway or model,
authentication failure, rate limit, stale exact-head evidence, or a failed assertion blocks live
acceptance rather than becoming a skipped success.

## Acceptance criteria

- The Gateway is consumed through provider ID `openai-compatible` and the existing generic
  adapter.
- No Gateway-specific public or internal protocol surface is added.
- Deterministic fixtures cover the finalized supported request, response, structured-output,
  error, streaming, cancellation, and cleanup intersection.
- Targeted live validation passes against the selected Gateway endpoint and model on the exact
  acceptance head.
- Kotlin and Swift host entry points remain provider-neutral and existing consumers compile.
- Documentation gives accurate base URL, credential, model, supported-feature, and limitation
  guidance.
- Secrets, authorization headers, sensitive request/response bodies, logs, and generated artifacts
  remain excluded from Git and retained evidence.
- OpenKeyboard integration remains outside this package milestone.

## Proof limits

Deterministic fixtures prove only the committed protocol examples. Targeted live validation proves
only the selected Gateway deployment, model, supported operation subset, network, exact head, and
recorded time. It does not prove every OpenAI-compatible client or server, model, optional field,
error variant, stream timing, deployment, backend implementation, billing or quota policy, or
future Gateway revision.

P7 does not prove Gateway server correctness, Gateway administration, OpenKeyboard integration,
physical-device execution, remote package distribution, or alpha-release readiness.
