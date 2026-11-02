# P8 unpublished distribution preparation

This branch prepares library release engineering and local validation after accepted P8-D
(PR #88, merged as `047169abbcb1438adfe29f9c8acb0c08fa9ed646`, with successful resulting-main
CI). It adds the root remote Swift manifest and explicit local publisher tools. It does not itself
publish a package, tag or release asset; the manifest's URL becomes usable only after publication.

The owner approved `0.1.0-alpha.0.p8.1` and the public libraries-first scope on November 2, 2026.
The shared `alpha` qualifier with numeric `0` keeps this P8 proof before P9's reserved
`0.1.0-alpha.1` under SemVer and Maven's documented qualifier ordering.
`gradle.properties` owns the canonical group and version; builds derive Kotlin runtime and
host version metadata from those properties. This candidate is reviewable preparation;
version/scope approval alone does not authorize credential or release operations. The owner's later
end-to-end release instruction authorizes routine repository and approved-version publication steps;
private credential entry and sensitive transmission still require their applicable secure handoffs.
PR #62 merged the preparation as `1732ee2d3b30ea153f075e27ae2c007d68175649` after exact-head full,
all 28 selected live tests, independent review and required CI. This is not distribution acceptance.

## Approved preparation identity

| Item | Candidate contract |
|---|---|
| Maven root coordinate | `com.myadidi:universal-ai-connector:0.1.0-alpha.0.p8.1` |
| Git tag | `v0.1.0-alpha.0.p8.1` |
| Project and SCM | `https://github.com/maneesh888/universal-ai-connector` |
| License / developer | MIT / `maneesh888` GitHub profile |
| Swift product / binary target | `UniversalAiConnector` / `UniversalAiConnectorBridge` |
| Apple asset | `UniversalAiConnectorBridge.xcframework.zip` plus `.sha256` |
| Apple publisher-signing policy | Approved unsigned iOS prerelease scope; no publisher identity is configured |
| Desktop application ID | `com.myadidi.universalai.connector.desktop` |
| Desktop assets | Deferred from the first library release; future DMG/MSI/DEB templates remain recorded in `distribution/release.properties` |

The verified Central namespace is `com.myadidi`. The Portal displayed Verified on October 29,
2026 at 19:14 UTC after domain verification for `myadidi.com`; the nonsecret evidence record is
`distribution/namespace-verification.json`. This is historical ownership evidence, not a
publishing-token authentication or deployment-validation claim. Do not repeat namespace setup
merely because an older draft described it as missing.

Public inspection on November 2 found no proposed tag and returned HTTP 404 for all five
`0.1.0-alpha.0.p8.1` public POM paths. Recheck the exact tag/version and Portal deployment state before any
immutable publication. A failed immutable candidate must use a new version.

## Verified build baseline

| Surface | Build baseline |
|---|---|
| JDK / Gradle / Kotlin | 21 / 9.7.1 / 2.4.10 |
| Android library | Minimum 24, compile 36, Build Tools 36.1.0 |
| Android sample | Minimum 24, compile 37, target 36 |
| Swift / iOS | Swift tools 6.0 / iOS 17.0 |
| Xcode | 16.4, proved by accepted-main CI run 40956077878 |
| CI hosts | macOS 15; Windows Server 2025 x86_64; Ubuntu 24.04 x86_64 |

These are build/proof baselines, not a claim that JVM consumers require those particular OS
releases. Matching-host packaged-desktop proof remains later P8 work.

## Scoped readiness and host inputs

Ordinary CI is secretless. The metadata checker and fully stubbed readiness regressions never
load real release credentials. Actual credential probes are separate opt-in host commands:

```bash
./scripts/check-distribution-metadata.sh
./scripts/check-distribution-readiness.sh libraries
./scripts/check-distribution-readiness.sh desktop
./scripts/check-distribution-readiness.sh all
```

