# Development Workflow

## Purpose

Use one deterministic local workflow and the same proof boundaries in GitHub Actions. Verify the library both internally and as a host application consumes it. MCP tools may improve discovery and remote coordination, but they do not replace builds or tests.

## Proof levels

Keep these claims separate:

1. **Behavior proof:** shared unit tests validate deterministic connector semantics.
2. **Packaging proof:** the target artifact or module assembles successfully.
3. **Consumer integration proof:** a thin host sample depends on the public package boundary, compiles, and exercises the documented first-use path.
4. **Distribution proof:** a clean fixture resolves a published artifact from its documented Maven or Swift Package location.

P1 requires the first three levels for its supported samples. Distribution proof remains P8 work. A unit test or artifact assembly alone is not evidence that a host application can integrate the library.

P2 additionally requires **contract conformance proof**: authoritative schemas are meta-valid,
every fixture has one documented schema or semantic result, production encodings remain
schema-valid for the governed corpus, and the embedded multiplatform mirror has zero drift from
the tracked bundle.

## Local checks

Run commands from the repository root:

```bash
# Shell syntax, secrets, and whitespace, including untracked files
./scripts/check.sh --hygiene

# Mandatory before every commit
./scripts/check.sh --quick

# Mandatory before every push and pull-request creation or update
./scripts/check.sh --full
```

Calling `./scripts/check.sh` without an argument is equivalent to `--full`.

The quick and full checks validate shell syntax, secrets, whitespace, deterministic shell-script behavior, canonical contract layout and conformance on JVM, Android host, and iOS Simulator, shared JVM and Android behavior, Android AAR packaging, iOS Simulator bridge behavior, the JVM console consumer, and the Android application's controller tests and debug APK assembly. The secret scanner requires `rg`, disables ripgrep configuration and ignore rules, fails closed when the tool is missing or errors, reports matches without printing matched credential material, and has a regression test for those properties in every hygiene run. The full check then builds and validates one XCFramework containing iOS ARM64 device and simulator slices, runs the product-facing Swift integration tests, builds the SwiftUI sample for a simulator, and links that same sample for a generic iOS device. Packaging checks reject retired POC classes, unsupported Kotlin canonical/serialization types, or Apple-header symbols. Standalone Swift scripts still build their own framework unless `UAC_SKIP_XCFRAMEWORK_BUILD=1` is set by the orchestrating check.

The active P2 package uses these focused contract and host-side checks:

```bash
./scripts/check-contracts.sh --all
./gradlew :bridge:jvmTest
./gradlew :bridge:testAndroidHostTest
./gradlew :bridge:bundleAndroidMainAar
./gradlew :samples:jvm-console:consumerCheck
./gradlew :samples:jvm-console:run
./gradlew :samples:android:consumerCheck
./scripts/run-android-sample.sh
./scripts/build-xcframework.sh
./scripts/test-swift-package.sh
./scripts/build-sample.sh
./scripts/build-sample-device.sh
```

The JVM `consumerCheck` compiles the console against `project(":bridge")`, tests its exact output, and executes its non-interactive entry point. The Android `consumerCheck` compiles the separate Compose application against the same public module, runs its controller tests, and assembles its debug APK. These are consumer-integration proofs for the local public Gradle module boundary, not Maven distribution proof.

`run-android-sample.sh` requires a booted emulator or connected device, installs the debug APK, and launches the app. Set `UAC_ANDROID_SERIAL` to use the same selected device for installation and launch, or `UAC_ADB` to select an `adb` binary. This optional device path is local evidence; CI intentionally uses deterministic unit/build checks and does not boot an emulator.

`build-sample-device.sh` uses Xcode's `generic/platform=iOS` destination with code signing disabled. It proves that the Swift Package product selects and links the XCFramework's `ios-arm64` slice; it does not install or execute the app on physical hardware.

When a later milestone changes a supported sample or package boundary, update its build/run commands here and in the top-level check. Sample verification must use public Gradle module dependencies or the Swift Package product; do not compile shared source files directly into a sample.

## Mandatory local hooks

Enable the committed hooks for every clone:

```bash
./scripts/install-hooks.sh
```

The installer configures the local repository to use `.githooks/` and refuses to replace a different configured hooks path. Because Git does not activate committed hooks automatically, run the installer before repository work and verify `git config --local --get core.hooksPath` prints `.githooks`.

The pre-commit hook rejects unstaged tracked changes and untracked files so the working tree matches the staged snapshot, then runs `./scripts/check.sh --quick`. The pre-push hook rejects branch or tag updates that do not resolve to the checked-out `HEAD` and requires a clean worktree before and after `./scripts/check.sh --full`, so verification applies to the exact commit being pushed. Never use `--no-verify`; a missing toolchain or failed check blocks the commit or push until fixed.

