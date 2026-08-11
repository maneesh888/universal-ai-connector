#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_PROPERTIES="$ROOT/gradle.properties"
RELEASE_PROPERTIES="$ROOT/distribution/release.properties"
TEMP_DIRECTORY=""

cleanup() {
  if [[ -n "$TEMP_DIRECTORY" && -d "$TEMP_DIRECTORY" ]]; then
    rm -rf -- "$TEMP_DIRECTORY"
  fi
}
trap cleanup EXIT

property_value() {
  local file="$1"
  local property_name="$2"

  awk -F= -v property_name="$property_name" '
    $1 == property_name {
      sub(/^[^=]*=/, "")
      print
    }
  ' "$file"
}

require_input() {
  local input_name="$1"

  if [[ -z "${!input_name:-}" ]]; then
    echo "Distribution readiness requires host input $input_name." >&2
    return 1
  fi
}

"$ROOT/scripts/check-distribution-metadata.sh" >/dev/null

for input_name in \
  ORG_GRADLE_PROJECT_mavenCentralUsername \
  ORG_GRADLE_PROJECT_mavenCentralPassword \
  ORG_GRADLE_PROJECT_signingInMemoryKey \
  ORG_GRADLE_PROJECT_signingInMemoryKeyPassword \
  UAC_PGP_SIGNING_KEY_FINGERPRINT \
  UAC_CENTRAL_NAMESPACE \
  UAC_MACOS_SIGNING_IDENTITY \
  UAC_NOTARY_KEYCHAIN_PROFILE; do
  require_input "$input_name"
done

group="$(property_value "$GRADLE_PROPERTIES" GROUP)"
version="$(property_value "$GRADLE_PROPERTIES" VERSION_NAME)"
artifact_id="$(property_value "$RELEASE_PROPERTIES" MAVEN_ARTIFACT_ID)"

if [[ "$UAC_CENTRAL_NAMESPACE" != "$group" ]]; then
  echo "UAC_CENTRAL_NAMESPACE must match the canonical Maven group." >&2
  exit 1
fi

for command_name in curl gh git gpg security xcrun; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Distribution readiness requires '$command_name'." >&2
    exit 1
  fi
done

if ! gh auth status >/dev/null 2>&1; then
  echo "Distribution readiness requires an authenticated gh session." >&2
  exit 1
fi

if [[ -n "$(git -C "$ROOT" ls-remote --tags origin "refs/tags/v$version")" ]]; then
  echo "The immutable release tag v$version already exists." >&2
  exit 1
fi

maven_status="$(
  curl \
    --silent \
    --show-error \
    --location \
    --output /dev/null \
    --write-out '%{http_code}' \
    "https://repo1.maven.org/maven2/${group//./\/}/$artifact_id/$version/"
)"
if [[ "$maven_status" != "404" ]]; then
  echo "The P8 candidate Maven path must be unused; received HTTP $maven_status." >&2
  exit 1
fi

TEMP_DIRECTORY="$(mktemp -d)"
chmod 700 "$TEMP_DIRECTORY"
key_file="$TEMP_DIRECTORY/signing-key.asc"
umask 077
printf '%s' "$ORG_GRADLE_PROJECT_signingInMemoryKey" > "$key_file"
actual_fingerprint="$(
  gpg --batch --with-colons --import-options show-only --import "$key_file" 2>/dev/null |
    awk -F: '$1 == "fpr" { print $10; exit }'
)"
expected_fingerprint="${UAC_PGP_SIGNING_KEY_FINGERPRINT//[[:space:]]/}"
if [[ -z "$actual_fingerprint" || "$actual_fingerprint" != "$expected_fingerprint" ]]; then
  echo "The in-memory PGP key does not match UAC_PGP_SIGNING_KEY_FINGERPRINT." >&2
  exit 1
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Apple distribution readiness must run on macOS." >&2
  exit 1
fi
if [[ "$UAC_MACOS_SIGNING_IDENTITY" != *"Developer ID Application"* ]]; then
  echo "UAC_MACOS_SIGNING_IDENTITY must name a Developer ID Application identity." >&2
  exit 1
fi
if ! security find-identity -v -p codesigning 2>/dev/null |
  grep -Fq "\"$UAC_MACOS_SIGNING_IDENTITY\""; then
  echo "The configured Developer ID Application identity is not available in the Keychain." >&2
  exit 1
fi
if ! xcrun notarytool history \
  --keychain-profile "$UAC_NOTARY_KEYCHAIN_PROFILE" \
  --output-format json >/dev/null 2>&1; then
  echo "The configured notarytool Keychain profile is not usable." >&2
  exit 1
fi

echo "Distribution release inputs are ready for $group:$artifact_id:$version."
