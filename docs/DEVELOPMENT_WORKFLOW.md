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
# Secrets and whitespace, including untracked files
./scripts/check.sh --hygiene

# Kotlin bridge tests, Kotlin consumer apps, plus hygiene
./scripts/check.sh --quick

# Complete deterministic Kotlin-consumer and Apple path
./scripts/check.sh --full
```

Calling `./scripts/check.sh` without an argument is equivalent to `--full`.

The quick and full checks run deterministic shell-script tests, shared JVM tests, iOS Simulator bridge tests, the JVM console consumer, and the Android application's controller tests and debug APK assembly. The full check then builds the XCFramework once and reuses it for Swift Package tests and the iOS sample build. Standalone Swift scripts still build their own framework unless `UAC_SKIP_XCFRAMEWORK_BUILD=1` is set by the orchestrating check.

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

## Optional pre-commit hook

Enable the committed hook for the current clone:

```bash
./scripts/install-hooks.sh
```

The installer configures the local repository to use `.githooks/`. It refuses to replace a different configured hooks path. The pre-commit hook runs `./scripts/check.sh --quick`.

The hook is a local feedback mechanism. GitHub CI remains the remote enforcement boundary.

## GitHub Actions

`.github/workflows/ci.yml` runs for pull requests, pushes to `main`, and manual dispatches:

- `Repository hygiene` runs secret and whitespace checks on Linux.
- `JVM + Android (Linux)` runs JVM shared tests, the JVM console consumer, Android host tests, Android AAR packaging, and the Android application consumer check with Java 21.
- `JVM (Windows)` runs JVM shared tests and the JVM console consumer with Java 21.
- `Apple POC + JVM (macOS)` runs JVM shared tests, both Kotlin consumer checks, and the complete verified Apple suite with Java 21.
- `Required checks` provides one stable branch-protection status.

Superseded runs on the same pull request or branch are cancelled. The workflow grants read-only repository permissions and does not inherit or require secrets. Failed Apple checks retain deterministic test evidence for seven days.

`.github/dependabot.yml` groups monthly GitHub Actions and Gradle updates so workflow and build dependencies do not silently age. Review and verify those pull requests like any other dependency change; do not auto-merge them without the required checks.

GitHub Actions run [29730678994](https://github.com/maneesh888/universal-ai-connector/actions/runs/29730678994) passed the complete matrix on July 20, 2026. It proves the JVM console consumer on Linux, Windows, and macOS, Android host tests, AAR packaging, and the Android application consumer on Linux and macOS, the complete Apple P0 suite on macOS, repository hygiene, and the stable `Required checks` aggregator. The macOS Apple job remains responsible for Kotlin/Native, XCFramework, Swift Package, and iOS sample proof. Do not label emulator/device, provider, gateway, distribution, or release behavior as CI-verified before the corresponding evidence exists.

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

After the workflow has passed on GitHub, protect `main` by requiring the single `Required checks` status. Configure branch protection in GitHub rather than encoding repository-admin assumptions in local scripts.

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

## Pull-request workflow

1. Select one roadmap work package.
2. Run targeted tests while iterating.
3. Build affected consumer samples when public APIs or package boundaries change.
4. Run `./scripts/check.sh --quick` before committing.
5. Run `./scripts/check.sh --full` before requesting review when the package baseline changes.
6. Create the pull request as a draft. GitHub Actions run while the pull request remains a draft.
7. Add or refresh the PR review brief from the implementation request, linked issue or plan, decisions, scope, exact evidence, and current head SHA.
8. Push and use `gh` to inspect every required job while the pull request is still a draft.
9. Have the project `pr-reviewer` agent independently review the exact head SHA using the current structured review brief.
10. Fix every blocking finding. If a fix or any other update changes the head SHA, refresh the brief and restart independent review, local verification, required-check verification, thread inspection, and mergeability inspection for the new SHA.
11. Update roadmap and README status only after the exact acceptance evidence exists.

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
Use $review-verify-merge-pr to review PR #<number>; if every gate passes, mark it ready and enable squash auto-merge for the exact reviewed head.
```

The workflow separates responsibilities. The project `pr-reviewer` agent performs an independent, read-only review of the exact head SHA with the current structured review brief. The root agent verifies that same PR head, runs the repository checks appropriate to the changed proof surface, reconciles the review with GitHub checks and unresolved threads, and alone performs authorized state changes.

A draft PR may be marked ready only when:

- the review brief is complete and current;
- the independently reviewed head SHA is still GitHub's current head;
- the affected local verification commands passed for that SHA;
- no blocking correctness, architecture, regression, test, security, public-contract, packaging, or evidence finding remains;
- every required GitHub check, including `Required checks`, has completed successfully;
- no requested change or unresolved review thread remains;
- GitHub reports the PR mergeable under the repository's branch rules.

Review requests are read-only by default. If the current user request explicitly authorizes marking a draft ready without merging, the root agent may do so only after every readiness gate passes. If the current user request explicitly authorizes merging and every gate passes, the root agent:

1. refreshes the GitHub head SHA, required checks, requested changes, unresolved review threads, and mergeability;
2. confirms the refreshed head is the independently reviewed and locally verified SHA;
3. marks the draft ready;
4. refreshes the same GitHub state again immediately before enabling auto-merge; and
5. enables GitHub native auto-merge with squash and exact-head protection:

```bash
gh pr merge <number> --auto --squash --match-head-commit <reviewed-head-sha>
```

Native auto-merge preserves GitHub as the enforcement boundary; it is not authorization to merge an unreviewed revision. If the head changes before GitHub completes the merge, disable an already-enabled attempt with `gh pr merge <number> --disable-auto` when applicable, or abandon the invalidated attempt. Refresh the review brief, fix findings, and restart the entire gate for the new SHA.

Never use `--admin`, bypass branch protection, dismiss valid feedback, force a merge, weaken required checks, or merge a different head than the one reviewed. After GitHub merges the pull request, inspect the workflow run created for the resulting `main` commit and report its result.

Keep GitHub Actions deterministic and read-only; do not place an autonomous merger, write token, PAT, or merge logic in `ci.yml`. Configure `main` branch protection in GitHub to require `Required checks` and conversation resolution. Repository skills and custom agents define the review procedure, while GitHub remains the enforcement and audit boundary.
