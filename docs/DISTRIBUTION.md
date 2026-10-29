# P8-E distribution contract candidate

This branch prepares one nonpublishing identity/version contract after accepted P8-D
(PR #88, merged as `047169abbcb1438adfe29f9c8acb0c08fa9ed646`, with successful resulting-main
CI). It does not publish a package, tag, release asset, or remote Swift manifest.

The proposed disposable proof version is `0.1.0-0.p8.1`, retained from the existing draft.
Its first prerelease identifier is numeric, so it precedes P9's reserved `0.1.0-alpha.1`.
`gradle.properties` owns the canonical group and version; builds derive Kotlin runtime and
host version metadata from those properties. This candidate is reviewable preparation;
the owner must approve the exact immutable version, destinations and artifacts before publication.

## Proposed identity

| Item | Candidate contract |
|---|---|
| Maven root coordinate | `com.myadidi:universal-ai-connector:0.1.0-0.p8.1` |
| Git tag | `v0.1.0-0.p8.1` |
| Project and SCM | `https://github.com/maneesh888/universal-ai-connector` |
| License / developer | MIT / `maneesh888` GitHub profile |
| Swift product / binary target | `UniversalAiConnector` / `UniversalAiConnectorBridge` |
| Apple asset | `UniversalAiConnectorBridge.xcframework.zip` plus `.sha256` |
| Apple publisher-signing policy | Proposed unsigned proof; no publisher identity is configured |
| Desktop application ID | `com.myadidi.universalai.connector.desktop` |
| Desktop assets | Versioned macOS ARM64 DMG, Windows x86_64 MSI and Linux x86_64 DEB templates in `distribution/release.properties` |

The verified Central namespace is `com.myadidi`. The Portal displayed Verified on October 29,
2026 at 19:14 UTC after domain verification for `myadidi.com`; the nonsecret evidence record is
`distribution/namespace-verification.json`. This is historical ownership evidence, not a
publishing-token authentication or deployment-validation claim. Do not repeat namespace setup
merely because an older draft described it as missing.

Public inspection on October 29 found no repository tags and returned HTTP 404 for this Maven
artifact's public metadata. Recheck the exact tag/version and Portal deployment state before any
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
identity and notary profile, without Maven credentials. `all` runs both for the current unified
release plan. A scoped probe does not waive any required target in the actual publication plan.

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

The supported iOS path needs a release device/simulator XCFramework, ZIP, checksum, root remote
`Package.swift`, immutable Git tag/asset and clean consumer proof. There is no separate mandatory
central SwiftPM upload. No public asset URL or remote consumption is claimed by this candidate.

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
The current roadmap still sequences desktop acceptance before unified publication; a libraries-first
publication path requires an explicit roadmap/scope decision and must not silently skip those gates.

References: [Central requirements](https://central.sonatype.org/publish/requirements/),
[SwiftPM binary distribution](https://developer.apple.com/documentation/xcode/distributing-binary-frameworks-as-swift-packages),
[XCFramework signatures](https://developer.apple.com/videos/play/wwdc2023/10061/),
[macOS notarization](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution).
