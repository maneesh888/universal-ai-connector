# P8 Production Distribution and Host Integration

## Status and activation gate

Status: `In progress`; P8-A is active.

P0-P7 are completed. P8-A activates P8 as the sole roadmap milestone marked `In progress`.
Every later P8 package remains `Not started`, and P9 remains inactive until P8 closes
authoritatively.

The earlier plan-authoring change had `Milestone effect: none`. P8-A advances the roadmap with the
iOS sample-host workflow but adds no publication task, remote artifact, release tag, or supported
distribution claim.

## Objective

Deliver one installable, versioned package path for each accepted host surface while preserving the
single Kotlin client and Swift façade established by P1-P7:

- first deliver the live model-discovery and exact-model connection workflow sequentially through
  the iOS sample, Android sample, Kotlin/JVM console, and Compose desktop application while
  retaining every zero-configuration deterministic demonstration;
- publish Android and Kotlin/JVM variants through one Kotlin Multiplatform Maven coordinate;
- distribute the Swift façade through a remote Swift Package whose binary target is a checksummed
  device-and-simulator XCFramework archive;
- add one Compose Multiplatform desktop demonstration consuming the Kotlin/JVM artifact on macOS,
  Windows, and Linux;
- keep installation and first-use examples executable through clean external consumers; and
- establish synchronized versioning, public API compatibility, artifact integrity, signing, and
  matching-host package proof for the P9 alpha release.

P8 proves distribution mechanics with a disposable pre-alpha candidate that precedes
`0.1.0-alpha.1`. P9 alone may publish the roadmap's alpha version.

P8-A through P8-C are intentionally sequential host stages: iOS first, Android second, then the
Kotlin/JVM console and Compose desktop application on Linux, Windows, and macOS. P8-D closes their
deterministic parity before P8-E starts the existing distribution roadmap. This ordering does not
reduce the already accepted cross-platform package surface or authorize a new host target.

## Sample-host live boundary

- Every existing deterministic response, ordered streaming, stable error, response cancellation,
  and stream cancellation path remains the zero-configuration default. It requires no account,
  network, Gateway, provider credential, environment input, or secret.
- Live mode is an explicit host choice. Each sample owns the selected delivered-provider or
  OpenAI-compatible Gateway configuration: provider ID, base URL when required, exact model ID,
  and credential. Gateway use remains provider ID `openai-compatible` plus its `/v1` base URL and
  host-owned bearer credential; no Gateway internal contract enters a sample or the package.
- Every host calls the public `listModels` API and renders or reports loading/supported, empty,
  unsupported, error, cancelled, and retrying states. A successful non-empty result drives exact
  selection. Manual model-ID entry is enabled only for an explicit `.unsupported` result; a
  supported empty list remains a non-success state that blocks `respond`.
- **Test Connection**, or the console equivalent, first attempts discovery, resolves the exact
  selected or explicitly entered model, then issues one minimal `respond` on that same identifier.
  Errors, cancellation, and a supported empty list stop before `respond`; only `.unsupported` may
  continue after manual entry. No fallback, alias replacement, routing substitution, or retry on a
  different model is allowed.
- Presentation remains host-owned. SwiftUI/Compose views render state, host view models or console
  controllers own actions, and host configuration/credential services own client construction and
  secret lifecycle. The connector package remains independent of UI frameworks, platform secret
  stores, application storage, accounts, and provider/Gateway administration.

### Host credential boundaries

- iOS masks credential entry and stores any retained credential only through a sample-host
  Keychain service. Clearing live configuration removes it.
- Android masks entry and protects any retained credential through a sample-host Android
  Keystore-backed service. Plain `SharedPreferences`, resources, and build configuration are not
  credential stores; clearing live configuration removes protected state.
- The Kotlin/JVM console accepts credentials through documented process environment or non-echoing
  interactive input, keeps them only for the process lifetime, and never accepts them as command
  history-visible arguments or persists them.
- Compose desktop uses a host-owned secure-store adapter for macOS Keychain, Windows Credential
  Manager, or Linux Secret Service. If the matching service is unavailable, live credentials are
  session-only and persistence is disabled rather than falling back to plaintext storage.
- No host writes credentials to source, bundled resources, logs, diagnostics, screenshots, crash
  text, test fixtures, or retained proof artifacts. Redaction and clear/remove behavior receive
  deterministic coverage on every host.

## Distribution architecture

### Kotlin and Android

- The public root coordinate is `io.github.maneesh888:universal-ai-connector:<version>`.
- Maven Central is the supported remote repository. The Kotlin Multiplatform root publication owns
  Gradle metadata and selects the published JVM or Android variant for supported consumers.
- The root, JVM, Android, `iosArm64`, and `iosSimulatorArm64` publications are uploaded together so
  every variant referenced by root Gradle metadata resolves. The iOS Kotlin/Native artifacts are
  publication-graph dependencies, not a supported Apple host API; Apple consumers use only the
  Swift façade and remote Swift Package.
- Every publication carries complete POM, source, Central-compatible Javadoc, Gradle metadata where
  applicable, checksum, and signature artifacts from the same exact source head and version. The
  internal Gradle module name `bridge` does not become the product artifact ID.
- P8 local checks publish to an isolated repository under build output. Remote Central publication
  is explicit, credentialed, immutable, and never runs from pull-request or ordinary CI events.
- The Central namespace, portal token, and PGP signing identity are host-owned release inputs. They
  are never committed, printed, retained in build output, or made runtime dependencies.
- The Android sample remains a thin consumer of the public Kotlin API. Its P8 live UI and
  Keystore-backed host service do not enter the Maven artifact or create an Android-specific
  connector implementation.

This follows Kotlin's current Multiplatform publication model, in which the root publication and
every metadata-referenced target publication are uploaded together, and Maven Central requires
complete POM, sources, Javadoc, checksum, and signature metadata.

### Apple

- A repository-root `Package.swift` is the remote consumer manifest. It exposes the existing
  `UniversalAiConnector` product and Swift-native wrapper sources.
- The manifest references a public GitHub release asset named
  `UniversalAiConnectorBridge.xcframework.zip` through a remote binary target and an exact SwiftPM
  checksum. The archive contains the XCFramework at its root.
- `swift-package/Package.swift` remains the local-development manifest and may reference only the
  exact locally built XCFramework. Local and remote manifests must expose the same product and
  supported Swift API.
- The remote manifest, archive, checksum record, tag, and release asset are bound to the same
  version and source head. A changed archive requires a new version; release assets are immutable.
- The existing device and simulator slices remain the accepted Apple target set. P8 does not add
  another Apple platform or expose Kotlin implementation types through Swift.

This follows Apple's binary Swift Package boundary: a public ZIP with the XCFramework at its root,
a URL-based binary target, and a checksum produced by `swift package compute-checksum`.

### Desktop demonstration

- Add one Kotlin/JVM Compose Multiplatform application under `samples/desktop`; it consumes the
  same public Maven artifact as other external JVM consumers.
- The default deterministic mode starts without network access, accounts, environment inputs,
  provider credentials, or a Gateway. It demonstrates one response, ordered streaming, a stable
  error, response cancellation, and stream cancellation through user-visible controls.
- Live mode is explicit and reuses the P8-C model-discovery and exact-selection controller. Its
  provider ID, base URL when required, exact model ID, and credential remain host-owned; secure
  persistence uses only the matching OS credential service and otherwise stays session-only. The
  application does not enumerate accounts, administer a Gateway, or add a provider-specific client
  or public API.
- Gateway live configuration remains provider ID `openai-compatible`, its `/v1` base URL, an exact
  model ID, and a host-owned credential. No Gateway internal contract enters the application.
- Compose `jpackage` tasks produce matching-host packages: DMG on macOS, MSI on Windows, and DEB on
  Linux. Cross-compilation is not accepted as matching-host proof.
- Published desktop artifacts include SHA-256 checksums. macOS distribution must be Developer ID
  signed and notarized before it is claimed as downloadable; Windows signing is required only if a
  trusted signing identity is available and otherwise remains an explicit alpha limitation. Linux
  package metadata identifies the maintainer, license, version, and supported architecture.

## Version and compatibility policy

