#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION="$("$ROOT/scripts/simulator-destination.sh")"
DERIVED_DATA="${TMPDIR:-/tmp}/universal-ai-connector-swift-package-derived"
RESULT_BUNDLE_PATH="${UAC_SWIFT_RESULT_BUNDLE_PATH:-}"

if [[ "${UAC_SKIP_XCFRAMEWORK_BUILD:-0}" != "1" ]]; then
  "$ROOT/scripts/build-xcframework.sh"
fi

cd "$ROOT/swift-package"

run_swift_package_tests() {
  # The generated package scheme includes the product-facing and retained POC
  # integration test targets; individual product schemes have no test action.
  xcodebuild test \
    -scheme UniversalAiConnector-Package \
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
