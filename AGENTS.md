# Universal AI Connector Workflow

## Scope

This repository contains the verified P0 iOS-Kotlin interoperability baseline and the cross-platform package foundation for evolving Universal AI Connector V2.

Execute only the work package marked active in `docs/plans/universal-ai-connector-v2.md`. A future milestone plan may be drafted while the current milestone closes, but planning does not activate its implementation. Do not add canonical AI contracts, provider adapters, Ktor networking, gateway integration, artifact publication, or OpenKeyboard integration before the corresponding work package is active.

## Start Every Task

1. Resolve the root with `git rev-parse --show-toplevel`.
2. Inspect `git status --short --branch`.
3. Run `./scripts/install-hooks.sh` and confirm `git config --local --get core.hooksPath` prints `.githooks`.
4. Read `docs/plans/universal-ai-connector-v2.md`, `docs/DEVELOPMENT_WORKFLOW.md`, and the active work-package plan completely.
5. Execute only one bounded work package unless the user explicitly authorizes a larger batch.
6. Preserve unrelated changes and generated artifacts.
7. Use the Gradle wrapper and repository scripts.
8. Do not commit or push unless explicitly authorized.

## Branch Naming

Use a conventional type prefix for every new branch:

- `feature/<short-description>` for new functionality
- `bugfix/<short-description>` for defect fixes
- `docs/<short-description>` for documentation-only work
- `chore/<short-description>` for maintenance
- `refactor/<short-description>` for structural changes without behavior changes

Write the description in lowercase kebab-case. Keep the full branch name concise and descriptive, omit issue numbers unless they add useful context, and choose the prefix that matches the work's primary purpose. Do not use `codex/` for new branches. Preserve existing branches and pull requests under their current names; do not rename `codex/p1-android-sample` or any other existing branch.

## Current Verification

- Hygiene only: `./scripts/check.sh --hygiene` validates shell syntax, secrets, and whitespace, including untracked files, and regression-tests fail-closed secret scanning that cannot be suppressed by ripgrep configuration or ignore files.
- Mandatory commit check: `./scripts/check.sh --quick` adds Android sample script tests, canonical contract tests on JVM, Android host, and iOS Simulator, shared behavior tests, Android AAR packaging, iOS Simulator bridge tests, and the JVM and Android consumers.
- Mandatory push and PR check: `./scripts/check.sh --full` adds combined device-and-simulator XCFramework assembly and validation, Swift Package tests, the iOS simulator sample build, and a generic iOS-device sample link/build.
- JVM shared tests: `./gradlew :bridge:jvmTest`
- Canonical contract conformance: `./scripts/check-contracts.sh --all`
- JVM console consumer: `./gradlew :samples:jvm-console:consumerCheck`
- JVM console application: `./gradlew :samples:jvm-console:run`
- Android shared host tests: `./gradlew :bridge:testAndroidHostTest`
- Android library AAR: `./gradlew :bridge:bundleAndroidMainAar`
- Android application consumer: `./gradlew :samples:android:consumerCheck`
- Android emulator/device application: `./scripts/run-android-sample.sh`
- Kotlin bridge tests: `./gradlew :bridge:iosSimulatorArm64Test`
- XCFramework: `./scripts/build-xcframework.sh`
- Swift Package and simulator tests: `./scripts/test-swift-package.sh`
- Sample simulator build: `./scripts/build-sample.sh`
- Sample generic-device link/build: `./scripts/build-sample-device.sh`
- Secret scan: `./scripts/secret-scan.sh` requires `rg`, disables ripgrep configuration and ignore rules, fails closed when the scanner is missing or errors, and reports a match without printing the matched credential material.
- Final whitespace check: `git diff --check`

Set `UAC_SIMULATOR_DESTINATION` to override the default Xcode destination.

## Mandatory Local Gates

