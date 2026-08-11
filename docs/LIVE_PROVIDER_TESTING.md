# Live Provider Testing

Live verification is separate from the deterministic repository gates. Normal local checks and
ordinary GitHub Actions remain secretless. Beginning with P4, a change that can affect a delivered
provider or Gateway path must pass the exact-head local live gate. GitHub validates only bounded
exact-head evidence through the credential-free `live-policy` deployment; it does not run
provider tests or receive provider credentials.

## Local input contract

The single ignored `.env.live` file may contain distinct inputs for each provider:

- `OPENAI_API_KEY`: a dedicated, revocable, low-quota test-project key.
- `OPENAI_LIVE_MODEL`: an explicit model identifier enabled for that test project.
- `ANTHROPIC_API_KEY`: a dedicated, revocable, conservatively quota-limited Anthropic test key.
- `ANTHROPIC_LIVE_MODEL`: an explicit bounded-cost model identifier enabled for that key.
- `OPENROUTER_API_KEY`: a dedicated, revocable, conservatively spend-limited OpenRouter test key.
- `OPENROUTER_LIVE_MODEL`: an explicit bounded-cost model slug enabled for that key.
- `GATEWAY_LIVE_BASE_URL`: the selected Gateway base URL ending in `/v1`; HTTPS is required except
  for exact loopback HTTP.
- `GATEWAY_API_KEY`: a dedicated, revocable Gateway-owned test credential.
- `GATEWAY_LIVE_MODEL`: an explicit model identifier enabled for that Gateway key and deployment.
- `GATEWAY_LIVE_STRUCTURED_OUTPUT`: explicit `true` or `false` capability evidence for the selected
  Gateway model; `false` records that the governed structured path is not claimed.

OpenAI, Anthropic, OpenRouter, and the OpenAI-compatible Gateway are delivered local-live gates.
Each direct-provider change selects its delivered gate, a generic `openai-compatible` adapter
change selects OpenRouter plus the Gateway, and shared or ambiguous live-impacting changes select
all four.

Do not use production keys. Restrict access to the test project or workspace, set conservative
spend and rate limits, and monitor usage. The repository, samples, mobile or desktop artifacts,
Gradle properties, command-line arguments, and normal CI must never contain a key.