An explicit scope is required. `libraries` probes GitHub read access, unused public tag/Maven
paths, a supplied Central token and supplied PGP signing material. It does not require or invoke
Developer ID or notarization. `desktop` probes GitHub plus the configured macOS Developer ID
identity and notary profile, without Maven credentials. `all` runs both sets of probes. The
approved first publication scope is `libraries-first`; desktop probes remain for separately
authorized later work. This scope does not waive desktop requirements for eventual P8 completion.

| Scope | Host inputs; values must never be printed or committed |
|---|---|
| Libraries | `ORG_GRADLE_PROJECT_mavenCentralUsername`, `ORG_GRADLE_PROJECT_mavenCentralPassword`, `ORG_GRADLE_PROJECT_signingInMemoryKey`, `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`, `UAC_PGP_SIGNING_KEY_FINGERPRINT` |
| macOS desktop | `UAC_MACOS_SIGNING_IDENTITY`, `UAC_NOTARY_KEYCHAIN_PROFILE` |
| Both | Existing authenticated `gh` session for the verified repository |

No real credential probe was run by this preparation. Token/PGP usability remains unverified.
The dummy-deployment Central status probe is bounded authentication screening; it does not prove
that a token can publish this namespace. Real deployment validation is required before release.
PGP material is used only in a protected temporary directory and removed on success or failure.
GitHub read access likewise does not prove release-write permission.

## SwiftPM and signing boundaries

The supported iOS path uses a release device/simulator XCFramework, ZIP, checksum, root remote
`Package.swift`, immutable Git tag/asset and clean consumer proof. There is no separate mandatory
central SwiftPM upload. The source manifest records the approved future asset URL; hosted
availability and remote consumption are not claimed before publication and consumer verification.

Apple documents unsigned and self-signed XCFramework states. Publisher identity signing is a
policy choice to finalize before archiving/checksum freeze; it is not the macOS notarization
workflow. Developer ID and notarization requirements apply to the planned macOS desktop app,
not this iOS-only binary package. No identity is generated, configured or used here.

The existing local builder now accepts an explicit release configuration:

```bash
./scripts/build-xcframework.sh --release
```

It validates the same iOS ARM64 device/simulator slices, minimum OS and private bridge boundaries.
Local archive/runtime consistency proof is preparation, not remote SwiftPM distribution proof.
The owner explicitly approved libraries-first publication. Maven and iOS SwiftPM preparation may
precede desktop distribution; Developer ID, notarization and matching-host installers are deferred
from this prerelease and remain required for the later desktop/P8 closeout. Ordinary desktop code
verification remains part of the existing checks.

