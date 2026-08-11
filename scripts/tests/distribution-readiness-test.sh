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
  -u UAC_CENTRAL_NAMESPACE \
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
  UAC_CENTRAL_NAMESPACE=io.github.maneesh888 \
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

echo "Distribution readiness fails closed without exposing host inputs."