- `gradle.properties` owns one canonical SemVer value used by Maven publications, Swift release
  assets, documentation, and desktop metadata. Scripts may derive platform-safe package versions
  but must reject semantic drift.
- P8 remote proof uses a disposable version that has lower SemVer precedence than
  `0.1.0-alpha.1`; the exact value is frozen in P8-E after confirming that it is unused remotely.
- Tags use `v<version>`. No immutable remote publication occurs until Kotlin, Apple, and desktop
  distribution-producing changes are frozen together. Publication commands fail closed unless the
  tag, Gradle version, remote Swift manifest, archives, and exact `HEAD` agree and the worktree is
  clean.
- Published Maven versions, Git tags, release assets, checksums, and Swift binary-target URLs are
  immutable. A correction uses a new version.
- P8 establishes a checked-in Kotlin public API baseline and a checked Swift façade/header
  baseline. Additive changes require deliberate baseline updates; breaking changes are rejected
  unless an explicit pre-1.0 compatibility decision, migration note, and version change accompany
  them.
- `0.1.0-alpha.1` makes no source or binary stability promise beyond the documented alpha policy,
  but accidental public surface drift remains a failed gate.

## Clean-consumer boundary

Distribution proof is stronger than repository packaging or sample compilation:

- Kotlin fixtures are standalone Gradle builds with no project dependency, composite build,
  source-directory import, or repository-specific task. They resolve the public coordinate from
  an injected repository URL, compile first use, and exercise construction and closure.
- Remote Kotlin proof starts from an empty dependency cache where practical, resolves the exact
  immutable Central version, and runs on Linux, Windows, and macOS.
- A standalone Swift fixture declares only the repository URL and exact tag requirement. It
  resolves the remote manifest and binary asset, compiles first use through the Swift façade, and
  does not copy an XCFramework manually.
- Android, iOS, JVM console, and desktop examples remain thin public consumers. Repository samples
  may keep deterministic test doubles, but release proof must not substitute a source project for
  the published dependency.
- No clean-consumer proof may fall back to `mavenLocal`, a Gradle included build, a local package
  path, an untagged Git revision, or a generated artifact outside the declared remote channel.

## Work packages

Execute one package at a time after activation.

### P8-A: iOS live sample and model discovery

Status: `In progress`.

- Activate P8 as the only `In progress` milestone while keeping every later P8 package
  `Not started`.
- Preserve the zero-configuration deterministic iOS sample and add an explicit live mode through
  the existing public Swift façade; Android, Kotlin/JVM, and desktop remain assigned to the later
  sequential host packages.
- Add sample-host configuration for delivered providers and the OpenAI-compatible Gateway, with
  masked credential entry and Keychain-backed host storage isolated from the connector package.
- Add the `listModels` UI state machine for loading/supported, empty, unsupported, error,
  cancellation, and retry, plus an exact model picker and manual model-ID fallback enabled only for
  an explicit `.unsupported` result. A supported empty list remains blocking.
- Implement **Test Connection** as discovery followed by one minimal `respond` on the exact selected
  model, with explicit manual continuation only for `.unsupported` discovery, a blocking supported
  empty result, and no model fallback or substitution.
- Add deterministic sample-host state tests covering discovery results, retry, cancellation,
  credential clearing/redaction, exact selection, `.unsupported` manual fallback, supported-empty
  blocking, and the no-substitution connection sequence.
- Run the existing Swift package, combined XCFramework, simulator sample-build, and generic-device
  link gates. Opt-in live acceptance must use the actual iOS sample on one exact source head and
  prove discovery plus minimal response for at least two exact selected models.
- Record normal Simulator interaction proof for the complete visible live workflow.
- As required P8-A acceptance evidence, install and launch the same exact signed build on a physical
  iPhone and exercise a visible **Test Connection** on an exact selected model, retaining only
  secret-free evidence.

P8-A adds no remote publication, Maven coordinate, remote Swift binary, desktop application,
Android/JVM UI, release tag, or supported distribution claim.

### P8-B: Android live sample and model discovery

Status: `Not started`.

- Preserve the Android sample's zero-configuration deterministic mode and add an explicit live
  Compose UI through the existing public Kotlin client; do not duplicate connector behavior in
  Android code.
- Add the shared discovery states, exact picker, `.unsupported`-only manual entry, supported-empty
  blocking, retry/cancellation, and exact-model **Test Connection** behavior established by P8-A.
- Keep provider/Gateway configuration in an Android host service. Mask credential input, protect
  retained values through an Android Keystore-backed service, and prove clear/remove and redaction
  without adding secrets to resources, build configuration, preferences, logs, or the package.
- Add deterministic view-model/state tests plus existing Android host, AAR, and sample-consumer
  gates.
- On one exact head, use the actual Android application to prove discovery and minimal response on
  at least two exact selected models, then record normal emulator install, launch, visible
  connection, cancellation, background/foreground, and credential-clearing evidence.

P8-B adds no new provider protocol, package-owned UI or credential storage, remote publication,
new host target, or distribution claim.

### P8-C: Kotlin/JVM console and Compose desktop live samples

Status: `Not started`.

- Extend the Kotlin/JVM console with a zero-configuration deterministic path and an explicit
  headless `listModels` -> exact selection -> minimal `respond` connection flow. Only
  `.unsupported` permits manual model entry; a supported empty result fails closed.
- Add the Compose desktop application for Linux, Windows, and macOS over the same public Kotlin
  client and a platform-neutral controller. Preserve the deterministic response, streaming,
  stable-error, and cancellation controls while adding the shared discovery states and visible
  exact-model **Test Connection** behavior.
- Keep console credentials process-scoped and out of command history. Use the matching host's
  credential service for optional desktop persistence and fall back only to session memory when
  secure persistence is unavailable.
- Add deterministic console/controller/state, redaction, cancellation, lifecycle, and secure-store
  availability tests, plus launchable UI semantics.
- On one exact head, prove the real console flow and normally launched Compose application on
  Linux, Windows, and macOS. Exercise at least two exact selected models across this live matrix,
  with no fallback or substitution and with visible desktop connection evidence on each host.

P8-C adds no native desktop library target, Java-specific façade, provider-specific public client,
account UI, remote publication, or packaged distribution claim.

### P8-D: Cross-platform sample behavior parity

Status: `Not started`.

- Define one deterministic case catalog for supported non-empty discovery, supported empty,
  `.unsupported`, error, retry, cancellation, exact selection, manual fallback, credential
  clearing/redaction, and no-substitution connection sequencing.
- Run the catalog through the iOS and Android host state machines, Kotlin/JVM console controller,
  and Compose desktop controller without moving host UI or secret-storage behavior into the
  connector package.
- Reconcile error presentation, cancellation/close ownership, concurrent action handling,
  background/foreground or process lifecycle, and cleanup while keeping host-native UI patterns.
- Require the complete deterministic host matrix and each P8-A through P8-C runtime/live evidence
  record to reference exact heads and exact model identifiers before distribution work begins.

P8-D closes sample behavior only. It adds no artifact publication, new platform target, or release
claim.

### P8-E: Distribution contract and version freeze

Status: `Not started`.

- Freeze the disposable P8 proof version.
- Add canonical group/version properties and record minimum JDK, Android, Kotlin, Swift, Xcode,
  iOS, macOS, Windows, and Linux requirements from the already verified toolchain baseline.
- Freeze Maven coordinates, POM identity, Git tag and release-asset naming, desktop package IDs,
  signing identities, and required release inputs.
- Add credential-free validation that versions and distribution metadata agree.
- Confirm Central namespace ownership and macOS signing/notarization readiness without adding a
  credential to the repository or ordinary CI.

P8-E adds no remote publication, remote Swift binary, desktop application, or release claim.

### P8-F: Reproducible Maven publication and Kotlin consumers

Status: `Not started`.

- Configure the Kotlin Multiplatform root and every referenced JVM, Android, `iosArm64`, and
  `iosSimulatorArm64` publication with complete POM, sources, Central-compatible Javadoc, Gradle
  metadata where applicable, checksums, and host-supplied signing.
- Add an isolated local publication repository and validate its artifact inventory and metadata.
- Add standalone JVM and Android consumer fixtures that resolve only the injected Maven repository
  and public coordinate.