The hooks are mandatory local gates. GitHub CI remains an independent remote enforcement boundary and must also pass before merge.

## GitHub Actions

`.github/workflows/ci.yml` runs for pull requests, pushes to `main`, and manual dispatches:

- `Repository hygiene` installs `rg`, runs the fail-closed secret-scan regression, and checks secrets and whitespace on Linux.
- `JVM + Android (Linux)` runs the schema/fixture harness, shared contract and behavior tests on JVM and Android host, the JVM console consumer, Android AAR packaging, and the Android application consumer check with Java 21.
- `JVM (Windows)` runs the schema/fixture harness, JVM shared contract/behavior tests, and the JVM console consumer with Java 21.
- `Apple + JVM (macOS)` installs `rg` and runs the complete local `--full` suite, including Android library/application, JVM, combined Apple framework, Swift Package, simulator sample, and generic-device link verification, with Java 21.
- `Required checks` provides one stable branch-protection status.

Superseded runs on the same pull request or branch are cancelled. Pull-request jobs explicitly check out the PR head SHA; strict branch protection separately requires that head to be current with `main` before merge. Every third-party action reference is pinned to a full commit SHA, with its release line recorded in a comment and Dependabot responsible for reviewed updates. The workflow grants read-only repository permissions and does not inherit or require secrets. Failed Apple checks retain deterministic test evidence for seven days.

`.github/dependabot.yml` groups monthly GitHub Actions and Gradle updates so workflow and build dependencies do not silently age. Review and verify those pull requests like any other dependency change; do not auto-merge them without the required checks.

