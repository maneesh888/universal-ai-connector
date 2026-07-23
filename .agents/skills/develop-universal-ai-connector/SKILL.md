---
name: develop-universal-ai-connector
description: Execute roadmap-driven development for the Universal AI Connector Kotlin Multiplatform repository. Use when Codex is asked to analyze, plan, implement, test, review, document, continue, or report status on connector milestones P0-P9, including shared Kotlin behavior, Swift bridging and XCFramework delivery, iOS/Android/JVM samples, canonical AI contracts, HTTP transport, provider adapters, gateway integration, Apple distribution, or release hardening.
---

# Develop Universal AI Connector

Advance the independent Universal AI Connector repository one bounded, verified work package at a time. Treat repository plans and test evidence as the source of truth.

## Establish Repository Context

1. Resolve the repository root with `git rev-parse --show-toplevel` and work only from that checkout or its task worktree.
2. Inspect `git status --short --branch` before making changes.
3. Run `./scripts/install-hooks.sh` and verify the local `core.hooksPath` is `.githooks`.
4. Read the root `AGENTS.md` completely and follow it as the repository's always-on policy.
5. Read `docs/plans/universal-ai-connector-v2.md` completely. Treat it as the source of truth for milestone order and status.
6. Read the plan named by the active milestone. For P1, read `docs/plans/cross-platform-client-samples.md` completely.
7. Read `docs/DEVELOPMENT_WORKFLOW.md` before changing build, verification, CI, hooks, or external-tool behavior.
8. Preserve unrelated tracked and untracked changes. Do not incorporate, revert, stage, or commit them unless the user explicitly assigns them to the task.

Do not apply OpenKeyboard's application workflow to this independent package. Use OpenKeyboard only when a later integration milestone explicitly activates it.

## Select One Work Package

Use the milestone explicitly requested by the user. If none is specified, select the single milestone marked `In progress`; otherwise select the first incomplete milestone in the roadmap.

Before editing, state a bounded internal work order containing:

- objective and active milestone;
- likely files and modules;
- behavior that remains out of scope;
- deterministic verification required;
- whether platform, simulator, device, or live proof is required;
- whether commit or push permission has been granted.

Implement only one bounded work package unless the user authorizes a larger batch. Keep at most one roadmap milestone marked `In progress`.

The established baseline is P0: Swift can consume the Kotlin/Native framework through the local Swift Package with async response, streaming, stable errors, and cancellation. Preserve that path until its replacement has equivalent passing tests. P1 is the next package unless the roadmap records a later state.

## Preserve Product and Architecture Boundaries

- Keep the connector independent from OpenKeyboard, SwiftUI application state, App Groups, Keychain storage, keyboard actions, and Gateway V1 DTOs.
- Keep platform-neutral behavior in Kotlin `commonMain`.
- Keep iOS, Android, and JVM samples thin; demonstrate the shared client contract without duplicating connector behavior.
- Treat samples as external consumers. They must use the packaged module or artifact boundary rather than internal source sets or implementation packages.
- Provide one Kotlin client entry point with idiomatic `suspend` and `Flow` APIs for Android and JVM, plus one idiomatic Swift façade using `async`, `AsyncThrowingStream`, Swift errors, and Swift cancellation.
- Keep the default integration path small and usable without custom transport construction. Add advanced transport injection when P3 is active, without making it mandatory for first use.
- Keep Kotlin `Flow`, coroutine types, and implementation types behind the Swift callback bridge. Expose the supported Apple API through the Swift facade.
- Keep provider DTOs internal to their adapter modules. Do not expose vendor-specific types through canonical public APIs.
- Use deterministic fake implementations and mock transports for normal tests and samples.
- Do not introduce networking, credentials, provider adapters, gateway behavior, or release infrastructure before the corresponding roadmap milestone is active.
- Never print or commit credentials, authorization headers, generated frameworks, build directories, DerivedData, `.xcresult` bundles, or raw logs.

## Execute the Milestone

