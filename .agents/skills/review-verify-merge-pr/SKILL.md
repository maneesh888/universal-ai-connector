---
name: review-verify-merge-pr
description: Review a draft GitHub pull request for correctness, architecture, regressions, tests, security, public API and packaging boundaries, and merge readiness; verify the exact PR head locally and against GitHub; and mark ready or enable review-gated native auto-merge only when explicitly authorized and every gate passes. Use for requests to review a PR, decide whether a PR is ready, verify PR checks or evidence, mark a draft ready, or enable auto-merge for a clean PR.
---

# Review, Verify, and Enable Auto-Merge for a PR

Apply one conservative gate from PR discovery through any authorized merge. Separate independent review from state-changing decisions, and bind every conclusion to the exact head SHA reviewed.

## Preserve the authority boundary

- Treat `review`, `is this ready?`, and `what is blocking this?` as read-only requests.
- Treat `merge`, `merge if clean`, or an equally explicit instruction in the current request as merge authorization. Do not infer it from earlier tasks or general repository ownership.
- Treat `mark ready` as authorization to change draft state only after every review-completion gate passes. Do not infer merge authorization from it.
- Treat creating or updating a pull request as authorization to invoke independent read-only review of its published exact head immediately, but not as authorization to change draft state or enable auto-merge.
- Allow one current request to bundle draft creation or update, review, in-scope finding fixes, readiness, and auto-merge authorization. When the authorization is explicit, continue the same active task through those gates without requesting a second confirmation.
- Keep the independent reviewer read-only. Let only the root agent perform a GitHub state change, and only when the current request explicitly authorizes that specific change.
- Create pull requests as drafts and run GitHub Actions while they remain drafts. Do not mark a pull request ready merely to start CI.
- Never bypass branch protection, force a merge, use `--admin` or another administrator override, weaken required checks, dismiss valid feedback, add an autonomous merger, or expose credentials.
- Do not implement fixes during a review-only task. Report blockers and wait for a separate implementation request.

## 1. Establish repository and PR context

1. Resolve the repository root and inspect `git status --short --branch`.
2. Read `AGENTS.md`, the roadmap, `docs/DEVELOPMENT_WORKFLOW.md`, and the active work-package plan completely.
3. Resolve the owner, repository, PR number, base branch, head branch, head SHA, draft state, author, changed files, mergeability, reviews, review threads, and check runs with `gh`. Prefer `gh pr view`, `gh pr diff`, `gh pr checks`, `gh run view`, and `gh api graphql` as appropriate.
4. Record the head SHA before reviewing. Never assume the current checkout matches the PR.
5. Preserve dirty or unrelated local work. If the checkout is not a clean copy of the exact PR head, verify in a separate temporary worktree or another isolated checkout; never stash, reset, overwrite, or incorporate user changes.

Use `gh` for every GitHub read and authorized state change. Do not use the GitHub connector unless the user explicitly requests it. If `gh` is unavailable or unauthenticated, report the blocker instead of silently switching tools.

## 2. Establish the review brief

Fetch the PR description and linked issues or plans with `gh`. Build a neutral review brief from those durable sources and the current implementation conversation when it is available. Distinguish documented requirements from assumptions or inferences.

Require the brief to contain:

- the problem being solved;
- requirement sources, including issue, plan, or user-request references;
- requirements and observable acceptance criteria;
- important implementation decisions and constraints;
- explicitly out-of-scope behavior;
- verification evidence and exact proof boundaries; and
- the exact PR head SHA the brief describes.

Record the brief in the PR description before independent review. Updating the description is a GitHub state change and requires explicit authorization; if authorization is absent, report the missing brief as a blocker rather than editing it. Refresh the brief whenever the requirements, scope, evidence, or head SHA materially changes.

Pass the independent reviewer the brief and its source material. Keep the handoff factual: do not include expected findings, tell the reviewer that the change is correct, or hide unresolved decisions.

## 3. Run an independent review

When the root agent created or updated the draft in the current task, it must immediately continue into this section after publishing the current review brief. Do not end the task at draft creation and do not wait for GitHub Actions to finish; independent review and required checks run in parallel.

Spawn the project custom agent `pr-reviewer` when it is available. Give it the current structured review brief, its source links, the PR identity, and the exact head SHA, and require findings-first output tied to files, lines, tests, or observable evidence. The root agent that created or updated the draft remains responsible for receiving the result and continuing the same task through every authorized gate. If the custom agent is unavailable, perform the same review locally and disclose that independent-agent review was unavailable.

Review the root diff and relevant surrounding code, not only the PR description. Inspect:

- correctness, failure behavior, lifecycle, concurrency, cancellation, and regressions;
- repository architecture, milestone scope, host boundaries, public API, packaging, and compatibility;
- tests for changed behavior, negative paths, and consumer integration;
- secrets, credential handling, permissions, injection paths, logs, dependencies, and unsafe defaults;
- documentation, roadmap status, screenshots, and verification claims for exact factual support; and
- generated artifacts, unrelated changes, and missing migration or cleanup steps.

