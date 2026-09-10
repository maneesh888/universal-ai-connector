#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT/samples/ios/UniversalAiConnectorSample.xcodeproj"
APP_SOURCES="$ROOT/samples/ios/UniversalAiConnectorSample/App"
SAMPLE_SUPPORT_SOURCES="$ROOT/samples/ios/UniversalAiConnectorSampleSupport/Sources"
DESTINATION="${UAC_SAMPLE_DESTINATION:-}"
DERIVED_DATA="${UAC_SAMPLE_DERIVED_DATA:-${TMPDIR:-/tmp}/universal-ai-connector-sample-derived}"

if [[ -z "$DESTINATION" ]]; then
  DESTINATION="$("$ROOT/scripts/simulator-destination.sh")"
fi

if [[ "${UAC_SKIP_XCFRAMEWORK_BUILD:-0}" != "1" ]]; then
  "$ROOT/scripts/build-xcframework.sh"
fi

UNSUPPORTED_IMPORTS="$(
  rg --no-filename --only-matching \
    '^[[:space:]]*import[[:space:]]+[A-Za-z_][A-Za-z0-9_]*' \
    "$APP_SOURCES" \
    "$SAMPLE_SUPPORT_SOURCES" \
    --glob '*.swift' |
    awk '{print $2}' |
    LC_ALL=C sort -u |
    grep -Ev '^(Combine|Darwin|Foundation|Security|SwiftUI|UniversalAiConnector)$' || true
)"
if [[ -n "$UNSUPPORTED_IMPORTS" ]]; then
  echo "The iOS application imports unsupported modules:" >&2
  printf '%s\n' "$UNSUPPORTED_IMPORTS" >&2
  exit 1
fi
if ! rg --quiet \
  '^[[:space:]]*import[[:space:]]+UniversalAiConnector[[:space:]]*$' \
  "$APP_SOURCES" \
  "$SAMPLE_SUPPORT_SOURCES" \
  --glob '*.swift'; then
  echo "The iOS application must import the UniversalAiConnector Swift Package product." >&2
  exit 1
fi
if ! rg --quiet 'productName = UniversalAiConnector;' "$PROJECT/project.pbxproj"; then
  echo "The iOS project must depend on the UniversalAiConnector Swift Package product." >&2
  exit 1
fi
if rg --quiet \
  'productName = (UniversalAiConnectorBridge|UniversalAiConnectorPOC);' \
  "$PROJECT/project.pbxproj"; then
  echo "The iOS project references the bridge or a retired compatibility product." >&2
  exit 1
fi

build_sample() {
  xcodebuild build \
    -project "$PROJECT" \
    -scheme UniversalAiConnectorSample \
    -destination "$DESTINATION" \
    -derivedDataPath "$DERIVED_DATA" \
    "$@"
}

if [[ "$DESTINATION" == *"platform=iOS Simulator"* ]]; then
  build_sample
else
  build_sample \
    CODE_SIGN_IDENTITY= \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO
fi