References: [Central requirements](https://central.sonatype.org/publish/requirements/),
[SwiftPM binary distribution](https://developer.apple.com/documentation/xcode/distributing-binary-frameworks-as-swift-packages),
[XCFramework signatures](https://developer.apple.com/videos/play/wwdc2023/10061/),
[macOS notarization](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution).


## Unsigned local Maven publication and coordinate consumers

P8-F preparation now uses Gradle's built-in `maven-publish` and opt-in `signing` plugins. The only
configured publication repository is `bridge/build/distribution/maven`, an ignored file repository.
No Central upload task or write-enabled CI workflow is introduced. `publish` does not publish to
Central in this project. Do not use shared `mavenLocal` as distribution proof.

```bash
./scripts/check-maven-distribution.sh local
./scripts/check-api-compatibility.sh kotlin
```

The complete local graph is built on macOS: root `universal-ai-connector`, `-jvm`, `-android`,
`-iosarm64`, and `-iossimulatorarm64`. Every publication includes complete POM fields, sources,
Gradle metadata and a documentation JAR. Official Dokka 2.2.0 generates real Kotlin HTML docs
under the Maven `javadoc` classifier; its Java-lookalike Javadoc mode does not support KMP.
Native publications complete the metadata graph; the supported Apple entry remains the Swift
façade, and Apple bridge internals are excluded from these Kotlin API docs.

The inventory checks the root/target graph, safe local metadata URLs, required artifacts,
canonical version sources, nonempty documentation/license, binary contents and all emitted
MD5/SHA-1/SHA-256/SHA-512 checksums. It rejects missing targets, tampering, unexpected artifacts,
and signatures in this unsigned proof route. It does not verify PGP signatures or public
resolution. Private generated logs and receipts stay under `bridge/build/distribution`.

Standalone builds under `distribution/consumers` resolve the **root coordinate** exclusively from
an injected repository for `com.myadidi`; other groups use their normal repositories. They have
no producer project/composite/source dependency and never use `mavenLocal`. The JVM fixture
constructs, checks version and closes the client, and verifies the loaded JAR hash against the
inventoried publication. Android compiles real first-use construction/closure into app/test APKs.
Both consumers pin Kotlin 2.4.10; AGP's default built-in compiler alone may be older and cannot
consume this metadata. No metadata-version check is disabled.

Optional explicit emulator proof performs one no-network construction/version/closure test:

```bash
./scripts/check-maven-distribution.sh local --android-runtime emulator-5580
```

This installs only the named standalone fixture APKs on that emulator. Without this option,
Android proof is compilation only. It establishes neither physical-device nor remote-distribution
proof. The verifier never starts, wipes or selects a physical device. Runtime proof is recorded
separately from ordinary CI's compilation route.

Kotlin compiler ABI baselines live in `bridge/api`; normal validation uses `checkKotlinAbi` and
never updates them automatically. Filters include the supported connector namespace, excluding
the separate Apple bridge namespace. JVM and Klib dumps are checked; on non-Apple hosts the Kotlin
tool may preserve/infer unsupported native target data, so complete native ABI proof is the Mac
route. Swift API compatibility uses the normalized compiler symbol graph baseline under
`swift-package/api`, including public declarations, generics, availability and conditional
conformance. Compiler paths and documentation are excluded. Baseline changes require explicit
source review; normal checks never update it.

### Independent SwiftPM preparation and publication order

SwiftPM does not require Maven's key or token. The approved unsigned iOS path is independently
implemented while Maven credentialed acceptance is pending. Both destinations keep the same
approved version and accepted source; reporting one as published never implies the other is.

```bash
./scripts/check-apple-distribution.sh metadata
./scripts/check-apple-distribution.sh prepare
./scripts/check-apple-distribution.sh build
./scripts/check-apple-distribution.sh local --rebuild-check
./scripts/check-apple-distribution.sh publication-ready
./scripts/check-apple-distribution.sh remote
```

`metadata` checks the source identity, exact URL/version/checksum and canonical root manifest.
`prepare` explicitly builds Release, updates the unpublished manifest/record and creates the
initial API baseline only if none exists. A dirty preparation run is not release acceptance.
`swift-package/Package.swift` remains the local development entry point; both manifests expose
the same public product, targets and source tree.

`build` joins full deterministic verification on every Apple host. It tests that host's archive
and the frozen manifest identity. Different Xcode/SDK toolchains may produce different binary
bytes: its receipt records whether the archive matches the publisher checksum without asserting
frozen-asset acceptance. It never updates the manifest, baseline or strict publisher receipt.
This adds packaging proof and does not waive any existing deterministic gate.

If Swift API extraction or comparison fails, CI retains its compiler log and normalized public
API comparison/actual-surface reports with the failure evidence. These contain public declarations,
not local configuration or provider content. The exact reviewed baseline assertion remains required;
diagnostic collection never updates or accepts a different API.

The canonical surface omits only a redundant `SendableMetatype` conformance when an
otherwise-identical `Sendable` conformance (same source and constraints) remains. Swift 6.2
introduced that inherited relationship; Swift 6.1 omits it. Explicit metatype-only conformances,
`Sendable` itself, declarations, generic/conditional constraints, availability and every other
relationship remain checked. The reviewed migration removed exactly 43 redundant edges and changed
none of the 264 symbols. See [Swift Evolution SE-0470](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0470-isolated-conformances.md).

Strict `local --rebuild-check` requires the produced archive to match the frozen remote checksum
and a forced Release rebuild to reproduce it. ZIP order, timestamps/modes and plist serialization/
library-record ordering are canonicalized; compiler binaries and legal texts are not patched.
The unsigned archive excludes dSYMs. Checks cover actual arm64 device/simulator slices and retained
notices. A fresh extracted-package consumer must execute exactly one passed XCTest with no skips
or expected failures. A thin app forces a generic-device executable link against the public
product. This proves neither physical-device installation nor App Store signing.

After exact-head Release gates, independent review, guarded merge and successful resulting-main
CI, rebuild from that clean accepted source with strict `local --rebuild-check`.
`publication-ready` checks the strict receipt, current archive, source/tree, main ancestry and
exact-source successful main CI without writing. Create the approved tag on that source,
reproduce from the tag, push through mandatory hooks, and create the GitHub prerelease with ZIP
and `.sha256` assets. Never overwrite an immutable candidate. Use the reviewed local release
procedure and existing `gh` session; ordinary CI remains read-only and never publishes.

Then `remote` downloads the public asset afresh with `gh`, checks its frozen checksum/notices,
resolves the public Git package at the exact version/source and runs the simulator runtime test
and generic-device app link. Both resolution records must match the published tag. Only this
completed command establishes hosted consumer proof. It creates no release, token or key and
makes no live-provider call.

### Signing and publication boundary

Unsigned local builds require no Central token or PGP key, and the verification driver removes
provider and publishing inputs from child environments. Signing is disabled unless explicitly
requested with `-PuacSignPublication=true`. That route requires approved host-supplied in-memory
key/passphrase inputs and `--no-configuration-cache`; use `--no-daemon` as well. Do not run it until
the owner has approved the precise key action. This preparation has not parsed/imported a key or
signed anything. The public key/fingerprint and real signature validation still need a secure
approved handoff, followed by credentialed Portal validation before any upload or release.

The version and libraries-first scope are approved. Preparation PR #62 is merged; the owner's
release-completion instruction supersedes its earlier draft hold. Owner-operated private signing-key
setup is approved, but key availability and token readiness have not been confirmed by this preparation.
Sensitive token transmission and public-key identity upload retain specific handoffs. These local
operations create no immutable public artifacts.

### Owner-operated signing of prepared bytes

The local signing route consumes the license-prepared graph, without invoking Gradle publication
again or rewriting signed payloads:

```bash
./scripts/check-maven-distribution.sh local
./scripts/check-maven-distribution.sh sign-local
```

Run the second command yourself in an interactive Mac Terminal, from a clean frozen commit after
unsigned verification succeeds on that exact head. It requires the approved setup helper's protected
`setup-complete.json` under the dedicated `~/.config/universal-ai-connector/signing` folder. It
compares the public receipt with the existing RSA-4096 signing primary, identity and expiration;
the identity is not embedded in repository configuration. It neither creates nor imports a key.
Enter the existing passphrase at the hidden prompt. No passphrase is accepted in command arguments,
environment inputs or a credential file; the script does not export the private key.

Unsigned verification records the canonical inventory digest and rechecks it after all consumers.
Signing requires a fresh match to those accepted bytes; a valid same-version rebuild cannot reuse
an older consumer receipt. Older receipts without the digest require fresh unsigned verification.

The route serializes owner sessions across linked worktrees using the shared protected signing
store, copies the 28 inventoried payloads and their checksums, signs
each copy with an armored SHA-512 detached signature, verifies its exact fingerprint and bytes,
and checks source stability. It stops only the dedicated home's agent before accepting output.
Tampering, wrong identity, signature failure or failed agent cleanup leaves no accepted signed
output. Existing output is never overwritten. Inspect an existing result or interrupted lock
before retrying; do not remove it blindly.

Successful output is confined to ignored `bridge/build/distribution/signed-maven`: the repository,
`central-bundle.zip` and a public verification receipt containing source/tree identities and hashes.
The bundle excludes the receipt and repository-managed Maven metadata. Offline fixtures validate
these controls with synthetic signatures and mocked GnuPG, not a real signing key. Actual signing
and Portal validation remain unverified until the owner-operated path runs successfully. The route
has no HTTP, keyserver, token or upload action; a local signed bundle is not public resolution proof.

Signing reference: [Central PGP requirements](https://central.sonatype.org/publish/requirements/gpg/).

### Explicit Central publisher

`./scripts/publish-central.sh prepare` checks the signed bundle, all 28 payloads, signatures'
recorded hashes, checksums, full Maven graph and consumer inventory identity without network or
credential entry. It checks the clean current source. It verifies bytes against the local GnuPG
receipt rather than claiming a new cryptographic operation.

The explicit owner commands `upload`, `status` and `publish` require
`--confirmed-recipient central.sonatype.com`, the approved tag at the signed source, ancestry on
`origin/main`, successful exact-source resulting-main CI, and private Terminal token entry.
They must not run until the specific credential-transmission handoff is confirmed. Only the two
token fields entered at hidden prompts are sent as the Portal Bearer header to
`https://central.sonatype.com/api/v1/publisher/`; they are never command arguments, environment
inputs or journal fields. No redirect, environment proxy or automatic request retry is allowed.

Upload always uses `USER_MANAGED`: upload success is not validation or publication. `status`
checks the exact deployment ID/name and retains only the documented state and error-group count.
`publish` accepts only a matching `VALIDATED` deployment; a later `PUBLISHED` status and independent
public-consumer checks are still required. Neither credentials nor raw upstream error text are
retained. HTTP status and numeric Retry-After are safe failure evidence.

Shared owner-only journals under Git's common metadata `uac-release-state/<version>` serialize
linked worktrees and retain uncertain upload/publish outcomes. Never reupload after a timeout:
inspect the Portal's named deployment, then reconcile with `status --deployment-id <public-id>`.
An existing journal blocks another upload; no drop/delete or overwrite route is provided.
Publish-attempt history survives status observations and prohibits another publish POST, including
after an uncertain response. A known deployment ID cannot be replaced; missing-ID adoption is
restricted to status-only upload reconciliation. Pending markers and their parent directories are
synced before a mutation is admitted.

The protocol and recovery fixtures are entirely offline. Real Portal authentication, validation,
publication and public resolution have not run. This source implementation removes an engineering
gap; the remaining secret entry and public-key identity transmission are separate owner handoffs.
See [the official Publisher API](https://central.sonatype.org/publish/publish-portal-api/).

Sources: [KMP publication](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-lib-setup.html),
[Android KMP publication](https://developer.android.com/kotlin/multiplatform/plugin),
[Dokka formats](https://kotlinlang.org/docs/dokka-gradle.html),
[Kotlin ABI validation](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html),
[Gradle signing](https://docs.gradle.org/9.7.1/userguide/signing_plugin.html).

Version precedence reference: [Maven version ordering](https://maven.apache.org/pom.html#Version_Order_Specification).

### Bundled native notices

XCFramework preparation copies the unchanged project MIT `LICENSE` and the reviewed
`distribution/licenses` texts into the artifact. `releaseNativeNoticeInputs` binds both iOS
Klib graphs to the SHA-256 inventory; `scripts/native-notices.py` rejects dependency drift or
missing/modified notices before the artifact is accepted. The inventory includes exact source
archive provenance and separate Kotlin standard-library/runtime attribution. Conservative notice
retention is documented in that directory; resolved inputs do not prove final binary inclusion.
Release ZIP verification must preserve this notice tree. A locally staged archive is not hosted
SwiftPM resolution proof.

The unsigned local Maven driver embeds the unchanged root MIT text in every own primary,
source, metadata and documentation archive, including the Android AAR and its classes JAR.
It then updates Gradle variant sizes/hashes and all four repository checksums before validating
or consuming that graph. It refuses signatures and symlinks; signed artifacts must never be
rewritten. JVM runtime, Android compilation and both native coordinate-compilation fixtures use
this prepared graph. Thin Maven artifacts do not bundle their external runtime dependencies.
Direct Gradle local publication is intermediate output; only the prepared, validated graph may
be considered for a later explicitly authorized signing/upload step.
