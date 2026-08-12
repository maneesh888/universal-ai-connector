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
  UAC_MACOS_SIGNING_IDENTITY \
  UAC_NOTARY_KEYCHAIN_PROFILE; do
  require_input "$input_name"
done

group="$(property_value "$GRADLE_PROPERTIES" GROUP)"
version="$(property_value "$GRADLE_PROPERTIES" VERSION_NAME)"
artifact_id="$(property_value "$RELEASE_PROPERTIES" MAVEN_ARTIFACT_ID)"
pom_url="$(property_value "$RELEASE_PROPERTIES" POM_URL)"
github_repository="${pom_url#https://github.com/}"
github_owner="${github_repository%%/*}"
github_name="${github_repository#*/}"

if [[ -z "$github_owner" || -z "$github_name" || "$github_name" == "$github_repository" ]]; then
  echo "Distribution readiness could not derive the GitHub repository from POM_URL." >&2
  exit 1
fi

for command_name in base64 curl gh git gpg security xcrun; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Distribution readiness requires '$command_name'." >&2
    exit 1
  fi
done

if ! gh auth status >/dev/null 2>&1; then
  echo "Distribution readiness requires an authenticated gh session." >&2
  exit 1
fi
github_permission="$(
  gh api graphql \
    -f owner="$github_owner" \
    -f name="$github_name" \
    -f query='query($owner:String!,$name:String!){repository(owner:$owner,name:$name){viewerPermission}}' \
    --jq '.data.repository.viewerPermission'
)"
case "$github_permission" in
  ADMIN | MAINTAIN | WRITE) ;;
  *)
    echo "Distribution readiness requires GitHub release-capable repository permission." >&2
    exit 1
    ;;
esac

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
central_config="$TEMP_DIRECTORY/central-curl.conf"
central_token="$({
  printf '%s:' "$ORG_GRADLE_PROJECT_mavenCentralUsername"
  printf '%s' "$ORG_GRADLE_PROJECT_mavenCentralPassword"
} | base64 | tr -d '\r\n')"
printf 'header = "Authorization: Bearer %s"\n' "$central_token" > "$central_config"
chmod 600 "$central_config"
unset central_token
central_status="$(
  curl \
    --silent \
    --show-error \
    --config "$central_config" \
    --request POST \
    --output /dev/null \
    --write-out '%{http_code}' \
    'https://central.sonatype.com/api/v1/publisher/status?id=00000000-0000-0000-0000-000000000000'
)"
case "$central_status" in
  400 | 404) ;;
  401 | 403)
    echo "The Central Portal token was rejected." >&2
    exit 1
    ;;
  *)
    echo "The Central Portal authentication probe returned unexpected HTTP $central_status." >&2
    exit 1
    ;;
esac

key_file="$TEMP_DIRECTORY/signing-key.asc"
gnupg_home="$TEMP_DIRECTORY/gnupg"
challenge_file="$TEMP_DIRECTORY/signing-challenge.txt"
signature_file="$TEMP_DIRECTORY/signing-challenge.sig"
mkdir -m 700 "$gnupg_home"
umask 077
printf '%s' "$ORG_GRADLE_PROJECT_signingInMemoryKey" > "$key_file"
if ! gpg --homedir "$gnupg_home" --batch --import "$key_file" >/dev/null 2>&1; then
  echo "The in-memory PGP private key could not be imported." >&2
  exit 1
fi
actual_fingerprint="$(
  gpg --homedir "$gnupg_home" --batch --with-colons --list-secret-keys 2>/dev/null |
    awk -F: '$1 == "fpr" { print $10; exit }'
)"
expected_fingerprint="${UAC_PGP_SIGNING_KEY_FINGERPRINT//[[:space:]]/}"
if [[ -z "$actual_fingerprint" || "$actual_fingerprint" != "$expected_fingerprint" ]]; then
  echo "The in-memory PGP key does not match UAC_PGP_SIGNING_KEY_FINGERPRINT." >&2
  exit 1
fi
printf '%s\n' 'Universal AI Connector P8 signing readiness challenge' > "$challenge_file"
if ! printf '%s\n' "$ORG_GRADLE_PROJECT_signingInMemoryKeyPassword" |
  gpg \
    --homedir "$gnupg_home" \
    --batch \
    --yes \
    --pinentry-mode loopback \
    --passphrase-fd 0 \
    --local-user "$expected_fingerprint" \
    --detach-sign \
    --output "$signature_file" \
    "$challenge_file" >/dev/null 2>&1; then
  echo "The in-memory PGP private key could not sign with the supplied passphrase." >&2
  exit 1
fi
if ! gpg \
  --homedir "$gnupg_home" \
  --batch \
  --verify "$signature_file" "$challenge_file" >/dev/null 2>&1; then
  echo "The PGP readiness signature could not be verified." >&2
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

echo "Locally verifiable distribution prerequisites passed for $group:$artifact_id:$version."
echo "P8-A remains blocked until authenticated Central Portal namespace ownership is recorded and independently reviewed." >&2
exit 2
