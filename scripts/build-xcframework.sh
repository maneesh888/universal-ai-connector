#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/bridge/build/XCFrameworks/debug/UniversalAiConnectorBridge.xcframework"
DESTINATION="$ROOT/swift-package/Artifacts/UniversalAiConnectorBridge.xcframework"
FRAMEWORK_INFO="$DESTINATION/ios-arm64-simulator/UniversalAiConnectorBridge.framework/Info.plist"

"$ROOT/gradlew" :bridge:assembleUniversalAiConnectorBridgeDebugXCFramework

if [[ ! -d "$SOURCE" ]]; then
  echo "Expected XCFramework was not generated at: $SOURCE" >&2
  exit 1
fi

rm -rf "$DESTINATION"
mkdir -p "$(dirname "$DESTINATION")"
cp -R "$SOURCE" "$DESTINATION"

MINIMUM_OS_VERSION="$(
  /usr/libexec/PlistBuddy -c "Print :MinimumOSVersion" "$FRAMEWORK_INFO"
)"
if [[ "$MINIMUM_OS_VERSION" != "17.0" ]]; then
  echo "Expected XCFramework minimum iOS version 17.0, found: $MINIMUM_OS_VERSION" >&2
  exit 1
fi

echo "XCFramework ready: $DESTINATION"
