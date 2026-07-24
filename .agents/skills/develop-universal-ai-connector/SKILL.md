---
name: develop-universal-ai-connector
description: Execute roadmap-driven analysis, planning, implementation, testing, documentation, and status work for Universal AI Connector milestones P0-P9. Use for repository changes involving shared Kotlin behavior, canonical contracts, Apple bridging, Android/JVM/iOS consumers, transport, providers, gateway integration, distribution, or release hardening.
---

# Develop Universal AI Connector

Work on one bounded package while preserving the repository's accepted host and safety boundaries.

## Establish only needed context

1. Resolve the repository root and inspect `git status --short --branch`.
2. Read `AGENTS.md`.
3. Inspect the roadmap `Status` and `Milestones` sections.
4. If the user requests planning or asks what is next, invoke the read-only `work-package-planner` custom agent without inherited conversation; it uses `$plan-universal-ai-work-package`.
5. Otherwise read only the active/requested work-package, acceptance, verification, and proof-limit sections needed for the change.
6. Read complete plans for activation, closeout, Release, or unresolved ambiguity.
7. Read `docs/DEVELOPMENT_WORKFLOW.md` when choosing checks or changing workflow, build, hook, CI, packaging, or tool behavior.

Preserve unrelated work. Use an isolated worktree when exact-head operations would disturb a dirty checkout.

## Select the mode

- **Fast:** focused local change and affected tests.
- **Standard:** completed implementation plus the quick gate.
- **Release:** exact-head full gate, CI, independent review, and guarded merge.

Use the highest mode required by the requested outcome or changed surface. Mandatory hooks may execute a higher cumulative gate without requiring Release-level analysis.

## Execute

1. Confirm the objective, active package, affected surfaces, out-of-scope behavior, selected mode, and proof boundaries. Keep this as a compact internal work order; do not restate a clear user request.
2. Add or update focused tests when behavior changes.
3. Prefer existing modules, fixtures, Gradle tasks, and repository scripts.
4. Use the Gradle wrapper.
5. Preserve the last accepted host path while changing public API, packaging, lifecycle, or compatibility behavior.
6. Keep deterministic and live proof distinct.
7. Update affected documentation when a public contract, package boundary, compatibility rule, or milestone status changes.
8. Never claim evidence for an unexecuted platform, device, provider, gateway, distribution, or release path.

The roadmap and active plan own product constraints. Do not copy them into a work order or report; cite the relevant source sections.

## Lifecycle autonomy

A bounded implementation request starts the normal repository lifecycle through guarded merge: branch/worktree preparation, edits, tests, commit, push, draft PR, in-scope review fixes, readiness, and merge. Continue without separate confirmation between those stages.

Honor the latest explicit opt-out:

- `local only`
- `do not commit`
- `do not push`
- `do not create a PR`
- `keep draft` or `do not mark ready`
- `do not merge`

Planning, review, status, readiness assessment, and blocker requests remain read-only. Ask only when work would materially expand scope, perform a destructive action, require unavailable credentials/external dependencies, or leave the requested repository workflow.

Use `gh` for GitHub operations and `gh api graphql` for thread state. Do not switch to the GitHub connector unless the user explicitly requests it.

## Verify and publish

- Run affected tests during iteration.
- Run `./scripts/check.sh --hygiene` for a Fast handoff with file changes.
- Run `./scripts/check.sh --quick` for Standard; let the pre-commit hook supply it when committing.
- Run `./scripts/check.sh --full` for Release and every mandatory push hook.
- Never bypass hooks or security scans.

Create PRs as drafts with a concise brief containing scope, source links, verification, proof limits, exact head SHA, and one milestone effect. Build the detailed reviewer packet only when Release review begins.

For PR review, readiness, merge, or any autonomous implementation reaching those stages, use `$review-verify-merge-pr`.

## Report compactly

For Fast and Standard, report:

- branch/worktree;
- changed files or modules;
- checks and results;
- proof limits or blockers.

For Release, add exact SHA, required CI, independent-review result, milestone effect, merge result, and any required post-merge assertion.
