#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:---all}"

case "$MODE" in
  --catalog)
    python3 -I "$ROOT/scripts/sample-parity.py" catalog
    ;;
  --kotlin|--all)
    python3 -I "$ROOT/scripts/sample-parity.py" catalog
    "$ROOT/gradlew" :samples:android:testDebugUnitTest :samples:host-controller:test \
      :samples:jvm-console:test :samples:desktop:test
    python3 -I "$ROOT/scripts/sample-parity.py" kotlin
    if [[ "$MODE" == "--all" ]]; then "$0" --ios; fi
    ;;
  --ios)
    python3 -I "$ROOT/scripts/sample-parity.py" catalog
    RESULT_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/uac-sample-parity.XXXXXX")"
    trap 'rm -rf -- "$RESULT_DIRECTORY"' EXIT
    UAC_IOS_SAMPLE_TEST_RESULT_BUNDLE="$RESULT_DIRECTORY/ios.xcresult" \
      "$ROOT/scripts/test-ios-sample.sh"
    xcrun xcresulttool get test-results tests --path "$RESULT_DIRECTORY/ios.xcresult" \
      --compact > "$RESULT_DIRECTORY/ios.json"
    python3 -I "$ROOT/scripts/sample-parity.py" ios "$RESULT_DIRECTORY/ios.json"
    ;;
  *)
    echo "Usage: $0 [--all|--kotlin|--ios|--catalog]" >&2
    exit 2
    ;;
esac
