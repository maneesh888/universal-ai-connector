# Universal AI Connector Workflow

## Repository boundary

- Treat `docs/plans/universal-ai-connector-v2.md` as the source of truth for milestone order and status.
- Implement only the active work package. Planning a future package does not activate it.
- Explicitly requested repository maintenance may proceed outside a product milestone with `Milestone effect: none`; it must not introduce future milestone behavior.
- Keep this package independent from OpenKeyboard, application storage/UI, provider DTOs, and Gateway V1 contracts.

## Start with proportional context

1. Resolve the root with `git rev-parse --show-toplevel`, inspect `git status --short --branch`, and preserve unrelated work.
2. Read this file completely.
3. Inspect the roadmap `Status` and `Milestones` sections. Read only the active or requested package sections needed for the task.
4. When the user asks what to do next, invoke the read-only `work-package-planner` custom agent with no inherited conversation; it uses `$plan-universal-ai-work-package` to return a digest-bound form. Verify its source digests before relying on it.
5. Read `docs/DEVELOPMENT_WORKFLOW.md` when selecting verification or changing workflow, build, hook, CI, packaging, or external-tool behavior.
6. Read the complete roadmap and active plan for milestone activation, milestone closeout, Release work, or when targeted sections leave material ambiguity.
7. Install or verify hooks before the first commit or push in an implementation lifecycle; read-only and planning tasks do not run the installer.

A plan is coordination, not another approval gate. Start a clear requested implementation without requiring a separate planning turn.

## Work modes

| Mode | Use | Required agent behavior |
|---|---|---|
| **Fast** | Bounded local implementation | Read targeted context, make one scoped change, run affected tests, and avoid routine delegation. |
| **Standard** | Normal completed implementation or commit | Fast plus `./scripts/check.sh --quick`; when committing, let the mandatory pre-commit hook supply that exact quick gate instead of immediately duplicating it. |
| **Release** | PR readiness, merge, milestone closeout, or explicit release verification | Freeze the candidate, run `./scripts/check.sh --full` on exact `HEAD`, run exact-head CI and independent review, then apply merge gates through `$review-verify-merge-pr`. |

Run only the highest cumulative gate needed for the final state. Repository hooks remain mandatory and may run a higher gate at commit or push; never bypass them. Add screen, simulator/device execution, live-provider, gateway, or distribution proof only when the changed surface or active plan requires it.

## Authority

- Planning, review, status, readiness assessment, and blocker requests are read-only.
- Do not invoke the planner for a clear bounded implementation; planning must not delay requested work.
- A request to implement a bounded repository change starts the normal autonomous lifecycle: create or reuse an appropriate branch/worktree, edit, test, commit, push, create or update a draft pull request, address in-scope findings, mark the verified head ready, and perform the guarded merge when every gate passes.
- Do not request separate confirmations between those normal stages.
- The latest explicit opt-out narrows the lifecycle: `local only`, `do not commit`, `do not push`, `do not create a PR`, `keep draft`, or `do not merge`.
- Opt-outs never create authority for a state change in a planning or review-only task.
- Ask only for a material scope expansion, destructive action, unavailable credential or external dependency, or another action outside the requested repository workflow.

Create new branches with `feature/`, `bugfix/`, `docs/`, `chore/`, or `refactor/` and a concise lowercase kebab-case suffix. Do not rename existing branches solely to adopt the convention.

## Product and proof boundaries

- Keep platform-neutral behavior in Kotlin `commonMain`.
- Keep one Kotlin client with idiomatic `suspend`/`Flow` APIs and one Swift façade with Swift-native models, `async`, `AsyncThrowingStream`, Swift errors, and Swift cancellation.
- Keep Kotlin implementation types, callback plumbing, coroutine scopes, generated Objective-C details, and packaging internals out of supported host APIs.
- Treat Android, iOS, and JVM samples as thin external consumers of public package boundaries.
- Preserve cancellation propagation, exactly-once terminal behavior, concurrency, ownership, and cleanup.
- Keep provider DTOs internal and use deterministic fakes for normal tests until the corresponding provider milestone activates.
- Do not claim platform, device, provider, gateway, distribution, or release proof for a path that did not run successfully.

## Safety and tools

- Preserve unrelated tracked and untracked changes. Use an isolated worktree when a clean exact-head operation would otherwise disturb them.
- Use the Gradle wrapper and repository scripts.
- Never bypass hooks or security scans.
- Never print or commit credentials, authorization headers, generated XCFrameworks, build output, DerivedData, `.xcresult` bundles, or logs.
- Use `gh` for GitHub reads and lifecycle writes. Do not use the GitHub connector unless the user explicitly requests it.
- Keep normal GitHub Actions read-only and secretless; never add `pull_request_target`, merge logic, a write token, PAT, or unattended merger.

## Pull requests

- Create pull requests as drafts.
- Keep the PR description concise and include the problem, scope, requirement sources, verification, proof limits, exact head SHA, and exactly one milestone effect: `none`, `advances`, or `completes`.
- Build the richer reviewer packet only when independent Release review begins.
- Keep milestone-closeout document changes with the implementation/root agent and commit them before final review.
- Route review, readiness, and merge work through `$review-verify-merge-pr`.

## Reporting

For Fast and Standard work, report only the branch/worktree, changed files, executed checks, proof limits, and blockers. Add exact SHA, CI, independent-review, milestone-effect, and merge evidence only for Release or when directly relevant.