1. Add or update focused tests with the implementation when behavior changes.
2. Prefer existing modules, fixtures, Gradle tasks, and repository scripts over parallel abstractions or hand-written command sequences.
3. Use the Gradle wrapper rather than a system Gradle installation.
4. Keep the last verified path intact while migrating structure or packaging.
5. Separate deterministic proof from opt-in live-provider proof. Never present mock results as evidence that a real provider or gateway works.
6. Record public API, contract, packaging, or compatibility changes in the appropriate plan or documentation.
7. Verify host integration through consumer samples that compile against documented package boundaries. A library-unit-test pass alone is not consumer integration proof.
8. Select exactly one PR review-brief declaration: `Milestone effect: none`, `Milestone effect: advances`, or `Milestone effect: completes`.
9. Do not treat a milestone as completed without acceptance evidence. For `completes`, after the implementation acceptance evidence exists, keep the PR in draft and make the implementation/root agent add the proposed roadmap transition, active work-package status and evidence, README public status and next package when affected, and every other repository status document required by the active plan. Commit all closeout documentation on the candidate branch before final independent review and before marking the PR ready. Final exact-head review and checks validate the proposed transition, which becomes authoritative only when that candidate merges. Identify the next milestone without activating it prematurely.
10. Keep the exact candidate SHA, check runs, independent-review result, and other self-referential evidence in the PR review brief, where the root agent can refresh it without changing the candidate. Do not require a merge SHA or final `main` CI run ID in repository documentation when adding it would create another commit and invalidate the reviewed evidence.
11. Before requesting independent review, keep the PR description as a concise, durable brief proportional to the change and normally about 20-40 lines. Include the problem and change summary, scope and key decisions, links to requirement sources plus concise deltas for requirements that exist only in the implementation conversation, the selected milestone effect, a verification summary, proof limits and out-of-scope behavior, and the exact head SHA. Do not duplicate full plan text, raw logs, test-by-test transcripts, or review history.
12. Separately, the root agent must assemble and pass the independent reviewer a richer neutral, structured packet from durable requirement sources, the current implementation conversation, the root diff, and exact verification evidence. Cover the problem, requirement sources, requirements and observable acceptance criteria, important decisions and constraints, milestone effect and required status-document consequences, out-of-scope behavior, verification evidence and proof boundaries, and exact head SHA. Missing, ambiguous, stale, or inconsistent material context blocks readiness; the concise PR description need not repeat information available in a linked durable source. Refresh both the description and packet whenever material context or the head SHA changes.

## Route Optional Development Tools

- Use the `gh` CLI for remote repository context, pull requests, issues, reviews, workflow results, job logs, artifacts, and authorized writes. Use `gh api graphql` for review-thread or other GraphQL-only state.
- Do not use the GitHub connector unless the user explicitly requests it. If `gh` is unavailable or unauthenticated, report the blocker instead of silently switching tools. Request explicit authorization before remote writes. A request that authorizes both implementation and creation or update of the resulting pull request supplies that remote-write authorization and activates the repository's default gated review, readiness, and guarded-merge lifecycle unless the latest user instruction narrows it.
- Use the OpenAI Developer Docs MCP during P4 and later OpenAI contract work. Fetch current primary documentation instead of relying on model memory.
- Use an available Xcode, simulator, or device MCP for exact Apple lifecycle and visual proof. Fall back to repository scripts when it is unavailable and state the missing proof surface.
- Use browser tooling only for future web samples or published documentation.
- Never make MCP availability a runtime dependency of the connector and never place MCP credentials in the repository.

## Enforce Local Verification

Use the repository check modes as mandatory cumulative gates:

- Iteration hygiene: `./scripts/check.sh --hygiene`
- Every commit: `./scripts/check.sh --quick`
- Every push and pull-request creation or update: `./scripts/check.sh --full`

The pre-commit hook rejects unstaged and untracked files before running the quick gate. The pre-push hook rejects refs that do not resolve to the checked-out `HEAD` and requires a clean worktree before and after the full gate. Never bypass either hook. A missing toolchain, unavailable platform dependency, or failed check blocks the commit or push until fixed.

Use narrower commands during iteration:

- Kotlin iOS bridge tests: `./gradlew :bridge:iosSimulatorArm64Test`
- Shared JVM tests: `./gradlew :bridge:jvmTest`
- Android host tests: `./gradlew :bridge:testAndroidHostTest`
- Android AAR packaging: `./gradlew :bridge:bundleAndroidMainAar`
- JVM console consumer: `./gradlew :samples:jvm-console:consumerCheck`
- Android application consumer: `./gradlew :samples:android:consumerCheck`
- Android emulator/device application: `./scripts/run-android-sample.sh`
- XCFramework assembly: `./scripts/build-xcframework.sh`
- Swift Package tests: `./scripts/test-swift-package.sh`
- iOS sample build: `./scripts/build-sample.sh`
- Secret scan: `./scripts/secret-scan.sh`
- Whitespace validation: `git diff --check`

The quick gate includes Android sample script tests, JVM tests, Android host tests and AAR packaging, iOS Simulator tests, the JVM consumer check, the Android application consumer check, and hygiene. The full gate adds XCFramework assembly, Swift Package tests, and the iOS sample build. For new consumers, device paths, providers, or distribution modules, use the commands recorded in the active work-package plan and add them to `./scripts/check.sh` and GitHub Actions as soon as they become part of the supported deterministic baseline.

Do not claim a target, sample, simulator, device slice, live provider, gateway, or release path is verified unless that exact path ran successfully. Report unavailable toolchains or environments as blockers.

## Maintain Honest Status

- Keep `docs/plans/universal-ai-connector-v2.md` as the detailed milestone source of truth.
- Keep the README concise and public-facing: current phase, proven capabilities, limitations, next milestone, and accurate commands.
- Keep installation and first-use snippets aligned with compiled consumer samples. Do not document unpublished Maven or remote Swift Package coordinates as available.
- Mark a milestone completed only when every acceptance criterion is satisfied.
- Attach evidence to the roadmap or active plan using exact test/build results and the execution date.
- For a milestone-closing candidate, make the roadmap, active plan, README when affected, and every plan-required status document agree before final review. Keep at most one milestone `In progress`, and do not activate or implement the next milestone merely because the current one is being closed.
- Distinguish architecture validation, deterministic tests, packaging success, and production readiness.

## Commit and Push Safely

Do not commit or push without explicit user authorization.

When the current request authorizes both implementation and creation or update of the resulting pull request, treat the necessary in-scope commit and push as explicitly authorized. After the draft is published, follow `$review-verify-merge-pr` through independent exact-head review, in-scope finding fixes, readiness, and the guarded native merge by default. Within that already-authorized lifecycle, `keep draft`, `remain draft`, or `do not mark ready` blocks readiness and merge, while `do not merge` permits readiness after clean gates but blocks the merge command. Those opt-outs never create state-change authority in a review-only task. A local-only implementation request never activates this lifecycle.

When creating a branch, use the conventional naming policy in `AGENTS.md`: `feature/`, `bugfix/`, `docs/`, `chore/`, or `refactor/` followed by a concise lowercase kebab-case description. Match the prefix to the work's primary purpose, omit issue numbers unless useful, and do not create new `codex/` branches. Never rename an existing branch or pull-request head solely to adopt the convention.

Before committing:

1. Inspect `git status --short --branch`.
2. Stage only files belonging to the work package.
3. Inspect `git diff --cached --name-only` and the staged diff.
4. Confirm the worktree contains no unstaged or untracked files and that generated artifacts and secrets are absent.
5. Commit only through the mandatory pre-commit hook and require `./scripts/check.sh --quick` to pass.

Before pushing or creating or updating a pull request:

1. Confirm the worktree is clean and `HEAD` is the exact intended remote update.
2. Push only through the mandatory pre-push hook and require `./scripts/check.sh --full` to pass.
3. Never use `--no-verify`; if the full gate cannot run or fails, stop and report the blocker.
4. Refresh the concise PR description and the richer reviewer packet with the pushed head SHA and exact local evidence whenever material context or the head changes.

If pushing would publish earlier local commits ahead of the remote, tell the user before pushing.

## Report the Result

End every implementation session with:

- active milestone and work package;
- branch or worktree;
- files and modules changed;
- tests and builds executed with pass/fail results;
- public contract or generated fixture changes;
- exact proof boundaries;
- remaining risks or blockers;
- next incomplete work package;
- commit ID when committed.

Never equate a green build with verified user-visible or live-provider functionality.