Classify findings by severity and explain the user-visible or engineering impact. Treat a finding as blocking when it can cause incorrect behavior, a security or data-loss risk, a public-contract or packaging regression, an untested material behavior change, or a materially false readiness or verification claim.

## 4. Verify the exact head completely

Check out or fetch the recorded PR head without modifying user work. Run the active plan's required commands and use the repository scripts:

- Run `./scripts/check.sh --full` for every pull request head, including documentation-only and workflow-only changes. Do not reduce this gate based on diff size.
- Run additional targeted tests when the active work package adds a proof surface not yet included in the full suite.
- Treat the exact-head verification as blocked when the required toolchain is missing or any check cannot run; do not substitute a narrower command.
- `--full` includes shell syntax validation, the repository secret scan, and whitespace checks.

Do not substitute green CI for missing local review or claim proof for an unexecuted simulator, device, live provider, gateway, distribution, or release surface. Record every command and result.

## 5. Apply the review-completion and auto-merge enrollment gates

Refresh GitHub state after local verification and again immediately before any state change. Fix every blocking finding under separate implementation authorization while the pull request remains a draft. Any head change, including a finding fix, invalidates the previous review, verification, and merge attempt: disable auto-merge when enabled, return the pull request to draft when necessary, refresh the brief, and restart the entire gate for the new SHA.

Require all of the following:

1. The PR description contains a complete, current review brief, including the exact head SHA, and its requirements and status claims match the evidence actually obtained.
2. The independently reviewed SHA equals GitHub's current PR head SHA.
3. Local verification passed for that exact SHA.
4. No blocking finding, requested change, or unresolved review thread remains.
5. GitHub reports no merge conflict, and any required base-update policy is satisfied. A blocked or unstable merge state caused only by pending required checks is allowed at this stage.
6. The diff stays inside the authorized work package and contains no secret or generated-artifact violation.
7. Required checks have been inspected and none has failed, been cancelled, timed out, or been skipped. Pending or in-progress checks are allowed; an unsuccessful terminal result blocks enrollment until corrected or successfully rerun.

Required checks are native auto-merge completion gates, not prerequisites for leaving draft. After enrollment, GitHub may merge only when every required check has completed successfully, including the stable `Required checks` aggregator and any applicable protected live-verification status, and every branch-protection requirement remains satisfied.

If any gate fails, leave the PR's state unchanged and report the blocker, evidence, exact head SHA, and next action.

## 6. Perform only authorized state changes

Apply authorization already stated in the current create-or-update request; do not require the user to repeat an explicit bundled permission after review completes. A create-or-update request without readiness or merge language still authorizes review only.

If every review-completion gate passes but the request is review-only, report that the exact head completed review, distinguish pending required checks from successful ones, and stop.

If draft-state authorization is explicit, refresh the gates and mark the exact clean head ready for review. If merge authorization is not explicit, stop without enabling auto-merge.

If merge authorization is explicit:

1. Refresh the head SHA, required checks, requested changes, unresolved review threads, and mergeability one final time.
2. Confirm the refreshed head is the independently reviewed and locally verified SHA, every review-completion gate still passes, and no required check has completed unsuccessfully. Do not wait for pending or in-progress required checks.
3. Mark the draft ready if it is still a draft.
4. Refresh the head SHA, required checks, requested changes, unresolved review threads, and mergeability again immediately before enabling auto-merge. Abort on any mismatch or failed gate.
5. Enable GitHub native auto-merge with squash and exact-head protection:

   ```bash
   gh pr merge <number> --auto --squash --match-head-commit <reviewed-head-sha>
   ```

6. Confirm and report whether auto-merge is waiting for required checks or GitHub has already completed the merge. Do not claim the pull request is merged while it is still queued.
7. If a required check fails after enrollment, confirm GitHub did not merge and report the blocker. Any fix that changes the head requires the reset in the next step.
8. If the head changes before the merge completes, disable the prior attempt with `gh pr merge <number> --disable-auto` when applicable, return the pull request to draft with `gh pr ready <number> --undo` when applicable, refresh the review brief, and restart local verification, independent review, and every gate for the new SHA.
9. After GitHub merges the pull request, record the PR URL and resulting squash commit SHA, inspect the workflow run for the resulting `main` commit, and report its result. Never rewrite shared history to hide a failure.

Keep GitHub Actions read-only. Do not add an autonomous merger, write token, PAT, or merge logic to `ci.yml`; native auto-merge and branch protection remain GitHub's enforcement boundary.

## Report the result

Lead with blocking findings, or state that none were found. Include:

- PR number, URL, base, and exact reviewed head SHA;
- review-brief sources, completeness, and any assumptions;
- independent-review availability and findings;
- local commands and pass/fail results;
- required checks, review-thread state, and mergeability;
- any ready-for-review or native auto-merge action performed, including whether auto-merge is pending or complete;
- merge commit and post-merge workflow result when applicable; and
- proof boundaries and residual risks.

Never describe a PR as merged, ready, verified, or production-ready beyond the exact evidence obtained.
