---
name: review-verify-merge-pr
description: Review a draft GitHub pull request for correctness, architecture, regressions, tests, security, public API and packaging boundaries, and merge readiness; verify the exact PR head locally and against GitHub; and carry an authorized implementation PR through readiness and a guarded native squash merge by default when every gate succeeds. Keep review-only requests read-only and honor explicit draft or merge opt-outs.
---

# Review, Verify, and Safely Merge a PR

Apply one conservative gate from PR discovery through any authorized merge. Separate independent review from state-changing decisions, and bind every conclusion to the exact head SHA reviewed.

## Preserve the authority boundary

- Treat `review`, `is this ready?`, and `what is blocking this?` as read-only requests.
- Start the default implementation-PR lifecycle only when the current request authorizes both implementing changes and creating or updating the resulting pull request. A request for local implementation alone does not authorize a commit, push, pull request, readiness change, or merge.
- Treat that implementation-and-PR authorization as conditional authorization for the root agent to commit and push the in-scope work, maintain the review brief, invoke independent review, fix in-scope findings, mark the clean reviewed head ready, and make the guarded native squash-merge attempt after every gate succeeds. Do not request a second confirmation between those stages.
- Let the latest user instruction narrow or replace that default. Within an already-authorized implementation-PR lifecycle, `keep draft`, `remain draft`, or `do not mark ready` blocks both readiness and merge, while `do not merge` permits readiness after all gates pass but blocks the merge command. These opt-outs never create state-change authority in a review-only task. An instruction merely to create the pull request as a draft is the required starting state, not a keep-draft opt-out.
- Treat an explicit `mark ready` request for an existing pull request as readiness-only authorization, and an explicit `merge` or `merge if clean` request as authorization for the same guarded readiness-and-merge path.
- Keep the independent reviewer read-only. Only the root agent performs GitHub state changes under the applicable implementation-PR lifecycle or explicit state-change request.
- Create pull requests as drafts and run GitHub Actions while they remain drafts. Do not mark a pull request ready merely to start CI.
- Never bypass branch protection, force a merge, use `--admin` or another administrator override, weaken required checks, dismiss valid feedback, add a privileged CI-side or unattended background merger, or expose credentials.
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

Record the brief in the PR description before independent review. Maintaining that description is part of the authorized implementation-PR lifecycle or an explicit PR-update request. During a review-only task, report a missing or stale brief as a blocker rather than editing it. Refresh the brief whenever the requirements, scope, evidence, or head SHA materially changes.

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
- Inspect local and GitHub logs to confirm every mandatory security tool and its ignore/configuration-bypass regressions actually executed without exposing credential material. A green job whose scanner or other required dependency was missing, whose inputs could be suppressed by ignore or configuration rules, or whose diagnostics leaked a matched secret is failed evidence even when the job conclusion is `success`.

Do not substitute green CI for missing local review or claim proof for an unexecuted simulator, device, live provider, gateway, distribution, or release surface. Record every command and result.

## 5. Apply the review-completion, readiness, and merge gates

Refresh GitHub state after local verification and again immediately before any state change. During an authorized implementation-PR lifecycle, fix every in-scope blocking finding while the pull request remains a draft. A review-only task or a finding that materially expands the authorized work package requires a separate implementation request. Any head change, including a finding fix, invalidates the previous review, verification, and merge attempt: disable any auto-merge request, return the pull request to draft when necessary, refresh the brief, and restart the entire gate for the new SHA.

Require all of the following:

1. The PR description contains a complete, current review brief, including the exact head SHA, and its requirements and status claims match the evidence actually obtained.
2. The independently reviewed SHA equals GitHub's current PR head SHA.
3. Local verification passed for that exact SHA.
4. No blocking finding, requested change, or unresolved review thread remains.
5. GitHub reports no merge conflict, and every required base-update policy is satisfied.
6. The diff stays inside the authorized work package and contains no secret or generated-artifact violation.
7. Every mandatory local and GitHub check has completed successfully for the exact head, including the stable `Required checks` aggregator and any applicable protected live-verification status. Inspect logs for missing-tool or skipped-proof failures instead of trusting conclusions alone. Pending, in-progress, failed, cancelled, timed-out, skipped, missing, or invalidly executed mandatory checks block both readiness and a merge attempt.
8. The base branch requires changes through a pull request and is protected through GitHub with strict `Required checks` enforcement, required conversation resolution, administrator enforcement without a bypass, and force pushes and deletion disabled. When a protected live-verification status is applicable, require it directly or through a server-enforced required-check dependency. Verify those values with the GitHub APIs, then confirm `gh pr checks <number> --required` reports every applicable successful required status.