- Keep the committed hooks enabled through `core.hooksPath=.githooks` in every clone.
- Every commit must pass the pre-commit hook, which rejects unstaged or untracked files and runs `./scripts/check.sh --quick` against the exact proposed commit contents.
- Every push, including the push used to create or update a pull request, must pass the pre-push hook, which rejects refs that do not resolve to the checked-out `HEAD`, requires a clean worktree, and runs `./scripts/check.sh --full` against that exact commit.
- Never use `--no-verify` or another mechanism to bypass repository hooks. If the required toolchain is unavailable or a check fails, treat the commit or push as blocked until the environment or failure is fixed.
- GitHub Actions remain an independent merge gate; local success never replaces required remote checks.

The platform and consumer jobs in GitHub Actions run [29730678994](https://github.com/maneesh888/universal-ai-connector/actions/runs/29730678994) passed on July 20, 2026, as bounded compatibility evidence. That pull-request run checked out GitHub's synthetic merge commit rather than the PR head, and its green hygiene result is not valid secret-scan evidence because `rg` was unavailable and the former scanner failed open. Cite only a later run whose logs show exact-head checkout plus the fail-closed scanner and its regression test executing successfully as exact-head repository-hygiene proof. CI still does not prove an Android emulator or physical device, iOS device, provider, gateway, or release behavior.

## Host Integration Standard

- Treat Android, iOS, and Kotlin/JVM as the supported application surfaces for the initial alpha. Prove JVM portability on Linux, Windows, and macOS before claiming those host operating systems.
- Give Kotlin consumers one shared client entry point with idiomatic `suspend` and `Flow` APIs. Give Swift consumers one Swift façade with `async` functions, `AsyncThrowingStream`, Swift errors, and Swift cancellation.
- Keep callback bridges, coroutine scopes, Kotlin implementation types, generated Objective-C names, and packaging details out of supported host-facing APIs.
- Provide a simple default construction path. Keep transport injection and other advanced configuration available without making them mandatory for first use once P3 activates networking.
- Treat samples as external consumers: they must use the packaged module or artifact boundary and must not reach into internal source sets or implementation packages.
- A platform is integration-verified only when its clean consumer sample builds from documented instructions and exercises response, streaming, stable errors, and cancellation where supported.
- Keep remote Maven and Swift Package publication in P8. P1 establishes local package boundaries, consumer samples, and copy-paste-ready integration documentation.

## Development Tools

- Use the `gh` CLI for GitHub repository, PR, issue, review, workflow, log, artifact, and authorized state-changing operations. Use `gh api graphql` when thread-level or GraphQL state is required.
- Do not use the GitHub connector unless the user explicitly requests it. If `gh` is unavailable or unauthenticated, report that blocker instead of silently switching tools.
- Use official provider-documentation MCPs for current external API contracts only when the corresponding provider milestone is active.
- Use Xcode or simulator MCPs for exact platform proof when available; otherwise use the repository Xcode scripts and report the missing proof surface.
- Treat MCPs as development tools, never runtime package dependencies.
- Do not store MCP tokens or credentials in the repository.

## Pull Request Review and Merge

- Route PR review, merge-readiness, and merge requests through `$review-verify-merge-pr`.
- Create every pull request as a draft. GitHub Actions must run while the pull request remains a draft; readiness is a gated state change, not the trigger for initial CI.
- Keep the PR description as a concise, durable review brief that is proportional to the change and normally about 20-40 lines. Include the problem and change summary, scoped behavior and material decisions, linked requirement sources plus any concise requirement delta that exists only in the implementation conversation, verification summary, out-of-scope behavior and proof limits, exact head SHA, and exactly one declaration: `Milestone effect: none`, `Milestone effect: advances`, or `Milestone effect: completes`. Do not duplicate full plan text, raw logs, test-by-test transcripts, or review history merely to make the description self-contained.
- Before independent review, separately assemble a neutral, structured reviewer packet from the current user request, concise PR brief, linked issue or plan, root diff, and verified implementation evidence. Include the detailed requirements and observable acceptance criteria needed to review the change, important decisions and constraints, milestone effect, required status-document consequences, out-of-scope behavior, evidence and proof boundaries, source links, and exact head SHA. Pass that packet directly to the reviewer without expected findings or implementation conclusions. Materially missing, ambiguous, stale, or inconsistent review context is a merge-readiness blocker, but the PR description need not repeat information available in a linked durable source.
- Keep milestone-closeout writes with the implementation/root agent. When the milestone effect is `completes`, keep the PR in draft until the implementation acceptance evidence exists and the final candidate branch contains a proposed roadmap transition, active work-package status and evidence, README public status and next package when affected, and every other repository status document required by the active plan. Commit those closeout changes before final independent review and before marking the PR ready. Final exact-head review and checks validate the proposed transition, which becomes authoritative only when that candidate merges. The next milestone may be identified as next without being activated prematurely.
- Record the exact candidate SHA, check runs, independent-review result, and other evidence created by validating that same candidate in the PR review brief. Do not require a merge SHA or final `main` CI run ID inside repository documentation when recording it would require another commit and invalidate the reviewed evidence.
- After creating or updating a draft pull request, its concise review brief, and the reviewer packet, the same root agent must immediately invoke the project `pr-reviewer` custom agent for an independent read-only review of the exact head SHA. Review and required checks may run in parallel while the pull request remains a draft, but that parallelism never permits readiness or a merge attempt before every mandatory check completes successfully. The root agent remains active and responsible for verification and every GitHub state change.
- Review the exact PR head for correctness, architecture, regressions, tests, security, public API and packaging boundaries, and truthful documentation or evidence.
- Fix every blocking finding while the pull request remains a draft. Marking a PR ready freezes the reviewed head. Any later commit makes the prior review and verification stale: disable any auto-merge request, return the PR to draft when authorized and necessary, refresh the concise PR brief, reviewer packet, milestone-effect evaluation, and exact SHA, rerun proportional local verification and GitHub checks, and obtain a new independent exact-head review before marking it ready again. In a review-only task, report that stale-head blocker without changing PR state or files.
- Before leaving draft or attempting a merge, confirm the concise PR brief and reviewer packet are complete, current, and consistent; the declared milestone effect matches the diff, roadmap, README, active plan, and acceptance evidence; every required closeout document is present for `completes`; the independently reviewed SHA equals GitHub's head SHA; all mandatory local verification passed for that exact SHA; every mandatory GitHub check completed successfully, including `Required checks` and any applicable protected live-verification status; no blocking finding, requested change, or unresolved thread remains; the diff stays in scope; and GitHub reports no merge conflict or unsatisfied base-update policy. A pending, in-progress, failed, cancelled, timed-out, skipped, or missing mandatory check blocks both readiness and merge.
- Verify through the GitHub APIs that `main` requires changes through a pull request, strictly requires the GitHub Actions `Required checks` status, requires conversation resolution, applies to administrators without a bypass, and prohibits force pushes and deletion. When a protected live-verification status becomes applicable, require it directly or through a server-enforced required-check dependency. Confirm `gh pr checks <number> --required` reports every applicable required status successfully. Missing or weakened enforcement blocks both readiness and merge.
- Treat correctness, security, data-loss, public-contract, missing-test, and materially false status or verification findings as blockers.
- Start the default implementation-PR lifecycle only when the current request authorizes both implementing changes and creating or updating the resulting pull request. Local implementation alone authorizes no commit, push, pull request, readiness change, or merge. Implementation-and-PR authorization covers the necessary in-scope commit and push, concise-brief and reviewer-packet maintenance, independent exact-head review, in-scope finding fixes, readiness, and the guarded native merge after every gate passes; do not request a second confirmation between those stages.
- Let the latest user instruction control the lifecycle. Standalone review, status, readiness-assessment, and blocker requests are read-only. Within an already-authorized implementation-PR lifecycle, `keep draft`, `remain draft`, or `do not mark ready` blocks readiness and merge, while `do not merge` permits the clean PR to become ready but blocks the merge command. These opt-outs never create state-change authority in a review-only task. An instruction to create the pull request as a draft states the mandatory starting state and is not by itself a keep-draft opt-out. A finding that materially expands the authorized work package requires user direction.
- For an authorized implementation-PR lifecycle without an applicable opt-out, mark the draft ready only after every gate above passes; then refresh the head SHA, required checks, reviews, unresolved threads, branch protection, mergeability, and latest user instruction immediately before the merge command, and confirm the head is still the exact reviewed SHA. Do not stop for another confirmation. Re-evaluate the latest instruction immediately before readiness and again before merge. If a keep-draft opt-out arrives after readiness, disable auto-merge, return the pull request to draft, and verify it is draft and unmerged; if a do-not-merge instruction arrives after readiness, disable auto-merge and verify it remains unmerged. An explicit request to merge an existing pull request follows the same guarded path. Make the native squash-merge attempt:

  ```bash
  gh pr merge <number> --auto --squash --match-head-commit <reviewed-head-sha>
  ```

- `--match-head-commit` checks the head only when the command runs; it is not durable protection for a queued auto-merge request. Because every gate is already green, expect the command to merge immediately and inspect the PR state at once. If GitHub leaves the pull request open with auto-merge queued, immediately run `gh pr merge <number> --disable-auto`, verify the request is disabled and the PR remains unmerged, and report the blocker. Never leave a queued request active or wait for it to merge later.
- If the head changes before the merge completes, disable any auto-merge request, return the pull request to draft with `gh pr ready <number> --undo` when authorized and applicable, refresh the concise PR brief and reviewer packet, and restart local verification, independent review, required-check inspection, protection inspection, milestone-effect consistency, and every other gate for the new SHA.
- Never use `--admin`, bypass branch protection, dismiss valid feedback, force a merge, weaken required checks, or enable a privileged CI-side or unattended background merger.
- Keep normal GitHub Actions read-only and secretless. Do not add a write token, PAT, privileged CI-side or unattended background merger, merge logic, or `pull_request_target` to `ci.yml`.
- After GitHub merges the pull request, inspect the resulting `main` workflow run, fetch remote `main`, reread the roadmap and every relevant repository status document from that remote ref, and assert that the declared milestone effect landed consistently. If the promised transition is absent or inconsistent, report the milestone closeout as incomplete. Do not let the reviewer edit files and do not create or push a follow-up closeout commit without explicit authorization.
- Keep review-only tasks read-only. Do not fix findings, alter unrelated work, or push replacement commits unless the user separately requests implementation.

## Architecture Rules

- Keep platform-neutral behavior in Kotlin `commonMain`.
- Keep sample applications thin; they demonstrate the shared API and do not duplicate connector behavior.
- Keep one shared Kotlin client contract for Android and JVM consumers; do not create platform-specific connector behavior merely to simplify a sample.
- Keep Kotlin `Flow`, coroutine types, and Kotlin implementation types behind the Swift callback bridge.
- Keep the supported Apple API in the Swift façade.
- Preserve idiomatic host lifecycle behavior: caller cancellation must propagate, terminal callbacks must be exactly once, and owned resources must have a documented cleanup path.
- Use deterministic fake implementations for samples and normal CI until live-provider work is explicitly active.
- Keep provider DTOs internal to their future provider modules.
- Do not introduce OpenKeyboard actions, prompts, storage, or UI into this package.

## Safety

- Never commit generated XCFrameworks, build directories, DerivedData, `.xcresult` bundles, logs, credentials, or secrets.
- Never print API keys or Authorization headers.
- Before committing, inspect `git status --short --branch`, stage only intended files, inspect `git diff --cached --name-only`, and let the mandatory pre-commit hook run `./scripts/check.sh --quick` without bypassing it.
- Before pushing, confirm the worktree is clean and let the mandatory pre-push hook run `./scripts/check.sh --full` without bypassing it.
