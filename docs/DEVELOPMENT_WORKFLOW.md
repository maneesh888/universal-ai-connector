# Development Workflow

## Purpose

Use proportional local verification while preserving deterministic Release proof. This file owns check selection and proof boundaries; `AGENTS.md` owns authority and context routing, the roadmap owns milestone status, active plans own package-specific acceptance, and `$review-verify-merge-pr` owns detailed Release choreography.

## Proof levels

Keep these claims separate:

1. **Behavior proof:** focused tests validate deterministic connector semantics.
2. **Packaging proof:** the target artifact or module assembles successfully.
3. **Consumer integration proof:** a thin host sample consumes the public package boundary and compiles or runs its documented first-use path.
4. **Distribution proof:** a clean fixture resolves a published artifact from its documented location.

Do not infer a stronger level from a weaker one. Distribution proof remains P8 work.

## Modes and cumulative gates

Canonical-contract changes additionally require **contract conformance proof**: authoritative schemas are meta-valid, every fixture has one documented schema or semantic result, production encodings stay schema-valid for the governed corpus, and the embedded multiplatform mirror has zero drift from the tracked bundle.

Run `./scripts/check-environment.sh` on a new clone before enabling hooks. It is a read-only
preflight: `--hygiene` validates standard shell, Git, Ruby YAML parsing, and secret-scan tools;
`--quick` also validates Java 21 and the Android SDK; and `--full` also validates the Apple
toolchain. Normal repository gates invoke the matching preflight automatically. A preflight
failure is a machine setup blocker, not permission to weaken the affected test or bypass a hook.

Run targeted checks while editing, then run only the highest final gate required by the selected mode:

| Mode | Final gate | Additional proof |
|---|---|---|
| Fast | Affected tests; `./scripts/check.sh --hygiene` before handoff when files changed | Only the changed surface |
| Standard | `./scripts/check.sh --quick` | Affected consumer check for public API or package-boundary changes |
| Release | `./scripts/check.sh --full` on exact `HEAD` | Exact-head CI, independent review, merge checks, and active-plan-specific proof |

Calling `./scripts/check.sh` without an argument is equivalent to `--full`.

Mandatory hooks may execute a higher cumulative gate at commit or push. Do not manually repeat the same gate immediately before the hook unless the intervening state changed.

## Targeted check routing

Use the smallest commands that exercise the changed surface:

| Changed surface | Targeted verification |
|---|---|
| Canonical contracts, schemas, or fixtures | `./scripts/check-contracts.sh --all` |
| Shared Kotlin behavior | `./gradlew :bridge:jvmTest` |
| Android host behavior | `./gradlew :bridge:testAndroidHostTest` |
| Android packaging or consumer | `./gradlew :bridge:bundleAndroidMainAar` and/or `./gradlew :samples:android:consumerCheck` |
| JVM consumer | `./gradlew :samples:jvm-console:consumerCheck`; use `:samples:jvm-console:run` when runtime output matters |
| Kotlin Apple bridge | `./gradlew :bridge:iosSimulatorArm64Test` |
| Swift façade | `./scripts/test-swift-package.sh` |
| XCFramework or Apple package boundary | `./scripts/build-xcframework.sh` |
| iOS sample integration | `./scripts/build-sample.sh`; add `./scripts/build-sample-device.sh` for device-slice link changes |
| Android launch script | `./scripts/tests/run-android-sample-test.sh`; use `./scripts/run-android-sample.sh` only when device/UI lifecycle proof is required |
| Shell, hooks, or secret scanning | `./scripts/check.sh --hygiene` and the affected script regression |
| Distribution identity, version, POM, assets, or host baselines | `./scripts/check-distribution-metadata.sh` and its metadata/readiness regressions; run `./scripts/check-distribution-readiness.sh` only on a credentialed release host |
| Documentation or plans | `./scripts/check.sh --hygiene` |

The quick gate covers hygiene, deterministic shell-script behavior, canonical contract layout and conformance on JVM, Android host, and iOS Simulator, shared JVM and Android behavior, Android AAR packaging, iOS Simulator bridge behavior, and the JVM and Android consumers. Packaging checks reject retired POC classes and platform-boundary leaks. The full gate adds combined device-and-simulator XCFramework validation, Swift Package tests, the simulator sample build, and generic-device link verification.

