# Development Workflow

## Purpose

Use one deterministic local workflow and the same proof boundaries in GitHub Actions. MCP tools may improve discovery and remote coordination, but they do not replace builds or tests.

## Local checks

Run commands from the repository root:

```bash
# Secrets and whitespace, including untracked files
./scripts/check.sh --hygiene

# Kotlin bridge tests plus hygiene
./scripts/check.sh --quick

# Complete verified P0 path
./scripts/check.sh --full
```

Calling `./scripts/check.sh` without an argument is equivalent to `--full`.

The full check builds the XCFramework once and reuses it for Swift Package tests and the sample build. Standalone Swift scripts still build their own framework unless `UAC_SKIP_XCFRAMEWORK_BUILD=1` is set by the orchestrating check.

P1 currently has focused host-side checks while its samples and CI jobs are still being built:

```bash
./gradlew :bridge:jvmTest
./gradlew :bridge:testAndroidHostTest
./gradlew :bridge:bundleAndroidMainAar
```

These commands prove JVM shared behavior, Android host-side shared behavior, and Android library packaging. They do not prove an Android application, emulator, or physical device.

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
- `Apple interoperability POC` runs the complete verified suite on macOS with Java 21.
- `Required checks` provides one stable branch-protection status.

Superseded runs on the same pull request or branch are cancelled. The workflow grants read-only repository permissions and does not inherit or require secrets. Failed Apple checks retain deterministic test evidence for seven days.

`.github/dependabot.yml` groups monthly GitHub Actions and Gradle updates so workflow and build dependencies do not silently age. Review and verify those pull requests like any other dependency change; do not auto-merge them without the required checks.

The current CI proves P0 only. Add Linux JVM and Android jobs during P1, after those targets and their deterministic commands exist. Do not label Android, JVM, device, provider, gateway, or release behavior as CI-verified before the corresponding job runs successfully.

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
3. Run `./scripts/check.sh --quick` before committing.
4. Run `./scripts/check.sh --full` before requesting review when the package baseline changes.
5. Push and use the GitHub connector or GitHub UI to inspect every required job.
6. Update roadmap and README status only after the exact acceptance evidence exists.
