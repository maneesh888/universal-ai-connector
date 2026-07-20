---
name: review-verify-merge-pr
description: Review a GitHub pull request for correctness, architecture, regressions, tests, security, public API and packaging boundaries, and merge readiness; verify the exact PR head locally and against GitHub; and mark ready or merge only when explicitly authorized and every gate passes. Use for requests to review a PR, decide whether a PR is ready, verify PR checks or evidence, mark a draft ready, or merge a PR if clean.
---

# Review, Verify, and Merge a PR

Apply one conservative gate from PR discovery through any authorized merge. Separate independent review from state-changing decisions, and bind every conclusion to the exact head SHA reviewed.

## Preserve the authority boundary

- Treat `review`, `is this ready?`, and `what is blocking this?` as read-only requests.
- Treat `merge`, `merge if clean`, or an equally explicit instruction in the current request as merge authorization. Do not infer it from earlier tasks or general repository ownership.
- Treat `mark ready` as authorization to change draft state only. Do not infer merge authorization from it.
- Keep the independent reviewer read-only. Let only the root agent perform a GitHub state change, and only when the current request explicitly authorizes that specific change.
- Never bypass branch protection, force a merge, use an administrator override, weaken required checks, dismiss valid feedback, or expose credentials.
- Do not implement fixes during a review-only task. Report blockers and wait for a separate implementation request.

## 1. Establish repository and PR context

1. Resolve the repository root and inspect `git status --short --branch`.
2. Read `AGENTS.md`, the roadmap, `docs/DEVELOPMENT_WORKFLOW.md`, and the active work-package plan completely.
3. Resolve the owner, repository, PR number, base branch, head branch, head SHA, draft state, author, changed files, mergeability, reviews, review threads, and check runs with the GitHub connector. Use `gh` only when connector coverage is insufficient.
4. Record the head SHA before reviewing. Never assume the current checkout matches the PR.
5. Preserve dirty or unrelated local work. If the checkout is not a clean copy of the exact PR head, verify in a separate temporary worktree or another isolated checkout; never stash, reset, overwrite, or incorporate user changes.

## 2. Run an independent review

Spawn the project custom agent `pr-reviewer` when it is available. Give it the PR identity and exact head SHA, and require findings-first output tied to files, lines, tests, or observable evidence. If the custom agent is unavailable, perform the same review locally and disclose that independent-agent review was unavailable.

Review the root diff and relevant surrounding code, not only the PR description. Inspect:

- correctness, failure behavior, lifecycle, concurrency, cancellation, and regressions;
- repository architecture, milestone scope, host boundaries, public API, packaging, and compatibility;
- tests for changed behavior, negative paths, and consumer integration;
- secrets, credential handling, permissions, injection paths, logs, dependencies, and unsafe defaults;
- documentation, roadmap status, screenshots, and verification claims for exact factual support; and
- generated artifacts, unrelated changes, and missing migration or cleanup steps.

Classify findings by severity and explain the user-visible or engineering impact. Treat a finding as blocking when it can cause incorrect behavior, a security or data-loss risk, a public-contract or packaging regression, an untested material behavior change, or a materially false readiness or verification claim.

## 3. Verify the exact head proportionally

Check out or fetch the recorded PR head without modifying user work. Run the active plan's required commands and use the repository scripts:

- For documentation, agent, or workflow-only changes, run `./scripts/check.sh --hygiene` plus format or syntax validation specific to the changed files.
- For narrow implementation changes, run affected targeted tests and `./scripts/check.sh --quick`.
- For public API, package boundary, sample, baseline, release, or broadly shared build changes, run affected targeted tests and `./scripts/check.sh --full`.
- Always run `git diff --check` and the repository secret scan when they are not already included by the selected check.

Do not substitute green CI for missing local review or claim proof for an unexecuted simulator, device, live provider, gateway, distribution, or release surface. Record every command and result.

## 4. Apply the merge-readiness gates

Refresh GitHub state after local verification and again immediately before any state change. Start over for a new head if the PR head SHA changed.

Require all of the following:

1. No blocking finding remains from either review pass.
2. The locally verified SHA equals GitHub's current PR head SHA.
3. Every required check has completed successfully, including the stable `Required checks` aggregator when configured. Do not treat pending, cancelled, timed-out, or skipped required checks as success.
4. No requested change or unresolved blocking review thread remains.
5. GitHub reports the PR mergeable, and any required base-update policy is satisfied.
6. The diff stays inside the authorized work package and contains no secret or generated-artifact violation.
7. The PR description and status documentation match the evidence actually obtained.

If any gate fails, leave the PR's state unchanged and report the blocker, evidence, exact head SHA, and next action.

## 5. Perform only authorized state changes

If every gate passes but the request is review-only, report that the exact head is ready and stop.

If draft-state authorization is explicit, refresh the gates and mark the exact clean head ready for review. If merge authorization is also explicit, continue; otherwise stop.

If merge authorization is explicit:

1. Refresh the head SHA, required checks, blocking threads, and mergeability one final time.
2. Use a repository-enabled merge method without bypassing rules. Prefer the repository's configured default; do not rewrite branch history merely to change the merge style.
3. Merge only the reviewed SHA. If GitHub cannot atomically protect the expected head, re-read the PR immediately before the merge and abort on any mismatch.
4. Confirm GitHub reports the PR merged and record the PR URL and resulting merge or squash commit SHA.
5. Inspect the post-merge `main` workflow when it starts. Report a failure clearly; never rewrite shared history to hide it.

## Report the result

Lead with blocking findings, or state that none were found. Include:

- PR number, URL, base, and exact reviewed head SHA;
- independent-review availability and findings;
- local commands and pass/fail results;
- required checks, review-thread state, and mergeability;
- any ready-for-review or merge action performed;
- merge commit and post-merge workflow result when applicable; and
- proof boundaries and residual risks.

Never describe a PR as merged, ready, verified, or production-ready beyond the exact evidence obtained.
