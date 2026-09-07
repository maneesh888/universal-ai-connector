#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT/samples/ios/UniversalAiConnectorSample.xcodeproj"
TARGET="UniversalAiConnectorExtensionConsumer"
EXTENSION_SOURCES="$ROOT/samples/ios/UniversalAiConnectorExtensionConsumer"
DESTINATION="${UAC_EXTENSION_DESTINATION:-}"
DERIVED_DATA="${UAC_EXTENSION_DERIVED_DATA:-${TMPDIR:-/tmp}/universal-ai-connector-extension-derived}"

if [[ -z "$DESTINATION" ]]; then
  DESTINATION="$("$ROOT/scripts/simulator-destination.sh")"
fi

if [[ "${UAC_SKIP_XCFRAMEWORK_BUILD:-0}" != "1" ]]; then
  "$ROOT/scripts/build-xcframework.sh"
fi

UNSUPPORTED_IMPORTS="$(
  rg --no-filename --only-matching \
    '^[[:space:]]*import[[:space:]]+[A-Za-z_][A-Za-z0-9_]*' \
    "$EXTENSION_SOURCES" \
    --glob '*.swift' |
    awk '{print $2}' |
    LC_ALL=C sort -u |
    grep -Ev '^(UIKit|UniversalAiConnector)$' || true
)"
if [[ -n "$UNSUPPORTED_IMPORTS" ]]; then
  echo "The iOS app-extension consumer imports unsupported modules:" >&2
  printf '%s\n' "$UNSUPPORTED_IMPORTS" >&2
  exit 1
fi
if ! rg --quiet \
  '^[[:space:]]*import[[:space:]]+UniversalAiConnector[[:space:]]*$' \
  "$EXTENSION_SOURCES" \
  --glob '*.swift'; then
  echo "The iOS app-extension consumer must import the supported Swift product." >&2
  exit 1
fi
if rg --quiet \
  '^[[:space:]]*import[[:space:]]+UniversalAiConnectorBridge[[:space:]]*$' \
  "$EXTENSION_SOURCES" \
  --glob '*.swift'; then
  echo "The iOS app-extension consumer must not import the Kotlin bridge." >&2
  exit 1
fi
if ! rg --quiet 'productType = "com\.apple\.product-type\.app-extension";' \
  "$PROJECT/project.pbxproj"; then
  echo "The iOS project must contain a real app-extension target." >&2
  exit 1
fi
if ! rg --quiet 'APPLICATION_EXTENSION_API_ONLY = YES;' "$PROJECT/project.pbxproj"; then
  echo "The iOS app-extension target must enforce extension-safe APIs." >&2
  exit 1
fi

xcodebuild build \
  -project "$PROJECT" \
  -scheme "$TARGET" \
  -configuration Debug \
  -destination "$DESTINATION" \
  -derivedDataPath "$DERIVED_DATA" \
  CODE_SIGN_IDENTITY= \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO
