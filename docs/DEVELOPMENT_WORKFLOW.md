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

The quick and full checks validate shell syntax, secrets, whitespace, deterministic shell-script behavior, shared JVM and Android behavior, Android AAR packaging, iOS Simulator bridge behavior, the JVM console consumer, and the Android application's controller tests and debug APK assembly. The secret scanner requires `rg`, fails closed when the tool is missing or errors, reports matches without printing matched credential material, and has a regression test for those properties in every hygiene run. The full check then builds the XCFramework once and reuses it for Swift Package tests and the iOS sample build. Standalone Swift scripts still build their own framework unless `UAC_SKIP_XCFRAMEWORK_BUILD=1` is set by the orchestrating check.

P1 currently has focused host-side checks while its samples and CI jobs are still being built:

```bash
./gradlew :bridge:jvmTest
./gradlew :bridge:testAndroidHostTest
./gradlew :bridge:bundleAndroidMainAar
./gradlew :samples:jvm-console:consumerCheck
./gradlew :samples:jvm-console:run
./gradlew :samples:android:consumerCheck
./scripts/run-android-sample.sh
```

The JVM `consumerCheck` compiles the console against `project(":bridge")`, tests its exact output, and executes its non-interactive entry point. The Android `consumerCheck` compiles the separate Compose application against the same public module, runs its controller tests, and assembles its debug APK. These are consumer-integration proofs for the local public Gradle module boundary, not Maven distribution proof.

`run-android-sample.sh` requires a booted emulator or connected device, installs the debug APK, and launches the app. Set `UAC_ANDROID_SERIAL` to use the same selected device for installation and launch, or `UAC_ADB` to select an `adb` binary. This optional device path is local evidence; CI intentionally uses deterministic unit/build checks and does not boot an emulator.

As P1 samples land, add their build/run commands to this document and the top-level check. Sample verification must use public Gradle module dependencies or the Swift Package product; do not compile shared source files directly into a sample.

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
- `JVM + Android (Linux)` runs JVM shared tests, the JVM console consumer, Android host tests, Android AAR packaging, and the Android application consumer check with Java 21.
- `JVM (Windows)` runs JVM shared tests and the JVM console consumer with Java 21.
- `Apple POC + JVM (macOS)` installs `rg` and runs the complete local `--full` suite, including Android library/application, JVM, and Apple verification, with Java 21.
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
8. Add or refresh the PR review brief from the implementation request, linked issue or plan, decisions, scope, exact head SHA, and exact local evidence.
9. In the same active task, the root agent that created or updated the draft must invoke the project `pr-reviewer` agent to independently review the exact head SHA using the current structured review brief. Review and required checks may continue in parallel while the pull request remains a draft; every readiness and merge gate still waits for successful completion.
10. Fix every blocking finding while the pull request remains a draft. If a fix or any other update changes the head SHA, disable any auto-merge request, return the pull request to draft when necessary, and restart local verification, the review brief, independent review, required-check inspection, protection inspection, thread inspection, and mergeability inspection for the new SHA.
11. Use `gh` to wait for every mandatory GitHub check to complete successfully on the exact reviewed head, including `Required checks` and any applicable protected live-verification status. Pending, in-progress, failed, cancelled, timed-out, skipped, or missing mandatory checks block both readiness and a merge attempt.
12. Verify `main` protection through the GitHub APIs and `gh pr checks <number> --required`. With explicit current merge authorization and every gate green, mark the exact reviewed head ready, refresh the head and all gates immediately, and make the guarded native squash-merge attempt. If GitHub queues auto-merge instead of merging immediately, disable it at once, keep the pull request unmerged, and report the blocker.
13. Update roadmap and README status only after the exact acceptance evidence exists.

### Review brief

The PR description is the durable handoff between implementation and independent review. Before review, the root agent writes a neutral brief from the current user request, linked issue or plan, repository requirements, and verified implementation evidence. It passes the brief and source links to the reviewer without supplying expected findings or a desired conclusion.

Use this structure:

```markdown
## Review brief

### Problem
The user or engineering problem being solved.

### Requirement sources
- User request, issue, plan, or decision link

### Requirements and acceptance criteria
- Required behavior and observable completion condition

### Implementation decisions
- Important approach or constraint selected during implementation

### Out of scope
- Behavior deliberately excluded from this PR

### Evidence and proof boundaries
- Exact checks executed and behavior not exercised

### Review target head
`<full commit SHA>`
```

