# Universal AI Connector Workflow

## Scope

This repository contains a verified iOS-Kotlin interoperability POC and the plans for evolving it into the Universal AI Connector V2 package.

The next approved scope is cross-platform targets, shared interoperability tests, and thin iOS, Android, and JVM sample clients. Do not add provider adapters, Ktor networking, canonical AI contracts, gateway integration, or OpenKeyboard integration before their work package is active in `docs/plans/universal-ai-connector-v2.md`.

## Start Every Task

1. Resolve the root with `git rev-parse --show-toplevel`.
2. Inspect `git status --short --branch`.
3. Read `docs/plans/universal-ai-connector-v2.md` and the active work-package plan completely.
4. Execute only one bounded work package unless the user explicitly authorizes a larger batch.
5. Preserve unrelated changes and generated artifacts.
6. Use the Gradle wrapper and repository scripts.
7. Do not commit or push unless explicitly authorized.

## Current Verification

- Kotlin bridge tests: `./gradlew :bridge:iosSimulatorArm64Test`
- XCFramework: `./scripts/build-xcframework.sh`
- Swift Package and simulator tests: `./scripts/test-swift-package.sh`
- Sample simulator build: `./scripts/build-sample.sh`
- Complete POC check: `./scripts/check.sh`
- Secret scan: `./scripts/secret-scan.sh`
- Final whitespace check: `git diff --check`

Set `POC_SIMULATOR_DESTINATION` to override the default Xcode destination.

## Architecture Rules

- Keep platform-neutral behavior in Kotlin `commonMain`.
- Keep sample applications thin; they demonstrate the shared API and do not duplicate connector behavior.
- Keep Kotlin `Flow`, coroutine types, and Kotlin implementation types behind the Swift callback bridge.
- Keep the supported Apple API in the Swift façade.
- Use deterministic fake implementations for samples and normal CI until live-provider work is explicitly active.
- Keep provider DTOs internal to their future provider modules.
- Do not introduce OpenKeyboard actions, prompts, storage, or UI into this package.

## Safety

- Never commit generated XCFrameworks, build directories, DerivedData, `.xcresult` bundles, logs, credentials, or secrets.
- Never print API keys or Authorization headers.
- Before committing, inspect `git status --short --branch`, run the active verification suite, stage only intended files, and inspect `git diff --cached --name-only`.
