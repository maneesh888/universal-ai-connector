# Live Provider Testing

Live verification is separate from the deterministic repository gates. Normal local checks and
ordinary GitHub Actions remain secretless. Beginning with P4, a change that can affect a delivered
provider or Gateway path must pass the exact-head local live gate. GitHub validates only bounded
exact-head evidence through the credential-free `live-policy` deployment; it does not run
provider tests or receive provider credentials.

## OpenAI credential contract

The OpenAI live suite reads two process-environment values:

- `OPENAI_API_KEY`: a dedicated, revocable, low-quota test-project key.
- `OPENAI_LIVE_MODEL`: an explicit model identifier enabled for that test project.

Do not use a production key. Restrict access to the test project, set conservative spend and rate
limits, and monitor its usage. The repository, samples, mobile or desktop artifacts, Gradle
properties, command-line arguments, and normal CI must never contain the key.

Create and manage API keys through the official
[OpenAI API-key settings](https://platform.openai.com/settings/organization/api-keys), and follow
the official [production key-safety guidance](https://developers.openai.com/api/docs/guides/production-best-practices).
An API project needs its own billing/credits and limits; a ChatGPT subscription is not an API
credential.

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

Set `OPENAI_API_KEY` and `OPENAI_LIVE_MODEL` in that local editor. Never print the file to diagnose
it. The live script and hook do not open, read, or source files automatically; they accept values
only from their process environment, which keeps file choice and permissions under host control.
If either value is absent, the failure repeats these setup directions instead of skipping.

## Local exact-head gate

Commit the candidate first, ensure the checkout is clean, export the manually configured file as
shown above, and run:

```bash
./scripts/check-live.sh openai
```

The command refuses a dirty checkout, validates the expected SHA when
`UAC_LIVE_EXPECTED_SHA` is present, runs deterministic tests first, and then runs the dedicated
OpenAI live task without a reusable Gradle daemon. It never prints the credential or full provider
request/response content, and disables Gradle configuration caching for the credential-bearing
process.

The completed P4 `:bridge:openAiLiveTest` task covers one minimal non-streaming response, one
governed structured response, one safe intentional provider error, one ordered streaming
response, one pending-response cancellation, and one active-stream cancellation. It is excluded
from `jvmTest`, `check.sh`, samples, and ordinary CI, and fails closed without valid inputs or
provider access.

The pre-push hook runs `scripts/live-impact.sh` between `origin/main` and exact `HEAD`. Unrelated
changes run only the full deterministic gate. Affected bridge, Swift façade, build, transport, or
live-gate changes additionally run `check-live.sh`; missing configuration, quota, model access,
provider availability, or assertions block the push. Set `UAC_LIVE_BASE_REF` only when the
intended base is genuinely different and locally resolvable.

Every head change invalidates earlier evidence. For an affected pull request, record:

- exact 40-character head SHA;
- `./scripts/check-live.sh openai`;
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

The `Universal AI Connector Live Verification` workflow classifies whether the exact pull-request
head can affect live adapter behavior. After the P4-A foundation reaches the default branch, every
bridge source, bridge build, Swift package, repository build-infrastructure, or live-gate change is
conservatively affected; classification never depends on an adapter package name or sentinel file.
A documentation-only change produces a successful secretless `Required live verification` result
automatically. The one-time bootstrap used before the trusted classifier exists rejects every
bridge source, Swift package, or build-behavior change.

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

Revoke the old key in the OpenAI project first, create a replacement with the same least-privilege
scope, update only the approved local secret store, and run the smallest live smoke test. Never
commit a rotation record containing key material.

If exposure may have reached Git history, artifacts, caches, or pull-request text, revocation is
still the first action. Follow with repository-specific cleanup and security review; history
rewrites are separate destructive operations and require explicit scope.

## Proof limits

A passing live gate proves only the provider, model, account, network, exact commit, and paths
recorded for that execution. It does not prove every OpenAI model or feature, physical-device
behavior, Gateway behavior, provider failover, released-artifact distribution, or production
credential management.