- Prove every variant referenced by root metadata exists while rejecting any documentation or
  sample that presents a Kotlin/Native publication as the supported Apple entry point.
- Add public API compatibility validation for the supported Kotlin surface.
- Keep remote credentials optional for deterministic checks and fail closed for remote commands.

### P8-G: Reproducible Apple binary distribution

Status: `Not started`.

- Produce the release XCFramework ZIP deterministically enough to verify its contents, slices,
  public headers, debug-symbol policy, license, and SHA-256/SwiftPM checksums.
- Add the repository-root remote manifest while preserving the local development manifest.
- Add manifest parity, archive-layout, URL/version/checksum, and Swift public-surface checks.
- Add a standalone Swift consumer fixture that can target an injected remote package URL and tag.
- Keep generated XCFrameworks, ZIPs, DerivedData, and result bundles out of Git.

### P8-H: Compose desktop distribution preparation

Status: `Not started`.

- Prepare the P8-C Compose application for matching-host packaging without changing its accepted
  deterministic or live model-discovery behavior.
- Bind packaging to the public Kotlin artifact boundary and reject internal-source, included-build,
  or local-artifact shortcuts.
- Freeze package identifiers, icons, version mapping, maintainer/license metadata, runtime image,
  deterministic launch entry point, and checksum inputs.
- Retain deterministic controller tests and launch-inspection semantics in packaged builds.

### P8-I: Matching-host desktop packages and lifecycle proof

Status: `Not started`.

- Preserve the P8-C explicit live configuration, secure-store/session-only boundary, redaction,
  ownership, cancellation, and close behavior in packaged desktop lifecycle tests.
- Build DMG, MSI, and DEB packages on matching hosts and smoke-test the packaged deterministic mode
  without network or credentials.
- Exercise the packaged opt-in discovery and exact-model connection flow on each matching host only
  through already delivered provider/Gateway gates selected by affected-path routing.
- Prepare package checksums and the required signing/notarization path without publishing an
  immutable artifact.

### P8-J: Unified release-candidate freeze and publication

Status: `Not started`.

- Freeze one exact candidate head only after Maven, Apple, and desktop distribution-producing
  changes are complete. Finalize the remote Swift checksum in that head and require a rebuild from
  the tagged head to reproduce it.
- Run the complete deterministic, API, packaging, secret, matching-host desktop, and affected-live
  gates, exact-head ordinary CI and live policy, and independent exact-head review while the
  candidate pull request remains a draft. Create no tag or immutable remote artifact yet.
- Guardedly merge the reviewed candidate, wait for the resulting `main` CI to pass, and prove the
  resulting squash commit has the identical Git tree as the reviewed PR head. The resulting `main`
  commit becomes the publication source identity; a tree mismatch invalidates the candidate.
- From a clean checkout of that accepted `main` commit, rebuild and revalidate every artifact, then
  create the disposable P8 tag and GitHub prerelease. Upload the checksummed XCFramework and
  matching-source desktop packages through the reviewed local release path; do not add a
  write-enabled publication workflow.
- Publish the same version and accepted `main` source identity to Maven Central, verify every
  required root and target publication, Javadoc/source artifact, checksum, and signature, then
  release the deployment.
- Record artifact URLs, version, source head, commands, dates, signing/notarization results, and
  proof limits without retaining credentials or build directories.

Missing namespace ownership, portal credentials, signing material, notarization inputs, Central
validation, GitHub release assets, or checksum agreement is a blocker, not a skipped success. A
failed immutable candidate is never overwritten; fixes use a new lower-than-alpha.1 candidate
version and repeat P8-J.

### P8-K: Remote clean-consumer and distribution proof

Status: `Not started`.

- Resolve the immutable public Maven coordinate from clean Linux, Windows, and macOS consumers.
- Resolve and compile the standalone Swift consumer solely from the public repository tag and
  release asset, then re-run the iOS simulator and generic-device sample links through that remote
  package boundary.
- Download the matching-head desktop packages, verify checksums/signatures, and smoke-test their
  deterministic no-secret mode on each matching host.
- Treat any remote resolution, checksum, signature, install, launch, or deterministic smoke failure
  as a failed candidate requiring a new version; never mutate the published candidate.

