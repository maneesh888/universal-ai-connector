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

# Kotlin bridge tests plus hygiene
./scripts/check.sh --quick

# Complete deterministic Kotlin, JVM-consumer, and Apple path
./scripts/check.sh --full
```

Calling `./scripts/check.sh` without an argument is equivalent to `--full`.

The quick and full checks run shared JVM tests, iOS Simulator bridge tests, and the JVM console consumer check. The full check then builds the XCFramework once and reuses it for Swift Package tests and the iOS sample build. Standalone Swift scripts still build their own framework unless `UAC_SKIP_XCFRAMEWORK_BUILD=1` is set by the orchestrating check.

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
- `JVM + Android (Linux)` runs JVM shared tests, the JVM console consumer, Android host tests, and Android AAR packaging with Java 21.
- `JVM (Windows)` runs JVM shared tests and the JVM console consumer with Java 21.
- `Apple POC + JVM (macOS)` runs JVM shared tests, the JVM console consumer, and the complete verified Apple suite with Java 21.
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

## MCP and connector routing

Use optional tools when they are available:

| Tool | Use | Proof boundary |
|---|---|---|
| GitHub connector | Read PRs, issues, workflow runs, job logs, and artifacts; create or update remote work only with authorization | Remote coordination and CI evidence |
| OpenAI Developer Docs MCP | Verify current OpenAI API contracts when P4 or later activates them | Documentation evidence, not implementation proof |
| Xcode or simulator MCP | Inspect simulator state and collect visual or lifecycle evidence | Valid only for the exact simulator/device path exercised |
| Browser tooling | Verify future web samples or published documentation | Browser behavior only |

If an MCP is unavailable, use repository scripts and report the missing proof surface. Never add MCP SDKs, credentials, or tool-specific DTOs to the runtime package. Never store tokens in repository configuration; use OAuth or environment-backed credentials.

## Pull-request workflow

1. Select one roadmap work package.
2. Run targeted tests while iterating.
3. Build affected consumer samples when public APIs or package boundaries change.
4. Run `./scripts/check.sh --quick` before committing.
5. Run `./scripts/check.sh --full` before requesting review when the package baseline changes.
6. Push and use the GitHub connector or GitHub UI to inspect every required job.
7. Update roadmap and README status only after the exact acceptance evidence exists.

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
