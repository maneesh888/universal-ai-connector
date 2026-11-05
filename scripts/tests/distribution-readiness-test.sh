#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECKER="$ROOT/scripts/check-distribution-readiness.sh"
TEST_DIRECTORY="$(mktemp -d)"
trap 'rm -rf "$TEST_DIRECTORY"' EXIT

STUB_DIRECTORY="$TEST_DIRECTORY/stubs"
mkdir -p "$STUB_DIRECTORY"

write_stub() {
  local name="$1"
  shift

  printf '%s\n' '#!/usr/bin/env bash' "$@" > "$STUB_DIRECTORY/$name"
  chmod +x "$STUB_DIRECTORY/$name"
}

write_stub git '[[ " $* " == *" ls-remote --tags origin "* ]] || exit 64' 'exit 0'
write_stub curl \
  'if [[ "${1:-}" != "-q" ]]; then exit 64; fi' \
  'if [[ " $* " == *"central.sonatype.com"* && "${UAC_TEST_FAILURE:-}" == central ]]; then printf 401; else printf 404; fi'
write_stub gh \
  'if [[ "${1:-} ${2:-}" == "auth status" ]]; then [[ "${UAC_TEST_FAILURE:-}" != github ]]; exit; fi' \
  'if [[ "${1:-}" == "api" ]]; then printf "maneesh888/universal-ai-connector\n"; exit 0; fi' \
  'exit 1'
write_stub gpg \
  'if [[ " $* " == *" --detach-sign "* && "${UAC_TEST_FAILURE:-}" == signing ]]; then exit 1; fi' \
  'if [[ " $* " == *" --list-secret-keys "* ]]; then' \
  "  printf '%s\\n' 'sec:u:4096:1:0123456789ABCDEF:0:0:::::::' 'fpr:::::::::0123456789ABCDEF0123456789ABCDEF01234567:'" \
  'fi' \
  'exit 0'
write_stub security \
  "printf '%s\\n' '  1) 0123456789ABCDEF \"Developer ID Application: UAC Test\"'"
write_stub xcrun '[[ "${1:-} ${2:-}" == "notarytool history" ]] || exit 64' 'exit 0'
write_stub uname "printf 'Darwin\\n'"

# Every checker invocation uses stubs, including missing-input and redaction cases.
export PATH="$STUB_DIRECTORY:$PATH"
export TMPDIR="$TEST_DIRECTORY/scratch"
mkdir -p "$TMPDIR"

assert_cleanup() {
  if [[ -n "$(find "$TMPDIR" -mindepth 1 -print -quit)" ]]; then
    echo "Distribution readiness retained temporary credential material." >&2
    exit 1
  fi
}

MISSING_OUTPUT="$TEST_DIRECTORY/missing-output.txt"
if env \
  -u ORG_GRADLE_PROJECT_mavenCentralUsername \
  -u ORG_GRADLE_PROJECT_mavenCentralPassword \
  -u ORG_GRADLE_PROJECT_signingInMemoryKey \
  -u ORG_GRADLE_PROJECT_signingInMemoryKeyPassword \
  -u UAC_PGP_SIGNING_KEY_FINGERPRINT \
  -u UAC_MACOS_SIGNING_IDENTITY \
  -u UAC_NOTARY_KEYCHAIN_PROFILE \
  "$CHECKER" libraries >"$MISSING_OUTPUT" 2>&1; then
  echo "Distribution readiness must fail when release inputs are absent." >&2
  exit 1
fi
if ! grep -Fq 'requires host input ORG_GRADLE_PROJECT_mavenCentralUsername' "$MISSING_OUTPUT"; then
  echo "Distribution readiness must identify a missing input by name." >&2
  exit 1
fi

assert_cleanup

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
  "$CHECKER" libraries >"$SENTINEL_OUTPUT" 2>&1; then
  echo "Synthetic release inputs must not satisfy distribution readiness." >&2
  exit 1
fi
if grep -Fq "$SENTINEL" "$SENTINEL_OUTPUT"; then
  echo "Distribution readiness must not print release input values." >&2
  exit 1
fi

assert_cleanup

for required_literal in \
  'api/v1/publisher/status' \
  'curl -q' \
  'gh api "repos/$github_owner/$github_name"' \
  '--list-secret-keys' \
  '--pinentry-mode loopback' \
  'needs_libraries' \
  'needs_desktop' \
  'Namespace ownership is recorded separately'; do
  if ! grep -Fq -- "$required_literal" "$CHECKER"; then
    echo "Distribution readiness is missing fail-closed validation: $required_literal" >&2
    exit 1
  fi
done

if grep -Fq 'Distribution release inputs are ready' "$CHECKER"; then
  echo "Distribution readiness must not claim completion without Portal namespace evidence." >&2
  exit 1
fi

LOCALLY_VALID_OUTPUT="$TEST_DIRECTORY/locally-valid-output.txt"
if ! env \
  -u UAC_MACOS_SIGNING_IDENTITY \
  -u UAC_NOTARY_KEYCHAIN_PROFILE \
  ORG_GRADLE_PROJECT_mavenCentralUsername=synthetic-central-user \
  ORG_GRADLE_PROJECT_mavenCentralPassword=synthetic-central-password \
  ORG_GRADLE_PROJECT_signingInMemoryKey=synthetic-private-key \
  ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=synthetic-key-password \
  UAC_PGP_SIGNING_KEY_FINGERPRINT=0123456789ABCDEF0123456789ABCDEF01234567 \
  "$CHECKER" libraries >"$LOCALLY_VALID_OUTPUT" 2>&1; then
  echo "Library probes must pass with valid stubs and no desktop identity/notary inputs." >&2
  exit 1
fi
if ! grep -Fq 'Scoped distribution probes passed (libraries)' "$LOCALLY_VALID_OUTPUT"; then
  echo "Library readiness did not exercise its complete scoped probe." >&2
  exit 1
fi
if ! grep -Fq 'real deployment validation and publication were not performed' "$LOCALLY_VALID_OUTPUT"; then
  echo "Readiness must preserve the real deployment proof boundary." >&2
  exit 1
fi
assert_cleanup

DESKTOP_OUTPUT="$TEST_DIRECTORY/desktop-output.txt"
if ! env \
  -u ORG_GRADLE_PROJECT_mavenCentralUsername \
  -u ORG_GRADLE_PROJECT_mavenCentralPassword \
  -u ORG_GRADLE_PROJECT_signingInMemoryKey \
  -u ORG_GRADLE_PROJECT_signingInMemoryKeyPassword \
  -u UAC_PGP_SIGNING_KEY_FINGERPRINT \
  UAC_MACOS_SIGNING_IDENTITY='Developer ID Application: UAC Test' \
  UAC_NOTARY_KEYCHAIN_PROFILE=synthetic-notary-profile \
  UAC_TEST_FAILURE=central \
  "$CHECKER" desktop >"$DESKTOP_OUTPUT" 2>&1; then
  echo "Desktop probes must not require or probe unrelated Maven credentials." >&2
  exit 1
fi
assert_cleanup

if "$CHECKER" >"$TEST_DIRECTORY/no-scope.txt" 2>&1; then
  echo "Readiness requires an explicit target scope." >&2
  exit 1
fi
if "$CHECKER" unknown >"$TEST_DIRECTORY/unknown-scope.txt" 2>&1; then
  echo "Readiness must reject unknown target scopes." >&2
  exit 1
fi

# Authentication and signing errors must remain deterministic, redacted, and clean.
for failure in github central signing; do
  FAILURE_OUTPUT="$TEST_DIRECTORY/$failure-output.txt"
  if env \
    UAC_TEST_FAILURE="$failure" \
    ORG_GRADLE_PROJECT_mavenCentralUsername="$SENTINEL" \
    ORG_GRADLE_PROJECT_mavenCentralPassword="$SENTINEL" \
    ORG_GRADLE_PROJECT_signingInMemoryKey="$SENTINEL" \
    ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$SENTINEL" \
    UAC_PGP_SIGNING_KEY_FINGERPRINT=0123456789ABCDEF0123456789ABCDEF01234567 \
    UAC_MACOS_SIGNING_IDENTITY='Developer ID Application: UAC Test' \
    UAC_NOTARY_KEYCHAIN_PROFILE="$SENTINEL" \
    "$CHECKER" libraries >"$FAILURE_OUTPUT" 2>&1; then
    echo "Distribution readiness accepted a failed $failure prerequisite." >&2
    exit 1
  fi
  case "$failure" in
    github) expected='requires an authenticated gh session' ;;
    central) expected='Central Portal token was rejected' ;;
    signing) expected='could not sign with the supplied passphrase' ;;
  esac
  if ! grep -Fq "$expected" "$FAILURE_OUTPUT" || grep -Fq "$SENTINEL" "$FAILURE_OUTPUT"; then
    echo "Distribution readiness did not safely report the $failure failure." >&2
    exit 1
  fi
  assert_cleanup
done

echo "Distribution readiness fails closed without exposing host inputs."
