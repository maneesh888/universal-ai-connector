---
name: develop-universal-ai-connector
description: Execute roadmap-driven analysis, planning, implementation, testing, documentation, and status work for Universal AI Connector milestones P0-P12. Use for repository changes involving shared Kotlin behavior, canonical contracts, Apple bridging, Android/JVM/iOS consumers, transport, providers, gateway integration, distribution, release hardening, multimodal inputs, or on-device model backends.
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

1. Keep one compact checkpoint visible in the working conversation: objective and scope, active
   package, current state, next proof gate, and blockers. Refresh it only when that state
   meaningfully changes; do not restate a clear user request.
2. Add or update focused tests when behavior changes.
3. Prefer existing modules, fixtures, Gradle tasks, and repository scripts.
4. Use the Gradle wrapper.
5. Preserve the last accepted host path while changing public API, packaging, lifecycle, or compatibility behavior.
6. Keep deterministic and live proof distinct.
7. Update affected documentation when a public contract, package boundary, compatibility rule, or milestone status changes.
8. Never claim evidence for an unexecuted platform, device, provider, gateway, distribution, or release path.
9. Keep the root README as a stable public product overview. Route routine milestone progress,
   exact-head proof, and closeout chronology to the roadmap, active plan, verification records,
   and pull-request brief; change the README only for user-visible product or consumption changes.
10. For affected Swift or iOS work, use the verified Xcode 27 MCP route when its reads,
    diagnostics, previews, build/test controls, or Simulator controls materially help. Apply the
    evidence levels in `docs/DEVELOPMENT_WORKFLOW.md`: a catalog or read does not prove an
    unexecuted build, test, interaction, or screenshot.
11. Keep MCP test and Simulator activity serialized with other repository/worktree Simulator work.
    Preserve complete repository scripts and their Kotlin, JVM, Android, Swift, packaging, and
    consumer coverage; MCP never replaces a selected repository gate.
12. Before MCP runtime work, discover the current tools and verify the project, scheme, and
    destinations with read-only calls. Validate Device Interaction eligibility separately from
    build/test support, use a task-owned device UUID, read hierarchy before each action, inspect
    direct screenshots, and end the interaction session during cleanup.

The roadmap and active plan own product constraints. Do not copy them into a work order or report; cite the relevant source sections.

## Lifecycle autonomy

A bounded implementation request starts the normal repository lifecycle through guarded merge: branch/worktree preparation, edits, tests, commit, push, draft PR, in-scope review fixes, readiness, and merge. Continue without separate confirmation between those stages.

Before opening a PR or reporting completion, apply the task-completion and delivery boundaries in `AGENTS.md`.

Recompute phase-scoped authority after a completed checkpoint and clear implementation request; preserve active task-wide constraints. Evidence readiness never creates authority.

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

- Run the smallest affected test after each coherent fix, using only the scoped failure output and
  source context needed for the next change.
- Retry a failed command only after a code, configuration, or environment change, or evidence of a
  transient external failure; state the reason instead of rerunning speculatively.
- Run `./scripts/check.sh --hygiene` for a Fast handoff with file changes.
- Run `./scripts/check.sh --quick` for Standard; let the pre-commit hook supply it when committing.
- Use the trusted docs-only selection from `docs/DEVELOPMENT_WORKFLOW.md`; all other Release/push scopes require `./scripts/check.sh --full`. Explicit full always means full.
- Freeze a Release candidate before the full gate and independent review; any head change restarts
  the exact-head cycle.
- Never bypass hooks or security scans.
- Keep screenshot or runtime proof for changed UI; do not collect unrelated UI evidence.
- For MCP-assisted Simulator/runtime acceptance, retain inspected direct screenshots and an
  exact-head evidence record that identifies source revision, Xcode build, scheme, task-owned
  device UUID, operation, and mock-versus-live boundary. Complete independent exact-head review
  and required checks, then stop before merge until the repository owner explicitly approves that exact head.

Create PRs as drafts with a concise brief containing scope, source links, verification, proof limits, exact head SHA, and one milestone effect. Build the detailed reviewer packet only when Release review begins.

For PR review, readiness, merge, or any autonomous implementation reaching those stages, use `$review-verify-merge-pr`.

## Report compactly

For Fast and Standard, report:

- branch/worktree;
- changed files or modules;
- checks and results;
- proof limits or blockers.

For Release, add exact SHA, required CI, independent-review result, milestone effect, merge result, and any required post-merge assertion.
