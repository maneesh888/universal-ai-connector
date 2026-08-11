# P8 Production Distribution and Host Integration

## Status and activation gate

Status: `Planned`; P8 is not active.

P0-P7 are completed. This plan defines the bounded P8 sequence without activating the milestone.
P8 may become the sole roadmap milestone marked `In progress` only in the separate P8-A change.
P9 remains inactive until P8 closes authoritatively.

The plan-authoring change has `Milestone effect: none`. It adds no publication task, artifact,
desktop runtime behavior, release tag, or supported distribution claim.

## Objective

Deliver one installable, versioned package path for each accepted host surface while preserving the
single Kotlin client and Swift façade established by P1-P7:

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
- Live mode is an explicit process-level opt-in. A single provider ID, base URL when required,
  model ID, and credential are supplied by the launching host through documented process inputs.
  The application does not store credentials, enumerate accounts, administer a Gateway, or add a
  provider-specific client or public API.
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
  `0.1.0-alpha.1`; the exact value is frozen in P8-A after confirming that it is unused remotely.
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

### P8-A: Activation and distribution contract

Status: `Not started`.

- Activate P8 as the only `In progress` milestone and freeze the disposable P8 proof version.
- Add canonical group/version properties and record minimum JDK, Android, Kotlin, Swift, Xcode,
  iOS, macOS, Windows, and Linux requirements from the already verified toolchain baseline.
- Freeze Maven coordinates, POM identity, Git tag and release-asset naming, desktop package IDs,
  signing identities, and required release inputs.
- Add credential-free validation that versions and distribution metadata agree.
- Confirm Central namespace ownership and macOS signing/notarization readiness without adding a
  credential to the repository or ordinary CI.

P8-A adds no remote publication, remote Swift binary, desktop application, or release claim.

### P8-B: Reproducible Maven publication and Kotlin consumers

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

### P8-C: Reproducible Apple binary distribution

Status: `Not started`.

- Produce the release XCFramework ZIP deterministically enough to verify its contents, slices,
  public headers, debug-symbol policy, license, and SHA-256/SwiftPM checksums.
- Add the repository-root remote manifest while preserving the local development manifest.
- Add manifest parity, archive-layout, URL/version/checksum, and Swift public-surface checks.
- Add a standalone Swift consumer fixture that can target an injected remote package URL and tag.
- Keep generated XCFrameworks, ZIPs, DerivedData, and result bundles out of Git.

### P8-D: Deterministic Compose desktop demonstration

Status: `Not started`.

- Add the Compose desktop module and a platform-neutral presentation/controller boundary.
- Implement the default no-secret response, ordered stream, stable-error, response-cancellation,
  and stream-cancellation demonstrations.
- Add deterministic controller tests and UI semantics suitable for launch inspection.
- Consume the public Kotlin artifact boundary; do not duplicate connector behavior in the UI.

### P8-E: Live desktop boundary and matching-host packages

Status: `Not started`.

- Add explicit, fail-closed live configuration without credential storage or provider/Gateway
  administration UI.
- Preserve redaction, ownership, cancellation, and close behavior in desktop lifecycle tests.
- Build DMG, MSI, and DEB packages on matching hosts and smoke-test the packaged deterministic mode
  without network or credentials.
- Exercise opt-in live mode only through the already delivered provider/Gateway gates selected by
  affected-path routing.
- Prepare package checksums and the required signing/notarization path without publishing an
  immutable artifact.

### P8-F: Unified release-candidate freeze and publication

Status: `Not started`.

- Freeze one exact candidate head only after Maven, Apple, and desktop distribution-producing
  changes are complete. Finalize the remote Swift checksum in that head and require a rebuild from
  the tagged head to reproduce it.
- Run the complete deterministic, API, packaging, secret, matching-host desktop, and affected-live
  gates before creating immutable remote state.
- Create the disposable P8 tag and GitHub prerelease from that exact head. Upload the checksummed
  XCFramework and matching-head desktop packages through the reviewed local release path; do not
  add a write-enabled publication workflow.
- Publish the same version and source head to Maven Central, verify every required root and target
  publication, Javadoc/source artifact, checksum, and signature, then release the deployment.
- Record artifact URLs, version, source head, commands, dates, signing/notarization results, and
  proof limits without retaining credentials or build directories.

Missing namespace ownership, portal credentials, signing material, notarization inputs, Central
validation, GitHub release assets, or checksum agreement is a blocker, not a skipped success. A
failed immutable candidate is never overwritten; fixes use a new lower-than-alpha.1 candidate
version and repeat P8-F.

### P8-G: Remote clean-consumer and distribution proof

Status: `Not started`.

- Resolve the immutable public Maven coordinate from clean Linux, Windows, and macOS consumers.
- Resolve and compile the standalone Swift consumer solely from the public repository tag and
  release asset, then re-run the iOS simulator and generic-device sample links through that remote
  package boundary.
- Download the matching-head desktop packages, verify checksums/signatures, and smoke-test their
  deterministic no-secret mode on each matching host.
- Treat any remote resolution, checksum, signature, install, launch, or deterministic smoke failure
  as a failed candidate requiring a new version; never mutate the published candidate.

### P8-H: Distribution and host acceptance

Status: `Not started`.

- Reconcile copy-paste installation and first-use documentation with executable clean consumers.
- Verify Android, iOS, JVM, and desktop demonstrations through public distribution boundaries.
- Run exact-head deterministic, packaging, distribution, API, secret, affected-live, and complete
  host gates; then obtain exact-head CI and independent review.
- Close P8 only after the disposable Maven and Swift distributions remain publicly resolvable,
  matching-host desktop artifacts pass their recorded smokes, and the resulting `main` workflow
  passes.

P8-H must not publish `0.1.0-alpha.1` or activate P9 in the same change.

## Verification routing

Targeted commands become authoritative as their package lands:

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
- P8 does not promise physical iOS-device execution, App Store distribution, Microsoft Store
  distribution, automatic updates, native desktop library targets, Gateway administration, or
  OpenKeyboard integration.

## Completion boundary

P8 completes only when all P8-A through P8-H packages are authoritative, the roadmap acceptance
criteria are satisfied, exact-head Release gates pass, the closing pull request merges through the
guarded path, and the resulting `main` workflow is verified. P9 activation is a later atomic
change based on that accepted distribution baseline.