### P8-L: Distribution and host acceptance

Status: `Not started`.

- Reconcile copy-paste installation and first-use documentation with executable clean consumers.
- Verify the iOS and Android applications, Kotlin/JVM console, and Compose desktop application on
  Linux, Windows, and macOS through their public distribution boundaries.
- Re-run the accepted discovery states, `.unsupported`-only manual entry, supported-empty blocking,
  exact-model connection, cancellation/lifecycle, credential handling, and no-substitution
  assertions across every current host surface.
- Run exact-head deterministic, packaging, distribution, API, secret, affected-live, and complete
  host gates; then obtain exact-head CI and independent review.
- Close P8 only after the disposable Maven and Swift distributions remain publicly resolvable,
  matching-host desktop artifacts pass their recorded smokes, and the resulting `main` workflow
  passes.

P8-L must not publish `0.1.0-alpha.1` or activate P9 in the same change.

## Verification routing

Targeted commands become authoritative as their package lands:

- iOS sample deterministic states: `./scripts/test-ios-sample.sh`;
- existing iOS package/build/link gates: `./scripts/test-swift-package.sh`,
  `./scripts/build-xcframework.sh`, `./scripts/build-sample.sh`, and
  `./scripts/build-sample-device.sh`;
- Android sample behavior and consumer: `./scripts/tests/run-android-sample-test.sh` and
  `./gradlew :samples:android:consumerCheck`;
- Kotlin/JVM console behavior and runtime: `./gradlew :samples:jvm-console:consumerCheck` and
  `./gradlew :samples:jvm-console:run`;
- cross-platform sample parity: `./scripts/check-sample-parity.sh`;
- metadata/version validation: `./scripts/check-distribution-metadata.sh`;
- local Maven inventory and standalone consumers: `./scripts/check-maven-distribution.sh local`;
- public Central resolution: `./scripts/check-maven-distribution.sh remote <version>`;
- Apple archive and local manifest parity: `./scripts/check-apple-distribution.sh local`;
- remote Swift resolution: `./scripts/check-apple-distribution.sh remote <tag>`;
- Kotlin and Swift API compatibility: `./scripts/check-api-compatibility.sh`;
- desktop deterministic behavior: `./gradlew :samples:desktop:check`;
- matching-host desktop package: `./scripts/check-desktop-distribution.sh`; and
- affected provider/Gateway behavior: the existing `./scripts/check-live.sh <target>` routes.

Command names are contracts for the packages that introduce them; they do not exist at plan time.
Credential-free supported checks join the appropriate quick/full/CI gates only after they become
stable. Remote publication remains explicit and never joins ordinary CI or a Git hook.

## Release safety and proof limits

- Ordinary CI and pull-request workflows stay read-only and secretless. Do not add
  `pull_request_target`, merge automation, a PAT, or a write-enabled publishing token.
- Central, PGP, GitHub release, Apple signing, and notarization inputs remain host-owned release
  inputs. Commands validate presence without displaying values.
- Generated archives, frameworks, packages, installers, signatures, result bundles, logs, and
  credential files remain untracked.
- A local repository proves publication layout, not remote distribution. A GitHub Actions artifact
  proves a build, not public installation. A compiled package proves integration, not device or UI
  execution. Each claim requires its matching recorded proof.
- Physical iPhone behavior is supported as a claim only for an exact signed build with recorded
  install, launch, and interaction proof; simulator/build/link evidence alone cannot make that
  claim. P8 does not promise App Store distribution, Microsoft Store distribution, automatic
  updates, native desktop library targets, Gateway administration, or OpenKeyboard integration.
- The current P8 host scope is iOS, Android, and Kotlin/JVM console/Compose desktop on Linux,
  Windows, and macOS. JavaScript, Wasm, and additional Kotlin/Native or native desktop targets
  remain demand-driven and are not implied by P8 acceptance.

## Completion boundary

P8 completes only when all P8-A through P8-L packages are authoritative, the roadmap acceptance
criteria are satisfied, exact-head Release gates pass, the closing pull request merges through the
guarded path, and the resulting `main` workflow is verified. P9 activation is a later atomic
change based on that accepted distribution baseline.
