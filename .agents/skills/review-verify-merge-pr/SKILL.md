---
name: review-verify-merge-pr
description: Review Universal AI Connector pull requests for correctness, architecture, regressions, tests, security, public API and packaging boundaries, and merge readiness. Use for read-only PR review and for exact-head Release verification, readiness, and guarded merge; honor explicit draft or merge opt-outs.
---

# Review, Verify, and Safely Merge a PR

Bind every conclusion and state change to one exact PR head.

## Authority

- Review, readiness assessment, and blocker requests are read-only.
- A bounded implementation request starts the normal autonomous repository lifecycle through guarded merge.
- Only the root agent performs commits, pushes, PR updates, readiness, and merge actions.
- The independent reviewer is always read-only.
- Honor the latest explicit `local only`, `do not commit`, `do not push`, `do not create a PR`, `keep draft`, or `do not merge` instruction.
- Do not request another confirmation between requested lifecycle stages.
- Stop for a material scope expansion, destructive action, unavailable credential/external dependency, or a failed mandatory gate.

Never bypass branch protection, hooks, scanners, or required checks; never use `--admin`, force a merge, dismiss valid feedback, expose credentials, or add an unattended merger.

## Establish the exact target

1. Resolve the repository and inspect the worktree.
2. Use `gh` to resolve the PR, base, head SHA, draft state, changed files, mergeability, reviews, threads, and checks.
3. Preserve unrelated local work. Use an isolated checkout when the current worktree is not the clean exact head.
4. Read `AGENTS.md`, the PR brief, the root diff, and only requirement/acceptance sections relevant to the changed surface.
5. Read the complete roadmap and active plan only for milestone completion, activation, Release claims, or material ambiguity.

Do not use the GitHub connector unless the user explicitly requests it.

## Prepare review context just in time

Keep the PR description concise and current:

- problem and change summary;
- scope and key decisions;
- durable requirement sources plus any request-only delta;
- exactly one milestone effect: `none`, `advances`, or `completes`;
- verification summary;
- proof limits; and
- full head SHA.

Immediately before independent review, assemble a neutral packet from the request, linked sources, PR brief, root diff, evidence, and exact SHA. Include requirements, observable acceptance criteria, decisions, out-of-scope behavior, status-document consequences, and proof boundaries. Do not include expected findings or a desired conclusion.

For `completes`, require consistent proposed roadmap, active-plan, affected README, and other required closeout updates in the candidate before final review. Merge makes that proposed transition authoritative.

## Review and verify

1. Spawn the project `pr-reviewer` with no inherited conversation when available; pass only the PR identity, exact SHA, reviewer packet, and source paths.
2. Start independent review and GitHub checks concurrently.
3. Inspect correctness, failure behavior, lifecycle, concurrency, cancellation, architecture, milestone scope, public API, packaging, compatibility, tests, security, dependencies, documentation claims, generated artifacts, and unrelated changes.
4. Treat correctness, security, data-loss, public-contract, packaging, missing-material-test, and false-evidence findings as blockers.
5. Run `./scripts/check.sh --full` on the exact Release head. Run additional active-plan proof only when applicable.
6. Confirm required security tools and regressions actually executed without revealing matched material.

Review-only work reports findings and stops. During an autonomous implementation lifecycle, fix in-scope blockers while the PR remains draft. Any head change invalidates local Release evidence, the reviewer result, and GitHub gate conclusions. If the PR had already become ready, immediately disable any auto-merge request and return it to draft, including after a `do not merge` hold or a queued merge was disabled. Refresh the PR brief and repeat the exact-head cycle before readiness; in review-only work, report the required recovery without changing state.

## Readiness gate

Before readiness, require:

1. current PR brief and reviewer packet bound to the exact head;
2. milestone effect consistent with the diff and status sources;
3. independently reviewed SHA equal to GitHub's head;
4. successful full local verification for that SHA;
5. no blocking finding, requested change, or unresolved thread;
6. in-scope diff with no secret or generated-artifact violation;
7. no conflict or unsatisfied base-update policy;
8. successful exact-head required checks, including `Required checks` and applicable protected live verification; and
9. effective base protection requiring PRs, strict required checks, conversation resolution, administrator enforcement without bypass, and prohibiting force pushes and deletion.

Verify protection through GitHub APIs and confirm `gh pr checks <number> --required`. Pending, skipped, missing, cancelled, timed-out, or failed mandatory checks block readiness and merge.

## Guarded merge

If the latest instruction says `keep draft`, leave the PR draft. If it says `do not merge`, a clean head may become ready but must remain unmerged.

Otherwise:

1. Refresh the head, checks, reviews, threads, protection, mergeability, scope, and latest user instruction.
2. Confirm the head remains the exact reviewed and locally verified SHA.
3. Mark the draft ready.
4. Refresh the same state once more. On any head, gate, protection, mergeability, scope, or review mismatch, disable any queued auto-merge, return the PR to draft when applicable, and restart the exact-head cycle or report the blocker. On a late `keep draft`, disable any queued auto-merge, run `gh pr ready <number> --undo`, verify draft and unmerged, and stop. On a late `do not merge`, disable any queued auto-merge, verify unmerged, and stop; the PR may remain ready.
5. Run:

   ```bash
   gh pr merge <number> --auto --squash --match-head-commit <reviewed-head-sha>
   ```

6. Inspect PR state immediately. If GitHub queues auto-merge instead of merging, disable it. Apply a late `keep draft` by returning the PR to draft; otherwise leave its current draft/readiness state. Verify unmerged and report the blocker.
7. If the head changes before completion, disable auto-merge, return the PR to draft when applicable, and restart the exact-head cycle.

Never leave queued auto-merge active.

## After merge

Record the PR URL and squash commit. Inspect resulting `main` CI and reread remote status sources when the change completes or activates a milestone, changes verification infrastructure, or makes another claim dependent on resulting `main`. Report inconsistent closeout without creating an unrequested follow-up.

Lead the final report with blockers or state that none remain. Include exact head, local verification, independent review, required checks, threads, protection, mergeability, action taken, milestone effect, and proof limits.