The platform and consumer portions of GitHub Actions run [29730678994](https://github.com/maneesh888/universal-ai-connector/actions/runs/29730678994) passed on July 20, 2026, as bounded compatibility evidence. Its pull-request jobs checked out GitHub's synthetic merge commit rather than the PR head, and its green hygiene result does not prove secret scanning because `rg` was unavailable and the former scanner failed open. Use only a later run whose logs show exact-head checkout plus the fail-closed scanner and regression test executing successfully as exact-head hygiene evidence. The macOS Apple job remains responsible for Kotlin/Native, XCFramework, Swift Package, and iOS sample proof. Do not label emulator/device, provider, gateway, distribution, or release behavior as CI-verified before the corresponding evidence exists.

Keep `Required checks` as the stable branch-protection status and make it depend on every supported P1 host job. Prefer one host job per materially different toolchain; do not add native targets solely to increase the matrix.

## Host-integration review

When a work package changes a public API, packaging, or sample:

- confirm Android and JVM samples use the same public Kotlin client entry point;
- confirm Swift application code imports only the Swift façade product;
- compile the documented first-use path from a consumer sample;
- verify host cancellation and resource cleanup behavior;
- reject required setup that depends on internal source paths, manual generated-artifact copying, or undocumented build flags;
- update installation, initialization, minimum-version, and limitation documentation in the same change.

Remote dependency-resolution checks are added only when P8 activates publication. Until then, describe Maven and remote Swift Package distribution as planned, not available.

Keep `main` protected in GitHub by requiring changes through a pull request, strict status checks that require the GitHub Actions `Required checks` status, required conversation resolution, administrator enforcement without a bypass, and force pushes and branch deletion disabled. When a protected live-verification status becomes applicable, add it as a required status or as a server-enforced dependency of a required aggregator. Configure this enforcement through GitHub repository settings rather than local scripts or workflow write permissions. Before a pull request leaves draft or any merge is attempted, verify the effective protection through the GitHub branch APIs and confirm `gh pr checks <number> --required` reports every applicable required status as successful. Missing, weakened, or unverifiable protection is a blocker.

## GitHub CLI and connector routing

Use optional tools when they are available:

| Tool | Use | Proof boundary |
|---|---|---|
| GitHub CLI (`gh`) | Default for repository, PR, issue, review, workflow, log, artifact, and authorized state-changing operations; use `gh api graphql` for thread-level state | Remote coordination and CI evidence |
| GitHub connector | Use only when the user explicitly requests it; never switch to it silently when `gh` is unavailable or unauthenticated | Connector-specific fallback explicitly chosen by the user |
| OpenAI Developer Docs MCP | Verify current OpenAI API contracts when P4 or later activates them | Documentation evidence, not implementation proof |
| Xcode or simulator MCP | Inspect simulator state and collect visual or lifecycle evidence | Valid only for the exact simulator/device path exercised |
| Browser tooling | Verify future web samples or published documentation | Browser behavior only |

If `gh` is unavailable or unauthenticated, report the blocker rather than silently changing GitHub tools. If another MCP is unavailable, use repository scripts and report the missing proof surface. Never add MCP SDKs, credentials, or tool-specific DTOs to the runtime package. Never store tokens in repository configuration; use OAuth or environment-backed credentials.

## Branch naming

Create new branches with the conventional prefix that matches the work's primary purpose:

- `feature/<short-description>` for new functionality
- `bugfix/<short-description>` for defect fixes
- `docs/<short-description>` for documentation-only work
- `chore/<short-description>` for maintenance
- `refactor/<short-description>` for structural changes without behavior changes

Use a concise, descriptive, lowercase kebab-case suffix. Avoid issue numbers unless they add useful context. Do not create new `codex/` branches, and do not rename existing branches or pull-request heads that already use that prefix.

## Pull-request workflow

1. Select one roadmap work package.
2. Run targeted tests while iterating.
3. Build affected consumer samples when public APIs or package boundaries change.
4. Stage every intended file and remove or preserve elsewhere any unrelated change; partial commits with unstaged or untracked files are rejected.
5. Commit only after the mandatory pre-commit hook passes `./scripts/check.sh --quick`.
6. Push only after the mandatory pre-push hook passes `./scripts/check.sh --full` from a clean worktree. This applies to both pull-request creation and every later update.
7. Create the pull request as a draft. GitHub Actions run while the pull request remains a draft.
8. Add exactly one declaration to the concise PR review brief: `Milestone effect: none`, `Milestone effect: advances`, or `Milestone effect: completes`.
9. For `completes`, keep the PR draft. Once the implementation candidate has the acceptance evidence required to propose completion, make the implementation/root agent update and commit the roadmap transition, active work-package status and evidence, README public status and next package when affected, and every other status document required by the active plan. Do this before final independent review; do not activate the next milestone prematurely.
10. Add or refresh the concise PR review brief from the implementation request, linked issue or plan, material decisions, scope, milestone effect, exact head SHA, verification summary, and proof limits. Separately assemble the richer structured reviewer packet from those sources, the root diff, status-document consequences, and exact evidence.
11. In the same active task, the root agent that created or updated the draft must invoke the project `pr-reviewer` agent to independently review the exact SHA of the final candidate using the current concise PR brief, structured reviewer packet, and source links. Review and required checks may continue in parallel while the pull request remains a draft; every readiness and merge gate still waits for successful completion.
12. Fix every blocking finding while the pull request remains a draft. Marking a PR ready freezes the reviewed head. If a fix or any later update changes the head SHA, disable any auto-merge request, return the PR to draft when authorized and necessary, and restart local verification, the concise PR brief, reviewer packet, milestone-effect evaluation, independent review, required-check inspection, protection inspection, thread inspection, and mergeability inspection for the new SHA.
13. Use `gh` to wait for every mandatory GitHub check to complete successfully on the exact reviewed head, including `Required checks` and any applicable protected live-verification status. Pending, in-progress, failed, cancelled, timed-out, skipped, or missing mandatory checks block both readiness and a merge attempt.
14. Verify milestone-effect consistency, `main` protection through the GitHub APIs, and `gh pr checks <number> --required`. For an authorized implementation-PR lifecycle without an applicable user opt-out, every green gate requires the root agent to mark the exact reviewed head ready, refresh the head and all gates immediately, and make the guarded native squash-merge attempt without requesting another confirmation. If GitHub queues auto-merge instead of merging immediately, disable it at once, keep the pull request unmerged, and report the blocker.
15. After merge, fetch remote `main` and reread its roadmap and relevant status documents. Assert that the declared milestone effect landed consistently; report an incomplete closeout instead of silently creating a follow-up commit when it did not.

### Review context

Use two complementary layers. The PR description is a concise, durable review brief for human readers; the root agent separately gives the independent reviewer a richer structured packet. The body and its linked sources preserve the auditable requirements and evidence, while the packet gives the reviewer enough detail to inspect the exact diff without forcing that detail into the public description.

Keep a normal PR description proportional to the change and usually about 20-40 lines. More detail is appropriate only when it records a material risk, a decision unavailable elsewhere, or evidence required by the active plan. Do not copy full plan sections, raw logs, test-by-test transcripts, generated file inventories, or review history into the body. Use this structure:

```markdown
## Summary
- Why this change is needed and what it does

## Scope and key decisions
- Material behavior or constraint introduced by this PR
- Requirement sources: linked issue, plan, or decision
- Request-only delta: concise requirement not available in a durable source, if any
- Milestone effect: none | advances | completes

## Verification
- `<command or check>` — `<result>`

## Proof limits
- Material out-of-scope or unexercised behavior

## Review target
`<full commit SHA>`
```

Before independent review, build a neutral reviewer packet from the concise PR brief, current implementation request, linked durable sources, root diff, and verified evidence. The packet includes the detailed problem, requirements and observable acceptance criteria, important decisions and constraints, out-of-scope behavior, evidence and proof boundaries, source links, and exact head SHA. Pass it directly to the reviewer without expected findings or a desired conclusion; it does not need to be duplicated in the PR description.

Refresh the concise brief and reviewer packet when requirements, scope, milestone effect, evidence, or the head SHA materially changes. Requirements that exist only in a private implementation conversation must have a concise durable delta in the PR body and fuller detail in the packet. The live-evidence fields required by the roadmap must still be recorded in a concise redacted form or linked durable record. Missing, ambiguous, stale, or internally inconsistent material context across the brief, linked sources, and packet blocks merge readiness; brevity or lack of repeated linked text alone does not.

Exact head SHA, check runs, independent-review results, and evidence generated by validating that same candidate are self-referential and belong in the PR review brief. The root agent may refresh those fields after checks or review finish without changing the candidate head. Do not require a merge SHA or final `main` CI run ID in repository documentation when adding it would require another commit and invalidate the evidence.

### Atomic milestone closeout

The milestone-effect declaration assigns the status consequence of the PR:

- `none` changes no milestone progress or completion state.
- `advances` adds implementation or evidence while leaving the active milestone incomplete.
- `completes` claims that the active milestone's full acceptance boundary is satisfied and closes it in the same candidate.

For `completes`, writing remains the implementation/root agent's responsibility. After the implementation acceptance evidence exists, keep the PR draft until the final candidate branch contains committed, mutually consistent proposed closeout updates for the roadmap, active work-package status and evidence, README public status and next package when affected, and every other repository status document required by the active plan. Commit these updates before final independent review and before readiness. Final exact-head review and checks validate the proposed transition, which becomes authoritative only when that candidate merges. Keep at most one milestone `In progress`; naming the next package does not authorize activating or implementing it before the current milestone is accepted.

The read-only reviewer must report a blocking `milestone closeout missing or inconsistent` finding when a `completes` brief lacks the required transition. Regardless of the declared milestone effect, the same finding is mandatory when repository status documents disagree, more than one milestone would be `In progress`, the next milestone is activated prematurely, or the PR claims completion without satisfying the active plan's acceptance criteria. The reviewer never edits closeout files.

Marking the PR ready freezes the independently reviewed and verified head. Any later commit makes the previous review and verification stale. When authorized and necessary, return the PR to draft, refresh the brief, packet, milestone effect, and exact SHA, rerun proportional local verification and GitHub checks, obtain a new independent exact-head review, and mark ready again only after every gate passes. A review-only request reports the stale-head blocker without changing files or PR state.

### Agent-assisted review and merge

Use the repository skill for a repeatable gate:

```text
Use $review-verify-merge-pr to review PR #<number>; if every local and GitHub gate completes successfully, mark the exact reviewed head ready and perform the guarded squash merge.
```

The workflow separates responsibilities without splitting the task. After creating or updating the draft, recording its concise review brief, and assembling the reviewer packet, the same root agent invokes the project `pr-reviewer` agent for an independent, read-only review of the exact head SHA while the pull request remains a draft. Independent review and required checks may run in parallel, but the root agent waits for both to finish successfully before readiness or a merge attempt. The root agent stays active, verifies that same PR head, runs the repository checks appropriate to the changed proof surface, reconciles the review with GitHub checks and unresolved threads, and alone performs authorized state changes.

A draft PR may leave draft or proceed to a merge attempt only when:

- the concise PR brief and reviewer packet are complete, current, and consistent;
- the declared milestone effect matches the diff, roadmap, README, active plan, and evidence, with every required closeout document committed before final review for `completes`;
- the independently reviewed head SHA is still GitHub's current head;
- the affected local verification commands passed for that SHA;
- no blocking correctness, architecture, regression, test, security, public-contract, packaging, or evidence finding remains;
- no requested change or unresolved review thread remains;
- the diff stays inside its authorized scope and contains no secret or generated-artifact violation;
- GitHub reports no merge conflict or unsatisfied base-update policy;
- every mandatory local and GitHub check has completed successfully for the exact head, including `Required checks` and any applicable protected live-verification status; and
- GitHub's effective `main` protection requires changes through a pull request, strictly requires the Actions `Required checks` status and conversation resolution, applies to administrators without a bypass, and prohibits force pushes and deletion. Any applicable protected live status must also be required directly or through a server-enforced dependency. The GitHub APIs and `gh pr checks <number> --required` must confirm that enforcement.

Pending, in-progress, failed, cancelled, timed-out, skipped, or missing mandatory checks are blockers. Do not leave draft or invoke the merge command until all mandatory checks are terminal-success for the exact reviewed head.

Review, status, readiness-assessment, and blocker requests are read-only by default. The default implementation-PR lifecycle starts only when the current request authorizes both implementing changes and creating or updating the resulting pull request; local implementation alone does not authorize a commit, push, pull request, readiness change, or merge. That implementation-and-PR request conditionally authorizes the root agent to make the necessary in-scope commit and push, maintain the concise PR brief and reviewer packet, invoke independent exact-head review, fix in-scope findings, mark the clean reviewed head ready, and make the guarded native squash-merge attempt after all gates pass. The root agent continues through those stages without requesting another confirmation.

The latest user instruction controls. Within an already-authorized implementation-PR lifecycle, `keep draft`, `remain draft`, or `do not mark ready` blocks both readiness and merge, while `do not merge` permits readiness after every gate passes but blocks the merge command. These opt-outs never create state-change authority in a review-only task. An instruction to create the pull request as a draft records the mandatory starting state and is not a keep-draft opt-out. An explicit request to mark an existing pull request ready authorizes readiness only; an explicit request to merge an existing pull request authorizes the same guarded readiness-and-merge path. In-scope findings may be fixed under the original implementation-PR authorization, but a material scope expansion requires user direction.

For an authorized implementation-PR lifecycle without an applicable opt-out, or an explicit request to merge an existing pull request, the root agent:

1. waits while the pull request remains draft until every mandatory local and GitHub check has completed successfully;
2. refreshes the GitHub head SHA, required checks, reviews, requested changes, unresolved review threads, branch protection, mergeability, scope, secrets, generated artifacts, and latest user instruction;
3. confirms the refreshed head is the independently reviewed and locally verified SHA and every gate still passes;
4. marks the draft ready;
5. refreshes the same GitHub state and latest user instruction again immediately before the merge command; and
6. invokes GitHub native auto-merge syntax as a guarded immediate squash-merge attempt:

```bash
gh pr merge <number> --auto --squash --match-head-commit <reviewed-head-sha>
```

`--match-head-commit` is only a command-time head precondition. It does not provide durable protection for a queued auto-merge request after the command returns. Because all checks and gates are already green, the pull request is expected to merge immediately. Inspect its state at once; if GitHub leaves it open with auto-merge queued, immediately run `gh pr merge <number> --disable-auto`, verify the request is disabled and the pull request remains unmerged, and report the blocker. Never leave a queued request active or wait for it to merge after a later head update.

Re-evaluate the latest user instruction immediately before readiness and again before the merge command. If a keep-draft opt-out arrives after readiness, disable any auto-merge request, run `gh pr ready <number> --undo`, and verify that the pull request is draft and unmerged. If a do-not-merge instruction arrives after readiness, disable any auto-merge request and verify that the pull request remains unmerged. Then report the new boundary instead of continuing.

If the head changes before the merge completes, disable any auto-merge request, return a ready pull request to draft with `gh pr ready <number> --undo` when authorized and applicable, refresh the concise PR brief and reviewer packet, and restart local verification, independent review, milestone-effect consistency, and every GitHub gate for the new SHA.

Never use `--admin`, bypass branch protection, dismiss valid feedback, force a merge, weaken required checks, or merge a different head than the one reviewed. After GitHub merges the pull request, inspect the workflow run created for the resulting `main` commit, fetch remote `main`, and reread the roadmap and every relevant status document from that ref. Assert that the declared milestone effect landed consistently. If the promised transition is absent or inconsistent, report the closeout as incomplete; do not let the reviewer write files and do not create or push a follow-up commit without explicit authorization.

Keep normal GitHub Actions deterministic, read-only, and secretless; do not place an autonomous merger, write token, PAT, merge logic, or `pull_request_target` in `ci.yml`. Keep `main` protection configured in GitHub to require the pull-request path, strict `Required checks`, conversation resolution, and any applicable protected live status, enforce the rule for administrators without bypass, and prohibit force pushes and deletion. Repository skills and custom agents define the review procedure, while GitHub remains the enforcement and audit boundary.
