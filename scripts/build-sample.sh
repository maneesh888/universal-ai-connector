#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESTINATION="$("$ROOT/scripts/simulator-destination.sh")"
DERIVED_DATA="${TMPDIR:-/tmp}/universal-ai-connector-sample-derived"

"$ROOT/scripts/build-xcframework.sh"

xcodebuild build \
  -project "$ROOT/samples/ios/UniversalAiConnectorPOCSample.xcodeproj" \
  -scheme UniversalAiConnectorPOCSample \
  -destination "$DESTINATION" \
  -derivedDataPath "$DERIVED_DATA" \
  CODE_SIGN_IDENTITY= \
  CODE_SIGNING_REQUIRED=NO