Create and manage API keys through the official
[OpenAI API-key settings](https://platform.openai.com/settings/organization/api-keys), and follow
the official [production key-safety guidance](https://developers.openai.com/api/docs/guides/production-best-practices).
An API project needs its own billing/credits and limits; a ChatGPT subscription is not an API
credential.

Create and manage the P5-B Anthropic test key through the official
[Claude API authentication settings and guidance](https://platform.claude.com/docs/en/manage-claude/authentication).
Use an expiring workspace-scoped test key when available. Do not send a credential through issue,
pull-request, chat, or review text.

OpenRouter authenticates API requests with a Bearer key as documented by its official
[current-key API reference](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key).
Use a dedicated key with a conservative spending limit and an explicit bounded-cost model.

The Gateway credential is created and rotated by the selected self-hosted Gateway deployment. It
is not an upstream-provider key, and the connector neither knows nor retains which backend the
Gateway routes to. Use a test key with a conservative Gateway rate limit and model scope.

The tracked `.env.live.example` is deliberately value-free. Configure the ignored local file
manually:

```bash
cp .env.live.example .env.live
chmod 600 .env.live
${EDITOR:-vi} .env.live
git check-ignore -q .env.live

set -a
source .env.live
set +a
```

Set only the provider values needed locally. Never print the file to diagnose it. The live script
and hook do not
open, read, or source files automatically; they accept values only from their process environment,
which keeps file choice and permissions under host control. If a selected delivered provider
input is absent, the failure repeats value-free setup directions instead of skipping.

## Local exact-head gate

Commit the candidate first, ensure the checkout is clean, export the manually configured file as
shown above, and run:

```bash
./scripts/check-live.sh openai
./scripts/check-live.sh anthropic
./scripts/check-live.sh openrouter
./scripts/check-live.sh gateway
```

Each command refuses a dirty checkout, validates the expected SHA when
`UAC_LIVE_EXPECTED_SHA` is present, runs deterministic tests first, and then runs the selected
provider or Gateway task without a reusable Gradle daemon. It never prints the Gateway base URL,
credential, authorization header, or full request/response content, and disables Gradle
configuration caching for the credential-bearing process.

The completed P4 `:bridge:openAiLiveTest` task covers one minimal non-streaming response, one
governed structured response, one safe intentional provider error, one ordered streaming
response, one pending-response cancellation, and one active-stream cancellation. It is excluded
from `jvmTest`, `check.sh`, samples, and ordinary CI, and fails closed without valid inputs or
provider access.

The P5-D `:bridge:anthropicLiveTest` task covers one minimal non-streaming text response, one
governed structured response, one safe intentional unavailable-model error, one ordered streaming
response, one pending-response cancellation after credential resolution, and one active-stream
cancellation after observable content. It has the same deterministic/CI exclusions and
fail-closed exact-head behavior.

The P6 `:bridge:openRouterLiveTest` task covers direct and representative generic non-streaming,
structured output, safe errors, ordered streaming, pending cancellation, and active-stream
cancellation. It has the same exclusion, exact-head, credential-isolation, and fail-closed
boundaries.

The P7-B `:bridge:gatewayLiveTest` task uses only provider ID `openai-compatible` and covers a
non-streaming response with optional usage, one governed structured response on the selected
supporting model when `GATEWAY_LIVE_STRUCTURED_OUTPUT=true`, ordered streaming with usage and one
terminal, one fixed authentication error, and caller cancellation after an actual Gateway response
starts. The capability input is mandatory and accepts only `true` or `false`, so the conditional
path cannot silently skip. The task retains neither the Gateway base URL nor any provider response
body. The local command fails closed when the URL, credential, model, deployment, capability
input, or assertion is unavailable.

The pre-push hook runs `scripts/live-impact.sh` between `origin/main` and exact `HEAD`. The
classifier returns `none` or an ordered comma-separated delivered-provider set. Unrelated changes
run only the full deterministic gate, provider-specific changes select that delivered provider,
and shared or ambiguous affected changes select every delivered provider. The hook removes every
provider input from the deterministic gate and every non-selected provider input from each live
gate. Missing configuration, quota, model access, provider availability, task wiring, or
assertions block the push. Set `UAC_LIVE_BASE_REF` only when the intended base is genuinely
different and locally resolvable.

Every head change invalidates earlier evidence. For an affected pull request, record:

- exact 40-character head SHA;
- every selected `./scripts/check-live.sh <provider>` command;
- provider or Gateway label and model identifier;
- execution date;
- pass or fail result; and
- limits of the exercised provider-specific paths.

Do not attach raw logs when they can contain sensitive input or provider output.

For an affected PR, include these exact policy statements with the current SHA:

```text
Local live verification: passed
Exact head SHA: <40-character SHA>
No credential or provider response body retained.
Trust boundary: local execution is contributor-attested; GitHub verifies retained exact-head evidence only.
```

## Secretless GitHub policy

The `Universal AI Connector Live Verification` workflow classifies the provider set affected by
the exact pull-request head. Provider-specific paths select that delivered provider; shared
bridge, build, Swift package, authentication, and live-policy paths select all delivered providers.
An undelivered or ambiguous affected provider path fails closed to every delivered provider. A
documentation-only change produces a successful secretless `Required live verification` result
automatically. The workflow retains trusted-base compatibility with the legacy P4 boolean
classifier and accepts stable ordered provider sets through
`openai,anthropic,openrouter,gateway`; that is also the currently delivered ordered set.

For an affected PR, the workflow checks that its body says the local run passed, contains the
current exact head SHA, records the no-retention boundary, and explicitly acknowledges that local
execution is contributor-attested rather than independently proved by GitHub. It executes no
candidate provider test, uses no secret or model variable, and works the same way for
same-repository and fork heads. Never use `pull_request_target`.

The stable status job targets the credential-free `live-policy` Environment. A branch ruleset
requires a successful deployment to that Environment before merge. The deployment has no required
reviewer and completes automatically after the secretless evidence assertions pass. It never
releases provider credentials. The protected `live-provider` Environment and its required reviewer
are retained for possible future policy changes, but the current workflow does not request it and
stores no required local test input there.

Missing configuration, skipped execution, rate limiting, provider outage, or a test failure is a
blocker rather than a skipped success.

### Merge-banner runbook

`Missing successful active live-policy deployment` means the latest pull-request head has not yet
completed the credential-free evidence workflow. An older pull request or an earlier commit cannot
satisfy this exact-head gate. This message does not indicate a merge conflict or provider
credential failure.

First confirm ordinary required checks are successful. Close an invalid or superseded pull request
instead of repeatedly updating it. For an affected head, confirm the local-live statements, exact
SHA, trust-boundary acknowledgment, command/result, and proof limits are current. Do not approve
`live-provider`; the local-only workflow never requests it. Every head change requires a fresh
local run and updated PR evidence.

Because GitHub receives neither credentials nor provider output, this automatic policy proves only
that the required exact-head statements are retained. It cannot independently prove that the
developer executed the local command; exact-head local execution remains a contributor and
Release-review responsibility.

## Rotation

Rotate the test key immediately when:

- it may have appeared in a file, terminal transcript, process argument, artifact, or log;
- a collaborator with access no longer needs it;
- the provider or GitHub reports suspicious use; or
- the routine rotation interval for the test project expires.

Revoke the old key in the affected provider project or workspace first, create a replacement with
the same least-privilege scope, update only the approved local secret store, and run the smallest
available live smoke test. Never commit a rotation record containing key material.

If exposure may have reached Git history, artifacts, caches, or pull-request text, revocation is
still the first action. Follow with repository-specific cleanup and security review; history
rewrites are separate destructive operations and require explicit scope.

## Proof limits

A passing live gate proves only the provider, model, account, network, exact commit, and paths
recorded for that execution. P5-D adds bounded Anthropic authentication, non-streaming response,
one governed structured response, one intentional unavailable-model error, ordered streaming,
pending-response cancellation, and active-stream cancellation proof for the selected exact head;
it does not prove every schema, error, event sequence, model, account state, capability, or
cancellation timing. P6 proves only the selected OpenRouter paths and does not prove every model
or upstream route. P7-B proves only the selected Gateway deployment, model, non-streaming
optional-usage behavior, governed structured output only when the retained capability value is
`true`, ordered streaming with usage, one authentication error, and active stream cancellation on
the exact recorded head and date. It does not prove every Gateway deployment, backend, model,
optional field, error shape, stream timing,
upstream-disconnect cleanup, physical-device behavior, released-artifact distribution, or
production credential management.
