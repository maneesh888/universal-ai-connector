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
- `JVM + Android (Linux)` runs JVM shared tests, Android host tests, and Android AAR packaging with Java 21.
- `JVM (Windows)` runs JVM shared tests with Java 21.
- `Apple POC + JVM (macOS)` runs JVM shared tests and the complete verified Apple suite with Java 21.
- `Required checks` provides one stable branch-protection status.

Superseded runs on the same pull request or branch are cancelled. The workflow grants read-only repository permissions and does not inherit or require secrets. Failed Apple checks retain deterministic test evidence for seven days.

`.github/dependabot.yml` groups monthly GitHub Actions and Gradle updates so workflow and build dependencies do not silently age. Review and verify those pull requests like any other dependency change; do not auto-merge them without the required checks.

The cross-platform jobs are configured locally but are not evidence until they pass on GitHub. The macOS Apple job remains responsible for Kotlin/Native, XCFramework, Swift Package, and iOS sample proof. JVM console and Android application consumer checks must be added when those samples exist. Do not label Android, JVM host-OS portability, device, consumer integration, provider, gateway, or release behavior as CI-verified before the corresponding job runs successfully.

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
