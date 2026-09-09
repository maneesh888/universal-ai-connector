#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION="$($ROOT/scripts/simulator-destination.sh)"
DERIVED_DATA="${UAC_IOS_SAMPLE_TEST_DERIVED_DATA:-${TMPDIR:-/tmp}/universal-ai-connector-ios-sample-test-derived}"

if [[ "${UAC_SKIP_XCFRAMEWORK_BUILD:-0}" != "1" ]]; then
  "$ROOT/scripts/build-xcframework.sh"
fi

cd "$ROOT/samples/ios/UniversalAiConnectorSampleSupport"
xcodebuild test \
  -scheme UniversalAiConnectorSampleSupport \
  -destination "$DESTINATION" \
  -derivedDataPath "$DERIVED_DATA" \
  CODE_SIGN_IDENTITY= \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO
