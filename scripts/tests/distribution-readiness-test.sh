#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECKER="$ROOT/scripts/check-distribution-readiness.sh"
TEST_DIRECTORY="$(mktemp -d)"
trap 'rm -rf "$TEST_DIRECTORY"' EXIT

MISSING_OUTPUT="$TEST_DIRECTORY/missing-output.txt"
if env \
  -u ORG_GRADLE_PROJECT_mavenCentralUsername \
  -u ORG_GRADLE_PROJECT_mavenCentralPassword \
  -u ORG_GRADLE_PROJECT_signingInMemoryKey \
  -u ORG_GRADLE_PROJECT_signingInMemoryKeyPassword \
  -u UAC_PGP_SIGNING_KEY_FINGERPRINT \
  -u UAC_MACOS_SIGNING_IDENTITY \
  -u UAC_NOTARY_KEYCHAIN_PROFILE \
  "$CHECKER" >"$MISSING_OUTPUT" 2>&1; then
  echo "Distribution readiness must fail when release inputs are absent." >&2
  exit 1
fi
if ! grep -Fq 'requires host input ORG_GRADLE_PROJECT_mavenCentralUsername' "$MISSING_OUTPUT"; then
  echo "Distribution readiness must identify a missing input by name." >&2
  exit 1
fi

SENTINEL='uac-distribution-sentinel-secret'
SENTINEL_OUTPUT="$TEST_DIRECTORY/sentinel-output.txt"
if env \
  ORG_GRADLE_PROJECT_mavenCentralUsername="$SENTINEL" \
  ORG_GRADLE_PROJECT_mavenCentralPassword="$SENTINEL" \
  ORG_GRADLE_PROJECT_signingInMemoryKey="$SENTINEL" \
  ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$SENTINEL" \
  UAC_PGP_SIGNING_KEY_FINGERPRINT="$SENTINEL" \
  UAC_MACOS_SIGNING_IDENTITY="Developer ID Application: $SENTINEL" \
  UAC_NOTARY_KEYCHAIN_PROFILE="$SENTINEL" \
  "$CHECKER" >"$SENTINEL_OUTPUT" 2>&1; then
  echo "Synthetic release inputs must not satisfy distribution readiness." >&2
  exit 1
fi
if grep -Fq "$SENTINEL" "$SENTINEL_OUTPUT"; then
  echo "Distribution readiness must not print release input values." >&2
  exit 1
fi

for required_literal in \
  'api/v1/publisher/status' \
  "--jq '.data.repository.viewerPermission'" \
  '--list-secret-keys' \
  '--pinentry-mode loopback' \
  'P8-A remains blocked until authenticated Central Portal namespace ownership is recorded'; do
  if ! grep -Fq -- "$required_literal" "$CHECKER"; then
    echo "Distribution readiness is missing fail-closed validation: $required_literal" >&2
    exit 1
  fi
done

if grep -Fq 'Distribution release inputs are ready' "$CHECKER"; then
  echo "Distribution readiness must not claim completion without Portal namespace evidence." >&2
  exit 1
fi

STUB_DIRECTORY="$TEST_DIRECTORY/stubs"
mkdir -p "$STUB_DIRECTORY"

write_stub() {
  local name="$1"
  shift

  printf '%s\n' '#!/usr/bin/env bash' "$@" > "$STUB_DIRECTORY/$name"
  chmod +x "$STUB_DIRECTORY/$name"
}

write_stub git 'exit 0'
write_stub curl "printf '404'"
write_stub gh \
  'if [[ "${1:-} ${2:-}" == "auth status" ]]; then exit 0; fi' \
  'if [[ "${1:-} ${2:-}" == "api graphql" ]]; then printf "WRITE\n"; exit 0; fi' \
  'exit 1'
write_stub gpg \
  'if [[ " $* " == *" --list-secret-keys "* ]]; then' \
  "  printf '%s\\n' 'sec:u:4096:1:0123456789ABCDEF:0:0:::::::' 'fpr:::::::::0123456789ABCDEF0123456789ABCDEF01234567:'" \
  'fi' \
  'exit 0'
write_stub security \
  "printf '%s\\n' '  1) 0123456789ABCDEF \"Developer ID Application: UAC Test\"'"
write_stub xcrun 'exit 0'
write_stub uname "printf 'Darwin\\n'"

LOCALLY_VALID_OUTPUT="$TEST_DIRECTORY/locally-valid-output.txt"
if env \
  PATH="$STUB_DIRECTORY:$PATH" \
  ORG_GRADLE_PROJECT_mavenCentralUsername=synthetic-central-user \
  ORG_GRADLE_PROJECT_mavenCentralPassword=synthetic-central-password \
  ORG_GRADLE_PROJECT_signingInMemoryKey=synthetic-private-key \
  ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=synthetic-key-password \
  UAC_PGP_SIGNING_KEY_FINGERPRINT=0123456789ABCDEF0123456789ABCDEF01234567 \
  UAC_MACOS_SIGNING_IDENTITY='Developer ID Application: UAC Test' \
  UAC_NOTARY_KEYCHAIN_PROFILE=synthetic-notary-profile \
  "$CHECKER" >"$LOCALLY_VALID_OUTPUT" 2>&1; then
  echo "Locally valid synthetic inputs must remain blocked without Portal namespace evidence." >&2
  exit 1
fi
if ! grep -Fq 'Locally verifiable distribution prerequisites passed' "$LOCALLY_VALID_OUTPUT"; then
  echo "The readiness regression did not exercise every locally verifiable prerequisite." >&2
  exit 1
fi
if ! grep -Fq 'P8-A remains blocked until authenticated Central Portal namespace ownership is recorded' \
  "$LOCALLY_VALID_OUTPUT"; then
  echo "Distribution readiness must preserve the external namespace blocker." >&2
  exit 1
fi

echo "Distribution readiness fails closed without exposing host inputs."
