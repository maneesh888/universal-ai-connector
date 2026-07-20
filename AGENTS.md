# Universal AI Connector Workflow

## Scope

This repository contains a verified iOS-Kotlin interoperability POC and the plans for evolving it into the Universal AI Connector V2 package.

The next approved scope is cross-platform targets, shared interoperability tests, thin iOS, Android, and JVM sample clients, and a measurable host-integration baseline. Do not add provider adapters, Ktor networking, canonical AI contracts, gateway integration, artifact publication, or OpenKeyboard integration before their work package is active in `docs/plans/universal-ai-connector-v2.md`.

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

- Hygiene only: `./scripts/check.sh --hygiene` validates shell syntax, secrets, and whitespace, including untracked files.
- Mandatory commit check: `./scripts/check.sh --quick` adds JVM and Android shared tests, Android AAR packaging, iOS Simulator bridge tests, and the JVM console consumer.
- Mandatory push and PR check: `./scripts/check.sh --full` adds XCFramework assembly, Swift Package tests, and the iOS sample build.
- JVM shared tests: `./gradlew :bridge:jvmTest`
- JVM console consumer: `./gradlew :samples:jvm-console:consumerCheck`
- JVM console application: `./gradlew :samples:jvm-console:run`
- Android shared host tests: `./gradlew :bridge:testAndroidHostTest`
- Android library AAR: `./gradlew :bridge:bundleAndroidMainAar`
- Kotlin bridge tests: `./gradlew :bridge:iosSimulatorArm64Test`
- XCFramework: `./scripts/build-xcframework.sh`
- Swift Package and simulator tests: `./scripts/test-swift-package.sh`
- Sample simulator build: `./scripts/build-sample.sh`
- Secret scan: `./scripts/secret-scan.sh`
- Final whitespace check: `git diff --check`

Set `POC_SIMULATOR_DESTINATION` to override the default Xcode destination.

## Mandatory Local Gates

- Keep the committed hooks enabled through `core.hooksPath=.githooks` in every clone.
- Every commit must pass the pre-commit hook, which rejects unstaged or untracked files and runs `./scripts/check.sh --quick` against the exact proposed commit contents.
- Every push, including the push used to create or update a pull request, must pass the pre-push hook, which rejects refs that do not resolve to the checked-out `HEAD`, requires a clean worktree, and runs `./scripts/check.sh --full` against that exact commit.
- Never use `--no-verify` or another mechanism to bypass repository hooks. If the required toolchain is unavailable or a check fails, treat the commit or push as blocked until the environment or failure is fixed.
- GitHub Actions remain an independent merge gate; local success never replaces required remote checks.

As of July 19, 2026, GitHub Actions run [29698575249](https://github.com/maneesh888/universal-ai-connector/actions/runs/29698575249) proves repository hygiene; shared tests and the JVM console consumer on Linux, Windows, and macOS; Android host tests and AAR packaging on Linux; the P0 Apple interoperability path on macOS; and the stable `Required checks` aggregator. CI still does not prove iOS device, Android application, provider, gateway, or release behavior.

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
- Before independent review, assemble a neutral review brief from the current user request, PR body, linked issue or plan, and verified implementation evidence. Include the problem, requirement sources, requirements and acceptance criteria, implementation decisions, out-of-scope behavior, evidence and proof boundaries, and exact head SHA.
- Record the review brief in the PR description before merge and pass it with its source links to the reviewer. Do not pass expected findings or implementation conclusions as facts. Treat a missing, ambiguous, or stale review brief as a merge-readiness blocker.
- Use the project `pr-reviewer` custom agent for an independent read-only review when it is available; the root agent remains responsible for verification and every GitHub state change.
- Review the exact PR head for correctness, architecture, regressions, tests, security, public API and packaging boundaries, and truthful documentation or evidence.
- Before merging, confirm the reviewed head SHA is unchanged, required checks passed, no blocking review thread or requested change remains, local verification passed for the affected proof surface, and GitHub reports the PR mergeable.
- Treat correctness, security, data-loss, public-contract, missing-test, and materially false status or verification findings as blockers.
- Mark a draft ready or merge it only when the user's current request explicitly authorizes that action. Never bypass branch protection, force a merge, dismiss a valid review, or enable administrator overrides.
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
