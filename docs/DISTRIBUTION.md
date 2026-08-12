# Distribution Contract

P8 uses the disposable proof version `0.1.0-0.p8.1`. It is valid SemVer and has lower
precedence than the P9 target `0.1.0-alpha.1` because its first prerelease identifier is numeric
`0`. The canonical value and Maven group live only in `gradle.properties`; builds generate the
Kotlin runtime constant and derive host metadata from those properties.

## Frozen identity

| Item | P8 contract |
|---|---|
| Public Maven coordinate | `io.github.maneesh888:universal-ai-connector:0.1.0-0.p8.1` |
| Git tag | `v0.1.0-0.p8.1` |
| POM name | `Universal AI Connector` |
| Project and SCM URL | `https://github.com/maneesh888/universal-ai-connector` |
| License | MIT License |
| Developer identity | `maneesh888` / `https://github.com/maneesh888` |
| Swift product | `UniversalAiConnector` |
| Swift binary target | `UniversalAiConnectorBridge` |
| Apple release asset | `UniversalAiConnectorBridge.xcframework.zip` plus `.sha256` |
| macOS desktop asset | `universal-ai-connector-desktop-{version}-macos-aarch64.dmg` plus `.sha256` |
| Windows desktop asset | `universal-ai-connector-desktop-{version}-windows-x86_64.msi` plus `.sha256` |
| Linux desktop asset | `universal-ai-connector-desktop-{version}-linux-x86_64.deb` plus `.sha256` |
| Desktop application ID | `io.github.maneesh888.universalai.connector.desktop` |
| Desktop package name | `universal-ai-connector-demo` |

The full public POM, asset, package, and toolchain fields are machine-readable in
`distribution/release.properties`. Release assets are immutable within their tag; a correction
uses a new candidate version.

## Supported baseline

| Surface | Minimum P8 build or consumer baseline |
|---|---|
| JDK | 21 |
| Gradle wrapper | 9.6.1 |
| Kotlin Gradle plugin | 2.4.10 |
| Android | API 24 minimum; compile/target API 36; Build Tools 36.1.0 |
| Swift | Swift tools 6.0 |
| Xcode | 16.4; Xcode 26.0 is also locally verified |
| iOS | 17.0 |
| macOS build host | macOS 15 on Apple silicon |
| Windows build host | Windows Server 2025 x86_64 |
| Linux build host | Ubuntu 24.04 x86_64 |

The desktop runtime claim remains pending P8-E matching-host package and launch proof. These
values therefore freeze the P8 verification floor; they do not claim broader desktop operating
system compatibility yet.

## Release inputs

Release commands accept inputs only from the launching host and must validate presence without
printing values. Ordinary CI and pull requests remain secretless.

| Purpose | Required host input |
|---|---|
| Central Portal token username | `ORG_GRADLE_PROJECT_mavenCentralUsername` |
| Central Portal token password | `ORG_GRADLE_PROJECT_mavenCentralPassword` |
| ASCII-armored PGP private key | `ORG_GRADLE_PROJECT_signingInMemoryKey` |
| PGP private-key password | `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` |
| Expected PGP fingerprint | `UAC_PGP_SIGNING_KEY_FINGERPRINT` |
| macOS signing identity | `UAC_MACOS_SIGNING_IDENTITY` matching a Developer ID Application certificate |
| Apple notarization credentials | `UAC_NOTARY_KEYCHAIN_PROFILE` referencing a local Keychain profile |
| GitHub release authority | An authenticated `gh` session with release permission |

The Central account must show verified namespace `io.github.maneesh888`. macOS downloadable
artifacts require a Developer ID Application identity and a working `notarytool` Keychain profile.
Windows signing is optional for the internal alpha only when no trusted identity is available and
must remain an explicit release limitation.

## Verification

Run the credential-free consistency gate on every platform:

```bash
./scripts/check-distribution-metadata.sh
```

This command checks canonical version derivation, the P8 SemVer boundary, POM and asset identity,
toolchain pins, version expectations, CI host pins, and the absence of credential-bearing public
metadata. It performs no publication, signing, notarization, or remote mutation.

After the authenticated external prerequisites exist, run the separate read-only prerequisite
probe:

```bash
./scripts/check-distribution-readiness.sh
```

It authenticates the Central Portal token without uploading, verifies the unused tag and Maven
path, checks GitHub repository write permission, proves that the expected PGP private key can sign
with the supplied passphrase, and validates the installed Developer ID Application identity and
notarization profile. It identifies missing input names but never prints their values.

The Portal exposes no documented read-only API that proves namespace ownership. The probe
therefore exits blocked even after its locally verifiable checks pass. A release owner must inspect
the authenticated Portal namespace page, record the resulting evidence in the P8-A review packet,
and obtain independent review before P8-A can complete. Supplying the group name as an environment
variable is not accepted as ownership proof.

On August 12, 2026, the candidate tag and Maven path were both unused. Central namespace ownership,
PGP signing readiness, a Developer ID Application identity, and a notarization profile still require
authenticated external proof before P8-A can complete.