If any gate fails, leave the PR's state unchanged and report the blocker, evidence, exact head SHA, and next action.

## 6. Follow the authorized lifecycle and its latest override

If the request is review-only, report the exact-head review result and every incomplete, failed, or satisfied readiness gate, then stop.

Re-evaluate the latest user instruction immediately before readiness and again immediately before the merge command. An opt-out narrows an existing lifecycle; it never authorizes a state change by itself. If a keep-draft opt-out arrives after the pull request became ready, disable any auto-merge request, return it to draft with `gh pr ready <number> --undo`, and verify that it is draft and unmerged. If a do-not-merge instruction arrives after readiness, disable any auto-merge request and verify that the pull request remains unmerged.

If the latest instruction says to keep the pull request in draft or not mark it ready, ensure it is draft, do not invoke the merge command, and report the exact-head review and gate results.

Within an already-authorized implementation-PR lifecycle, if the latest instruction says not to merge, refresh every gate and mark the exact clean head ready only after they all pass, then stop without invoking a merge command. Separately, an explicit readiness-only request for an existing pull request authorizes that same readiness step but not a merge.

For an authorized implementation-PR lifecycle without an applicable opt-out, or an explicit request to merge an existing pull request:

1. Keep the pull request in draft and wait until every mandatory local and GitHub check has completed successfully for the exact reviewed head.
2. Refresh the head SHA, required checks, review decision, requested changes, unresolved review threads, branch protection, mergeability, scope, secret scan, generated-artifact state, and latest user instruction one final time.
3. Confirm the refreshed head is the independently reviewed and locally verified SHA and every gate still passes.
4. Mark the draft ready if it is still a draft.
5. Immediately refresh the head SHA, every GitHub gate, and the latest user instruction again. Apply any new opt-out before continuing, and abort on any mismatch or failed gate.
6. Invoke GitHub's native auto-merge command as a guarded immediate squash-merge attempt:

   ```bash
   gh pr merge <number> --auto --squash --match-head-commit <reviewed-head-sha>
   ```

7. Treat `--match-head-commit` only as a command-time head precondition. It does not bind or cancel an auto-merge request after the command returns and is not durable protection against a later head update.
8. Because every gate was already green, expect the pull request to merge immediately and inspect its state at once. If GitHub leaves it open with auto-merge queued, immediately run `gh pr merge <number> --disable-auto`, verify auto-merge is disabled and the pull request remains unmerged, and report the blocker. Do not leave the request queued, wait for it to merge later, or retry without re-establishing every gate.
9. If the head changes before the merge completes, disable any auto-merge request, return the pull request to draft with `gh pr ready <number> --undo` when applicable, refresh the review brief, and restart local verification, independent review, and every gate for the new SHA.
10. After GitHub merges the pull request, record the PR URL and resulting squash commit SHA, inspect the workflow run for the resulting `main` commit, and report its result. Never rewrite shared history to hide a failure.

Keep normal GitHub Actions read-only and secretless. Do not add a privileged CI-side or unattended background merger, write token, PAT, merge logic, or `pull_request_target` to `ci.yml`; native merge controls and branch protection remain GitHub's enforcement boundary.

## Report the result

Lead with blocking findings, or state that none were found. Include:

- PR number, URL, base, and exact reviewed head SHA;
- review-brief sources, completeness, and any assumptions;
- independent-review availability and findings;
- local commands and pass/fail results;
- required checks, review-thread state, and mergeability;
- any ready-for-review or guarded merge action performed, including a completed merge or an unexpected queued request that was disabled as a blocker;
- merge commit and post-merge workflow result when applicable; and
- proof boundaries and residual risks.

Never describe a PR as merged, ready, verified, or production-ready beyond the exact evidence obtained.
