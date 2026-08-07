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

OpenAI and Anthropic are delivered local-live gates after P5-B. OpenRouter remains a value-free
readiness input only: no runner route, Gradle live task, or provider network behavior receives it.
P6-B must atomically deliver the OpenRouter paths and add OpenRouter to real provider selection,
then pass the exact-head OpenRouter gate before its first push or pull-request update.

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

Set only the provider values needed locally. OpenRouter values are not consumed until P6-B
delivers its real live route. Never print the file to diagnose it. The live script and hook do not
open, read, or source files automatically; they accept values only from their process environment,
which keeps file choice and permissions under host control. If a selected delivered provider
input is absent, the failure repeats value-free setup directions instead of skipping.

## Local exact-head gate

Commit the candidate first, ensure the checkout is clean, export the manually configured file as
shown above, and run:

```bash
./scripts/check-live.sh openai
./scripts/check-live.sh anthropic
```

The command refuses a dirty checkout, validates the expected SHA when
`UAC_LIVE_EXPECTED_SHA` is present, runs deterministic tests first, and then runs the dedicated
selected-provider live task without a reusable Gradle daemon. It never prints the credential or
full provider request/response content, and disables Gradle configuration caching for the
credential-bearing process.

The completed P4 `:bridge:openAiLiveTest` task covers one minimal non-streaming response, one
governed structured response, one safe intentional provider error, one ordered streaming
response, one pending-response cancellation, and one active-stream cancellation. It is excluded
from `jvmTest`, `check.sh`, samples, and ordinary CI, and fails closed without valid inputs or
provider access.

The P5-B `:bridge:anthropicLiveTest` task covers one minimal non-streaming text response and one
pending-response cancellation after credential resolution. It has the same deterministic/CI
exclusions and fail-closed exact-head behavior. Structured output, intentional provider-error,
streaming, and active-stream cancellation proof remain P5-C/P5-D work.

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
- the selected `./scripts/check-live.sh openai` and/or
  `./scripts/check-live.sh anthropic` command;
- provider and model identifier;
- execution date;
- pass or fail result; and
- limits of the exercised response, structured-output, intentional-error, streaming, and
  cancellation paths.

Do not attach raw logs when they can contain sensitive input or provider output.

For an affected PR, include these exact policy statements with the current SHA:

```text
Local live verification: passed
Exact head SHA: <40-character SHA>
No credential or provider response body retained.
```

## Secretless GitHub policy

The `Universal AI Connector Live Verification` workflow classifies the provider set affected by
the exact pull-request head. Provider-specific paths select that delivered provider; shared
bridge, build, Swift package, authentication, and live-policy paths select all delivered providers.
An undelivered or ambiguous affected provider path fails closed to every delivered provider. A
documentation-only change produces a successful secretless `Required live verification` result
automatically. The workflow retains trusted-base compatibility with the legacy P4 boolean
classifier and accepts stable ordered provider sets through
`openai,anthropic,openrouter`.

For an affected PR, the workflow checks that its body says the local run passed, contains the
current exact head SHA, and records the no-retention boundary. It executes no candidate provider
test, uses no secret or model variable, and works the same way for same-repository and fork heads.
Never use `pull_request_target`.

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
SHA, command/result, and proof limits are current. Do not approve `live-provider`; the local-only
workflow never requests it. Every head change requires a fresh local run and updated PR evidence.

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
recorded for that execution. P5-B adds bounded Anthropic authentication, non-streaming response,
and pending-cancellation proof for the selected exact head; it does not prove structured output,
complete provider errors, capabilities, streaming, or active-stream cancellation. P6-A likewise
proves no OpenRouter credential, credit, model access, authentication, compatibility, or provider
behavior. A provider gate does not prove every model or feature, physical-device behavior, Gateway
behavior, provider failover, released-artifact distribution, or production credential management.