When a milestone adds an authoritative contract, provider, gateway, publication, or compatibility command, record it in that active plan and add it to the appropriate cumulative gate when it becomes supported baseline behavior. P8-A adds the credential-free distribution metadata and regression checks to hygiene; they validate no credential and make no remote-publication claim.

P4 adds `./scripts/check-live.sh openai` as a separate exact-head provider gate. It is not part of
`--quick` or normal CI. The pre-push hook compares the candidate with `origin/main` through
`scripts/live-impact.sh`; P5-A replaces the boolean result with `none` or an ordered delivered
provider set, P6-A extends the bounded set to OpenRouter, and P7-B extends it to the selected
OpenAI-compatible Gateway. A
provider-specific change selects its delivered gate, a shared or ambiguous live change selects
every delivered gate, and unrelated changes remain credential-free. The hook removes every
documented provider input from the full deterministic gate and every non-selected provider input
from a selected live gate. OpenAI, Anthropic, OpenRouter, and the Gateway are the delivered real
gates after P7-B adds the Gateway runner, task, and selection to the existing three routes. A
generic-adapter change selects both its representative OpenRouter proof and the Gateway proof. The
initial PR body and every affected update record exact-head local
evidence for the secretless `Required live verification` policy check. GitHub does not rerun
provider tests or receive provider credentials.

Machine-local live inputs persist only in the primary checkout path printed by
`./scripts/local-config.sh live-env-path`. The helper derives that checkout from
`git rev-parse --path-format=absolute --git-common-dir` and the shared worktree metadata, so the
same ignored regular file is used from the primary checkout and every linked worktree. Ignored and
untracked files are not synchronized by Git: each clone on each machine needs its own file. Use a
trusted secret manager to materialize a mode-`600` regular file at the resolved path when
cross-machine synchronization is needed; never copy credentials into disposable worktrees.

`./scripts/local-config.sh validate-live-env` parses the file only to validate its syntax, while
`./scripts/check-live.sh` is the only repository command that loads selected values. Both accept
only the documented `.env.live` literal-assignment format and variable allowlist; direct Gradle live
tasks continue to accept process environment only. Non-empty process values override file values, and
`UAC_LIVE_ENV_FILE` may select only `.env.live` or `.env.live.<name>` in the same canonical trusted
directory. The quick/full deterministic gates and ordinary CI never require or load local live
configuration.

## Dependency updates

The Gradle wrapper pins both its distribution version and the official binary-distribution
SHA-256. Every ordinary CI job that configures Gradle explicitly enables wrapper-JAR validation;
the hygiene gate rejects removal or drift of either protection.

Dependabot checks Gradle dependencies monthly, and routine version-update pull requests are limited
to SemVer patches and minors. Android build tooling, Compose, and Lifecycle patches use separate
groups so an incompatible host-platform dependency cannot invalidate otherwise independent
updates. Minor updates and other patches remain separate unless a cohesive group is added
deliberately.

Major version upgrades require an explicit maintenance task that evaluates toolchain, platform,
public-contract, packaging, and live-impact consequences before changing the accepted baseline.
Repository settings keep Dependabot alerts and automated security-update pull requests enabled;
those security updates remain separate from the configured routine version-update levels. A failed
automated dependency pull request is closed rather than merged, rebased repeatedly, or expanded
into an incidental platform upgrade.

## Hooks

Enable committed hooks once per clone or worktree before the first commit or push in an implementation lifecycle:

```bash
./scripts/check-environment.sh --full
./scripts/install-hooks.sh
git config --local --get core.hooksPath
```

The path must be `.githooks`.

- Pre-commit rejects unstaged tracked changes and untracked files, then runs `./scripts/check.sh --quick` against the proposed contents.
- Pre-push accepts only refs resolving to checked-out `HEAD`, requires a clean worktree before and after verification, and runs `./scripts/check.sh --full`. It then runs every exact-head provider gate selected relative to `origin/main` and fails closed when any selected gate, task, or required process input is absent.
- Never use `--no-verify`. Missing toolchains and failed checks are blockers.

These hook requirements are safety gates; they do not make every task a Release analysis task.

## GitHub Actions

`.github/workflows/ci.yml` runs on pull requests, pushes to `main`, and manual dispatch:

- `Repository hygiene` installs `rg`, runs the fail-closed secret-scan regression, and checks secrets and whitespace on Linux.
- `JVM + Android (Linux)` runs on Ubuntu 24.04 and checks shared contract and behavior tests on JVM and Android host, the JVM console consumer, Android AAR packaging, and the Android application consumer with Java 21.
- `JVM (Windows)` runs on Windows Server 2025 and checks JVM shared contract and behavior tests plus the JVM console consumer with Java 21.
- `Apple + JVM (macOS)` installs `rg` and runs the complete local `--full` suite, including Android library/application, JVM, combined Apple framework, Swift Package, simulator sample, and generic-device link verification, with Java 21.
- `Required checks` provides one stable branch-protection status.

Pull-request jobs check out the exact PR head. Third-party actions remain pinned, workflow permissions remain read-only, and ordinary CI remains secretless. CI does not prove emulator/device execution, live providers, gateways, distribution, or release behavior without matching evidence.

`.github/workflows/live.yml` remains separate from ordinary CI. It classifies live impact
secretlessly and reports `Required live verification`. For an affected head it requires the PR to
record `Local live verification: passed`, the exact head SHA, and the no-retained-secret proof
boundary. It must also state that local execution is contributor-attested and GitHub verifies only
the retained exact-head evidence. Its stable status job uses the credential-free `live-policy`
Environment, whose successful deployment is a server-required merge condition. The protected
`live-provider` Environment is retained with its reviewer protection but is not requested by this
local-only policy; no GitHub job receives provider credentials. An unrelated documentation head
passes without local provider inputs. The `live-policy` deployment is automatic and has no
required reviewer; it validates retained evidence but cannot independently prove that the local
provider call ran.

## Host integration

When public API, packaging, or sample behavior changes:

- compile the affected documented first-use path;
- keep Android and JVM consumers on the same public Kotlin client;
- keep Swift application code on the Swift façade product;
- verify affected cancellation, error, ownership, and cleanup behavior;
- reject internal source imports, manual generated-artifact copying, and undocumented flags; and
- update affected installation, initialization, minimum-version, and limitation documentation.

Remote dependency resolution starts only when P8 activates publication.

## External tools

- Use `gh` for repository, PR, issue, review, workflow, log, artifact, and lifecycle state-changing operations. Use `gh api graphql` for review-thread state.
- Use the GitHub connector only when the user explicitly requests it.
- Use current official provider documentation only after the corresponding provider milestone activates.
- Use Xcode, simulator, or device tooling only for an affected proof surface. Repository scripts remain the deterministic fallback.
- Never make development tools or credentials runtime dependencies.

## Agent efficiency

- Do not create a separate plan when the user already supplied a clear bounded implementation.
- A bounded implementation request starts the complete normal repository lifecycle unless the user states an explicit local, commit, push, PR, draft, or merge opt-out.
- Do not pause for confirmations between branch creation, implementation, verification, commit, push, draft PR publication, in-scope review fixes, readiness, and guarded merge.
- When planning is requested, invoke the read-only `work-package-planner` custom agent without inherited conversation. It uses `$plan-universal-ai-work-package`; pass its compact source-bound work order to implementation.
- Verify work-order source digests; reread only changed or ambiguous source sections.
- Do not delegate routine Fast or Standard work. Delegate only independent investigations, disjoint implementation ownership, specialist proof, or mandatory Release review.
- Give subagents a minimal task packet without inherited conversation history whenever independence allows it.
- Build the detailed reviewer packet just before independent Release review, not throughout implementation.
- Run independent review and GitHub checks concurrently, but do not cross readiness or merge gates until both succeed.

## Release pull requests

Create pull requests as drafts. A Release candidate must have:

- a concise, current PR brief bound to the exact head SHA;
- complete milestone-closeout documents when the milestone effect is `completes`;
- successful full local verification for that head;
- successful exact-head required CI and applicable exact-head local-live policy status;
- an independent exact-head review with no blocking finding;
- no requested changes or unresolved threads;
- verified branch protection and mergeability; and
- proof claims limited to exercised paths.

Use `$review-verify-merge-pr` for the detailed read-only review, readiness, stale-head handling, guarded merge command, and post-merge assertions. A head change invalidates prior Release evidence.
