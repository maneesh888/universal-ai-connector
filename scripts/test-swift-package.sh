#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION="$("$ROOT/scripts/simulator-destination.sh")"
DERIVED_DATA="${TMPDIR:-/tmp}/universal-ai-connector-swift-package-derived"
RESULT_BUNDLE_PATH="${UAC_SWIFT_RESULT_BUNDLE_PATH:-}"

if [[ "${UAC_SKIP_XCFRAMEWORK_BUILD:-0}" != "1" ]]; then
  "$ROOT/scripts/build-xcframework.sh"
fi

if rg --quiet \
  'UniversalAiConnectorPOC|PocBridge' \
  "$ROOT/swift-package/Package.swift" \
  "$ROOT/swift-package/Sources" \
  "$ROOT/swift-package/Tests"; then
  echo "The Swift Package contains a retired POC product or callback dependency." >&2
  exit 1
else
  rg_status=$?
  if [[ "$rg_status" -ne 1 ]]; then
    echo "Failed to scan the Swift Package for retired POC symbols." >&2
    exit 1
  fi
fi

cd "$ROOT/swift-package"

run_swift_package_tests() {
  # With one supported product, Xcode generates one package scheme containing
  # the product-facing integration test target.
  xcodebuild test \
    -scheme UniversalAiConnector \
    -destination "$DESTINATION" \
    -derivedDataPath "$DERIVED_DATA" \
    "$@" \
    CODE_SIGN_IDENTITY= \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO
}

if [[ -n "$RESULT_BUNDLE_PATH" ]]; then
  rm -rf "$RESULT_BUNDLE_PATH"
  run_swift_package_tests -resultBundlePath "$RESULT_BUNDLE_PATH"
else
  run_swift_package_tests
fi
