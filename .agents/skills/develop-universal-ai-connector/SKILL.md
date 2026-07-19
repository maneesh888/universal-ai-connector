---
name: develop-universal-ai-connector
description: Execute roadmap-driven development for the Universal AI Connector Kotlin Multiplatform repository. Use when Codex is asked to analyze, plan, implement, test, review, document, continue, or report status on connector milestones P0-P9, including shared Kotlin behavior, Swift bridging and XCFramework delivery, iOS/Android/JVM samples, canonical AI contracts, HTTP transport, provider adapters, gateway integration, Apple distribution, or release hardening.
---

# Develop Universal AI Connector

Advance the independent Universal AI Connector repository one bounded, verified work package at a time. Treat repository plans and test evidence as the source of truth.

## Establish Repository Context

1. Resolve the repository root with `git rev-parse --show-toplevel` and work only from that checkout or its task worktree.
2. Inspect `git status --short --branch` before making changes.
3. Read the root `AGENTS.md` completely and follow it as the repository's always-on policy.
4. Read `docs/plans/universal-ai-connector-v2.md` completely. Treat it as the source of truth for milestone order and status.
5. Read the plan named by the active milestone. For P1, read `docs/plans/cross-platform-client-samples.md` completely.
6. Read `docs/DEVELOPMENT_WORKFLOW.md` before changing build, verification, CI, hooks, or external-tool behavior.
7. Preserve unrelated tracked and untracked changes. Do not incorporate, revert, stage, or commit them unless the user explicitly assigns them to the task.

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
8. Update milestone status only after its acceptance criteria have evidence.

## Route Optional Development Tools

- Use the GitHub connector for remote repository context, pull requests, issues, workflow results, job logs, and artifacts. Request explicit authorization before remote writes.
- Use the OpenAI Developer Docs MCP during P4 and later OpenAI contract work. Fetch current primary documentation instead of relying on model memory.
- Use an available Xcode, simulator, or device MCP for exact Apple lifecycle and visual proof. Fall back to repository scripts when it is unavailable and state the missing proof surface.
- Use browser tooling only for future web samples or published documentation.
- Never make MCP availability a runtime dependency of the connector and never place MCP credentials in the repository.

## Verify Proportionally

Use the repository check modes proportionally:

- Hygiene only: `./scripts/check.sh --hygiene`
- Fast Kotlin and hygiene check: `./scripts/check.sh --quick`
- Complete deterministic Kotlin, JVM-consumer, and Apple check: `./scripts/check.sh --full`

Use narrower commands during iteration:

- Kotlin iOS bridge tests: `./gradlew :bridge:iosSimulatorArm64Test`
- Shared JVM tests: `./gradlew :bridge:jvmTest`
- Android host tests: `./gradlew :bridge:testAndroidHostTest`
- Android AAR packaging: `./gradlew :bridge:bundleAndroidMainAar`
- JVM console consumer: `./gradlew :samples:jvm-console:consumerCheck`
- XCFramework assembly: `./scripts/build-xcframework.sh`
- Swift Package tests: `./scripts/test-swift-package.sh`
- iOS sample build: `./scripts/build-sample.sh`
- Secret scan: `./scripts/secret-scan.sh`
- Whitespace validation: `git diff --check`

For new Android, JVM, device, provider, or distribution modules, use the commands recorded in the active work-package plan and add them to `./scripts/check.sh` and GitHub Actions when they become part of the supported deterministic baseline.

Do not claim a target, sample, simulator, device slice, live provider, gateway, or release path is verified unless that exact path ran successfully. Report unavailable toolchains or environments as blockers.

## Maintain Honest Status

- Keep `docs/plans/universal-ai-connector-v2.md` as the detailed milestone source of truth.
- Keep the README concise and public-facing: current phase, proven capabilities, limitations, next milestone, and accurate commands.
- Keep installation and first-use snippets aligned with compiled consumer samples. Do not document unpublished Maven or remote Swift Package coordinates as available.
- Mark a milestone completed only when every acceptance criterion is satisfied.
- Attach evidence to the roadmap or active plan using exact test/build results and the execution date.
- Distinguish architecture validation, deterministic tests, packaging success, and production readiness.

## Commit and Push Safely

Do not commit or push without explicit user authorization.

Before committing:

1. Inspect `git status --short --branch`.
2. Run the active verification suite, `./scripts/secret-scan.sh`, and `git diff --check`.
3. Stage only files belonging to the work package.
4. Inspect `git diff --cached --name-only` and the staged diff.
5. Confirm generated artifacts and secrets are absent.

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
