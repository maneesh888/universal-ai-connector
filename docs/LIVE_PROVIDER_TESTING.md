# Live Provider Testing

Live verification is separate from the deterministic repository gates. Normal local checks and
ordinary GitHub Actions remain secretless. Beginning with P4, a change that can affect a delivered
provider or Gateway path must pass both the exact-head local live gate and the protected exact-head
GitHub live gate.

## OpenAI credential contract

The OpenAI live suite reads two process-environment values:

- `OPENAI_API_KEY`: a dedicated, revocable, low-quota test-project key.
- `OPENAI_LIVE_MODEL`: an explicit model identifier enabled for that test project.

Do not use a production key. Restrict access to the test project, set conservative spend and rate
limits, and monitor its usage. The repository, samples, mobile or desktop artifacts, Gradle
properties, command-line arguments, and normal CI must never contain the key.

The tracked `.env.live.example` is deliberately value-free. If a local file is convenient, copy it
to `.env.live`, fill it outside Git, and source it into the current shell:

```bash
set -a
source .env.live
set +a
```

The live script does not source files automatically. It accepts credentials only from its process
environment, which keeps file choice and permissions under host control.

## Local exact-head gate

Commit the candidate first and ensure the checkout is clean:

```bash
OPENAI_API_KEY=... \
OPENAI_LIVE_MODEL=... \
./scripts/check-live.sh openai
```

The command refuses a dirty checkout, validates the expected SHA when
`UAC_LIVE_EXPECTED_SHA` is present, runs deterministic tests first, and then runs the dedicated
OpenAI live task without a reusable Gradle daemon. It never prints the credential or full provider
request/response content.

P4-A installs the fail-closed runner before adapter behavior. Until P4-B adds
`:bridge:openAiLiveTest`, an attempted OpenAI live run fails and blocks an adapter pull request.

Every head change invalidates earlier evidence. For an affected pull request, record:

- exact 40-character head SHA;
- `./scripts/check-live.sh openai`;
- provider and model identifier;
- execution date;
- pass or fail result; and
- limits of the exercised response, streaming, error, and cancellation paths.

Do not attach raw logs when they can contain sensitive input or provider output.

## Protected GitHub gate

The `Universal AI Connector Live Verification` workflow classifies whether the exact pull-request
head can affect live adapter behavior. After the P4-A foundation reaches the default branch, every
bridge source, bridge build, Swift package, repository build-infrastructure, or live-gate change is
conservatively affected; classification never depends on an adapter package name or sentinel file.
A documentation-only change produces a successful secretless `Required live verification` status.
The one-time bootstrap used before the trusted classifier exists rejects every bridge source,
Swift package, or build-behavior change.

An affected same-repository head runs through the protected `live-provider` Environment and
requires approval before the environment releases `OPENAI_API_KEY` and `OPENAI_LIVE_MODEL`.
The workflow checks out and verifies the exact pull-request head.

Fork pull-request code never receives live credentials. A maintainer must inspect the contribution,
move the approved commit to a same-repository branch, and use that trusted head for protected live
verification. Never use `pull_request_target` to execute contribution code.

The stable status job targets the credential-free `live-policy` Environment. A branch ruleset
requires a successful deployment to that Environment before merge, so candidate workflow changes
cannot manufacture merge readiness without server-enforced maintainer approval. This policy
approval is separate from `live-provider` approval and never releases provider credentials.

Missing configuration, rejected approval, skipped execution, rate limiting, provider outage, or a
test failure is a blocker rather than a skipped success.

### Merge-banner runbook

`Missing successful active live-policy deployment` means the latest pull-request head has not yet
completed the credential-free policy approval. An older pull request or an earlier commit cannot
satisfy this exact-head gate. This message does not indicate a merge conflict or provider
credential failure.

First confirm ordinary required checks are successful. Close an invalid or superseded pull request
instead of approving its environments. For a valid unaffected head, approve `live-policy`. For a
valid affected same-repository head, approve `live-provider`, wait for the protected provider suite
to pass, and then approve `live-policy`. Every head change requires fresh exact-head results.

## Rotation

Rotate the test key immediately when:

- it may have appeared in a file, terminal transcript, process argument, artifact, or log;
- a collaborator with access no longer needs it;
- the provider or GitHub reports suspicious use; or
- the routine rotation interval for the test project expires.

Revoke the old key in the OpenAI project first, create a replacement with the same least-privilege
scope, update only the `live-provider` Environment secret and approved local secret store, and run
the smallest live smoke test. Never commit a rotation record containing key material.

If exposure may have reached Git history, artifacts, caches, or pull-request text, revocation is
still the first action. Follow with repository-specific cleanup and security review; history
rewrites are separate destructive operations and require explicit scope.

## Proof limits

A passing live gate proves only the provider, model, account, network, exact commit, and paths
recorded for that execution. It does not prove every OpenAI model or feature, physical-device
behavior, Gateway behavior, provider failover, released-artifact distribution, or production
credential management.