Refresh the brief when requirements, scope, evidence, or the head SHA materially changes. A missing, ambiguous, stale, or internally inconsistent brief blocks merge readiness. Requirements that existed only in a private implementation conversation must be summarized here; the reviewer cannot recover context that was never recorded or passed to it.

### Agent-assisted review and merge

Use the repository skill for a repeatable gate:

```text
Use $review-verify-merge-pr to review PR #<number>; if every local and GitHub gate completes successfully, mark the exact reviewed head ready and perform the guarded squash merge.
```

The workflow separates responsibilities without splitting the task. After creating or updating the draft and recording its current review brief, the same root agent invokes the project `pr-reviewer` agent for an independent, read-only review of the exact head SHA while the pull request remains a draft. Independent review and required checks may run in parallel, but the root agent waits for both to finish successfully before readiness or a merge attempt. The root agent stays active, verifies that same PR head, runs the repository checks appropriate to the changed proof surface, reconciles the review with GitHub checks and unresolved threads, and alone performs authorized state changes.

A draft PR may leave draft or proceed to a merge attempt only when:

- the review brief is complete and current;
- the independently reviewed head SHA is still GitHub's current head;
- the affected local verification commands passed for that SHA;
- no blocking correctness, architecture, regression, test, security, public-contract, packaging, or evidence finding remains;
- no requested change or unresolved review thread remains;
- the diff stays inside its authorized scope and contains no secret or generated-artifact violation;
- GitHub reports no merge conflict or unsatisfied base-update policy;
- every mandatory local and GitHub check has completed successfully for the exact head, including `Required checks` and any applicable protected live-verification status; and
- GitHub's effective `main` protection requires changes through a pull request, strictly requires the Actions `Required checks` status and conversation resolution, applies to administrators without a bypass, and prohibits force pushes and deletion. Any applicable protected live status must also be required directly or through a server-enforced dependency. The GitHub APIs and `gh pr checks <number> --required` must confirm that enforcement.

Pending, in-progress, failed, cancelled, timed-out, skipped, or missing mandatory checks are blockers. Do not leave draft or invoke the merge command until all mandatory checks are terminal-success for the exact reviewed head.

Review requests are read-only by default. A request to create or update a pull request authorizes the root agent to invoke independent read-only review, but it does not by itself authorize changing draft state or merging. The user may bundle those permissions into one request—for example, create a draft pull request, review it, fix in-scope findings, and if clean mark it ready and merge it. When the current request contains that bundled authorization, the root agent must continue the same active task through every applicable gate without requesting a second confirmation. If the current user request explicitly authorizes marking a draft ready without merging, the root agent may do so only after every readiness gate passes. If the current user request explicitly authorizes merging and every gate passes, the root agent:

1. waits while the pull request remains draft until every mandatory local and GitHub check has completed successfully;
2. refreshes the GitHub head SHA, required checks, reviews, requested changes, unresolved review threads, branch protection, mergeability, scope, secrets, and generated artifacts;
3. confirms the refreshed head is the independently reviewed and locally verified SHA and every gate still passes;
4. marks the draft ready;
5. refreshes the same GitHub state again immediately before the merge command; and
6. invokes GitHub native auto-merge syntax as a guarded immediate squash-merge attempt:

```bash
gh pr merge <number> --auto --squash --match-head-commit <reviewed-head-sha>
```

`--match-head-commit` is only a command-time head precondition. It does not provide durable protection for a queued auto-merge request after the command returns. Because all checks and gates are already green, the pull request is expected to merge immediately. Inspect its state at once; if GitHub leaves it open with auto-merge queued, immediately run `gh pr merge <number> --disable-auto`, verify the request is disabled and the pull request remains unmerged, and report the blocker. Never leave a queued request active or wait for it to merge after a later head update.

If the head changes before the merge completes, disable any auto-merge request, return a ready pull request to draft with `gh pr ready <number> --undo` when applicable, refresh the review brief, and restart local verification, independent review, and every GitHub gate for the new SHA.

Never use `--admin`, bypass branch protection, dismiss valid feedback, force a merge, weaken required checks, or merge a different head than the one reviewed. After GitHub merges the pull request, inspect the workflow run created for the resulting `main` commit and report its result.

Keep normal GitHub Actions deterministic, read-only, and secretless; do not place an autonomous merger, write token, PAT, merge logic, or `pull_request_target` in `ci.yml`. Keep `main` protection configured in GitHub to require the pull-request path, strict `Required checks`, conversation resolution, and any applicable protected live status, enforce the rule for administrators without bypass, and prohibit force pushes and deletion. Repository skills and custom agents define the review procedure, while GitHub remains the enforcement and audit boundary.
