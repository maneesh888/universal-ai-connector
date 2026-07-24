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
| Documentation or plans | `./scripts/check.sh --hygiene` |

The quick gate covers hygiene, deterministic shell-script behavior, shared JVM and Android behavior, Android AAR packaging, iOS Simulator bridge behavior, and the JVM and Android consumers. The full gate adds combined device-and-simulator XCFramework validation, Swift Package tests, the simulator sample build, and generic-device link verification.

When a milestone adds an authoritative contract, provider, gateway, publication, or compatibility command, record it in that active plan and add it to the appropriate cumulative gate when it becomes supported baseline behavior.

## Hooks

Enable committed hooks once per clone or worktree before the first commit or push in an implementation lifecycle:

```bash
./scripts/install-hooks.sh
git config --local --get core.hooksPath
```

The path must be `.githooks`.

- Pre-commit rejects unstaged tracked changes and untracked files, then runs `./scripts/check.sh --quick` against the proposed contents.
- Pre-push accepts only refs resolving to checked-out `HEAD`, requires a clean worktree before and after verification, and runs `./scripts/check.sh --full`.
- Never use `--no-verify`. Missing toolchains and failed checks are blockers.

These hook requirements are safety gates; they do not make every task a Release analysis task.

## GitHub Actions

`.github/workflows/ci.yml` runs on pull requests, pushes to `main`, and manual dispatch:

- `Repository hygiene` validates the fail-closed scanner, secrets, and whitespace on Linux.
- `JVM + Android (Linux)` verifies shared behavior, JVM consumption, Android host behavior, AAR packaging, and the Android consumer.
- `JVM (Windows)` verifies the JVM tests and consumer with Java 21.
- `Apple + JVM (macOS)` runs the complete local gate, including Apple packaging and sample proof.
- `Required checks` supplies the stable branch-protection status.

Pull-request jobs check out the exact PR head. Third-party actions remain pinned, workflow permissions remain read-only, and ordinary CI remains secretless. CI does not prove emulator/device execution, live providers, gateways, distribution, or release behavior without matching evidence.

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
- successful exact-head required CI and any applicable protected live status;
- an independent exact-head review with no blocking finding;
- no requested changes or unresolved threads;
- verified branch protection and mergeability; and
- proof claims limited to exercised paths.

Use `$review-verify-merge-pr` for the detailed read-only review, readiness, stale-head handling, guarded merge command, and post-merge assertions. A head change invalidates prior Release evidence.
