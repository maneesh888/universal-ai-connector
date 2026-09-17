#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION="$($ROOT/scripts/simulator-destination.sh)"
DERIVED_DATA="${UAC_IOS_SAMPLE_TEST_DERIVED_DATA:-${TMPDIR:-/tmp}/universal-ai-connector-ios-sample-test-derived}"
PROJECT="$ROOT/samples/ios/UniversalAiConnectorSample.xcodeproj"

if [[ "${UAC_SKIP_XCFRAMEWORK_BUILD:-0}" != "1" ]]; then
  "$ROOT/scripts/build-xcframework.sh"
fi

xcodebuild test \
  -project "$PROJECT" \
  -scheme UniversalAiConnectorSample \
  -destination "$DESTINATION" \
  -derivedDataPath "$DERIVED_DATA"
