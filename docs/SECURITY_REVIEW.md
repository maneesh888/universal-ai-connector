# Code and security review workflow

## Advisory rollout and routing

Keep deterministic CI, secret scans, the project `pr-reviewer`, requirement coverage, exact-head
validity, draft/readiness rules, and guarded merge mandatory. External review availability is
advisory; validated correctness or security findings enter the existing blocker rules regardless
of their source. Missing, failed, or unavailable review is never a clean result.

The routine external Code Review route is **Codex app Code Review of the PR diff** when available.
Inspect the PR base/head and supporting code. App review is separate from automatic GitHub Code
Review and from the mandatory project `pr-reviewer`. If automatic GitHub review is already enabled,
use its current-head result as the single routine external route instead of starting a duplicate
app run. A replacement or focused rerun needs a reason, such as stale scope or an unresolved finding.
This policy does not enable either route. If neither can run, record `unavailable` and continue the
mandatory independent review without claiming external coverage.

Add a deeper security review when the diff affects authentication, credentials, network/endpoints,
storage/privacy, command execution, dependencies, signing, or CI permissions. Prefer an installed
local Codex Security **Changes** scan scoped to the exact merge-base and head plus supporting code.
GitHub Security Review is a separate, plan-dependent alternative, not an alias for that scan.
Codex Security Cloud repository/commit scans are another separate product and are not the routine
PR route. A full baseline scan is not required on every PR.

Before independent review, inspect external findings privately and give the reviewer their scope,
revision, status, disposition, and safe report references. The reviewer evaluates the actual code,
attack path, and evidence independently, and may disagree with an external finding. Never rename
app output as the custom reviewer report. Do not count silence, absent PR comments, a reporting
threshold, or zero findings as security or runtime acceptance.

## Revision and disposition record

Use a short private record per route. Record base/head, reviewed scope and exclusions, status
(`pending`, `completed`, `failed`, `unavailable`, or `not-applicable`), reason when not completed,
inspected report reference, and finding IDs with `blocking`, `fixed`, `dismissed`, or `out-of-scope`
dispositions and independent rationale. `fixed` requires verified remediation; dismissal needs
supporting evidence. Valid in-scope blockers cannot be relabeled out of scope to merge. Capture
coverage/deferred areas and validation limits from the actual report; do not invent measurements.

Optional private JSON can be checked with:

```bash
python3 -I scripts/verification-policy.py review /private/path/review.json <full-head-sha>
```

The record has exactly these keys: `route`, `base`, `head`, `scope`, `status`, `reason`, `findings`,
`private_report`. Routes are `app-code-review`, `github-code-review`, `local-security`,
`github-security-review`, or `security-cloud`. Both revisions are full commit SHAs. Each finding
has `id`, `disposition`, and `rationale`. An empty findings list means only what the inspected
report and coverage support. The validator checks metadata shape and freshness, not truth,
review completion, authorization, or merge readiness. An unavailable record can be valid metadata
while conveying no review proof. Keep JSON and reports outside the Git worktree.

A new commit expires the prior review result for readiness. Refresh affected checks and reviews
on the new exact head; record an unavailable rerun honestly. Older reports remain historical
context, not acceptance evidence. Public PRs need only the selected route, status, exact head,
and a sanitized disposition summary. Keep private links and vulnerability details in the private
reviewer packet. Posting review-trigger comments or vulnerability reports requires explicit
applicable authorization; normal PR creation authority alone is not that authorization.

## Baseline and threat model

`SECURITY.md` supplies durable repository context. When an authorized, installed local security
plugin is available, prepare a standard **Codebase** scan of a clean exact revision, with deep scan
off. Scope the first baseline to `bridge`, `swift-package`, `samples`, `contracts`, `scripts`, and
`.github`; inspect actual repository paths before starting. Review credentials/endpoints, request
isolation, cancellation/streaming, resource ownership, public APIs, host storage, generated
contracts, packaging, dependencies, and CI. Record exclusions explicitly.

Confirm the target revision, plugin version, selected model/effort, and available allowance before
execution. Evaluate evidence quality, reachable attack paths, and usefulness of remediation without
inventing scores. Inspect threat-model assumptions, reviewed/deferred coverage, validation gaps,
and the completed report. Where the installed version provides them, retain `report.md`,
`scan-manifest.json`, `findings.json`, and `coverage.json` together in private storage. These names
are documented outputs, not a claim that this repository has produced a scan.

A baseline is an explicit one-time assessment. Revisit it after material trust-boundary changes;
this document schedules nothing. Do not install plugins, enable recurring Cloud scans, change
account settings or protection, purchase usage, or publish findings as an incidental implementation
step. Resolve required authorization before those actions. Existing repository gates continue
independently of optional service availability.

## Documentation and activation evidence

Official documentation checked on 2026-10-15:

- [GitHub Code Review](https://learn.chatgpt.com/docs/third-party/github): connected repository and
  review settings; app reviews and GitHub triggers must not be conflated.
- [GitHub Security Review](https://learn.chatgpt.com/docs/security/security-review): Enterprise,
  Business, Edu, and Pro eligibility, not Plus; consumes included Codex allowance or ChatGPT
  credits. Confirm actual workspace eligibility and current allowance before use. GitHub findings
  inherit PR visibility; the full report is in Codex. An existing scan is optional.
- [Local changes scans](https://learn.chatgpt.com/docs/security/plugin/code-changes) and
  [baseline scans](https://learn.chatgpt.com/docs/security/plugin/scans): scope and inspect exact
  revisions, coverage, and reports; a diff scan is not a whole-repository audit.
- [Cloud threat models](https://learn.chatgpt.com/docs/security/threat-model): separately configured
  repository monitoring context; local `SECURITY.md` does not activate Cloud settings.

At rollout, this task exposed a Code Review CI-diagnostics tool and Security Cloud tools, but no
local security scan skill or review-start tool. Plugin discovery did not establish local scan
availability. These observations do not prove account eligibility, repository connection,
automatic-review settings, or successful activation. Activation and baseline results remain
unverified until directly observed and recorded. No account settings are changed by this commit.
