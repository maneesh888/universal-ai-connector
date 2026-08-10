# P7 OpenAI-Compatible Gateway Validation

## Status and activation gate

Status: `Not started` and inactive.

P0-P6 are completed. P7 may be activated only after the separately maintained LLM Gateway has a
stable, tested OpenAI-compatible contract and P7 becomes the sole roadmap milestone marked
`In progress`.

This plan records the corrected architecture before activation. It does not claim that the
Gateway, its current deployment, or the connector-to-Gateway path has been verified.

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

Status: `Not started`.

- Bind the supported subset to the finalized Gateway implementation, documentation, and tests.
- Record exact base-URL, authentication, request, response, error, streaming, structured-output,
  and cancellation expectations.
- Add Gateway-representative `MockEngine` fixtures to the existing generic adapter tests.
- Add no live task, runtime adapter, provider identifier, host API, or sample behavior.

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
