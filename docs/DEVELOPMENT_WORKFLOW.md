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

The quick and full checks validate shell syntax, secrets, whitespace, shared JVM and Android behavior, Android AAR packaging, iOS Simulator bridge behavior, and the JVM console consumer. The full check then builds the XCFramework once and reuses it for Swift Package tests and the iOS sample build. Standalone Swift scripts still build their own framework unless `UAC_SKIP_XCFRAMEWORK_BUILD=1` is set by the orchestrating check.

P1 currently has focused host-side checks while its samples and CI jobs are still being built:

```bash
./gradlew :bridge:jvmTest
./gradlew :bridge:testAndroidHostTest
./gradlew :bridge:bundleAndroidMainAar
./gradlew :samples:jvm-console:consumerCheck
./gradlew :samples:jvm-console:run
```

The `consumerCheck` task compiles the JVM console against `project(":bridge")`, tests its exact output, and executes its non-interactive entry point. It is consumer-integration proof for the local public Gradle module boundary, not Maven distribution proof. The Android commands prove host-side shared behavior and library packaging; they do not prove an Android application, emulator, or physical device.

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

- `Repository hygiene` runs secret and whitespace checks on Linux.
- `JVM + Android (Linux)` runs JVM shared tests, the JVM console consumer, Android host tests, and Android AAR packaging with Java 21.
- `JVM (Windows)` runs JVM shared tests and the JVM console consumer with Java 21.
- `Apple POC + JVM (macOS)` runs the complete local `--full` suite, including Android, JVM, and Apple verification, with Java 21.
- `Required checks` provides one stable branch-protection status.

Superseded runs on the same pull request or branch are cancelled. The workflow grants read-only repository permissions and does not inherit or require secrets. Failed Apple checks retain deterministic test evidence for seven days.

`.github/dependabot.yml` groups monthly GitHub Actions and Gradle updates so workflow and build dependencies do not silently age. Review and verify those pull requests like any other dependency change; do not auto-merge them without the required checks.

GitHub Actions run [29698575249](https://github.com/maneesh888/universal-ai-connector/actions/runs/29698575249) passed the complete matrix on July 19, 2026. It proves the JVM console consumer on Linux, Windows, and macOS without adding an operating-system job, Android host tests and AAR packaging on Linux, the complete Apple P0 suite on macOS, repository hygiene, and the stable `Required checks` aggregator. The macOS Apple job remains responsible for Kotlin/Native, XCFramework, Swift Package, and iOS sample proof. An Android application consumer check remains outstanding. Do not label device, provider, gateway, distribution, or release behavior as CI-verified before the corresponding job runs successfully.

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
7. Add or refresh the PR review brief from the implementation request, linked issue or plan, decisions, scope, exact head SHA, and exact local evidence.
8. Use `gh` to inspect every required job and do not treat the PR as merge-ready until the stable `Required checks` job passes.
9. Update roadmap and README status only after the exact acceptance evidence exists.

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
Use $review-verify-merge-pr to review PR #<number>; if it is clean, mark it ready and merge it.
```

The workflow separates responsibilities. The project `pr-reviewer` agent performs an independent, read-only review. The root agent verifies the exact PR head, runs the repository checks appropriate to the changed proof surface, reconciles the review with GitHub checks and unresolved threads, and alone performs authorized state changes.

A PR is merge-ready only when:

- the reviewed and locally verified head SHA is still GitHub's current head;
- no blocking correctness, architecture, regression, test, security, public-contract, packaging, or evidence finding remains;
- every required GitHub check, including `Required checks`, has completed successfully;
- no requested change or unresolved blocking review thread remains;
- the affected local verification commands pass; and
- GitHub reports the PR mergeable under the repository's branch rules.

Review requests are read-only by default. The agent may mark a draft ready and merge only when the current user request explicitly authorizes those actions. It must never force a merge, use an administrator bypass, weaken branch protection, dismiss valid feedback, or merge a different head than the one reviewed. If the head changes during review or verification, start the gate again for the new SHA.

Keep GitHub Actions deterministic and read-only; do not place an autonomous merger or write token in `ci.yml`. Configure `main` branch protection in GitHub to require `Required checks` and conversation resolution. Repository skills and custom agents define the review procedure, while GitHub remains the enforcement and audit boundary.
