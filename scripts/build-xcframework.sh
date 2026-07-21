#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/bridge/build/XCFrameworks/debug/UniversalAiConnectorBridge.xcframework"
DESTINATION="$ROOT/swift-package/Artifacts/UniversalAiConnectorBridge.xcframework"
XCFRAMEWORK_INFO="$DESTINATION/Info.plist"

"$ROOT/gradlew" :bridge:assembleUniversalAiConnectorBridgeDebugXCFramework

if [[ ! -d "$SOURCE" ]]; then
  echo "Expected XCFramework was not generated at: $SOURCE" >&2
  exit 1
fi

rm -rf "$DESTINATION"
mkdir -p "$(dirname "$DESTINATION")"
cp -R "$SOURCE" "$DESTINATION"

if [[ ! -f "$XCFRAMEWORK_INFO" ]]; then
  echo "Expected XCFramework metadata was not generated at: $XCFRAMEWORK_INFO" >&2
  exit 1
fi

slice_identifier() {
  local index="$1"
  /usr/libexec/PlistBuddy \
    -c "Print :AvailableLibraries:$index:LibraryIdentifier" \
    "$XCFRAMEWORK_INFO"
}

FIRST_IDENTIFIER="$(slice_identifier 0)"
SECOND_IDENTIFIER="$(slice_identifier 1)"
if slice_identifier 2 >/dev/null 2>&1; then
  echo "Expected exactly two XCFramework slices, found more than two." >&2
  exit 1
fi

ACTUAL_IDENTIFIERS="$(
  printf '%s\n%s\n' "$FIRST_IDENTIFIER" "$SECOND_IDENTIFIER" |
    LC_ALL=C sort
)"
EXPECTED_IDENTIFIERS="$(printf '%s\n%s\n' ios-arm64 ios-arm64-simulator)"
if [[ "$ACTUAL_IDENTIFIERS" != "$EXPECTED_IDENTIFIERS" ]]; then
  echo "Expected XCFramework slices ios-arm64 and ios-arm64-simulator; found:" >&2
  printf '%s\n' "$ACTUAL_IDENTIFIERS" >&2
  exit 1
fi

validate_xcframework_metadata() {
  local index="$1"
  local identifier
  local platform
  local architecture
  local variant

  identifier="$(slice_identifier "$index")"
  platform="$(
    /usr/libexec/PlistBuddy \
      -c "Print :AvailableLibraries:$index:SupportedPlatform" \
      "$XCFRAMEWORK_INFO"
  )"
  architecture="$(
    /usr/libexec/PlistBuddy \
      -c "Print :AvailableLibraries:$index:SupportedArchitectures:0" \
      "$XCFRAMEWORK_INFO"
  )"

  if [[ "$platform" != "ios" ]]; then
    echo "Expected $identifier to target iOS, found: $platform" >&2
    exit 1
  fi
  if [[ "$architecture" != "arm64" ]]; then
    echo "Expected $identifier metadata to contain arm64, found: $architecture" >&2
    exit 1
  fi
  if /usr/libexec/PlistBuddy \
    -c "Print :AvailableLibraries:$index:SupportedArchitectures:1" \
    "$XCFRAMEWORK_INFO" >/dev/null 2>&1; then
    echo "Expected $identifier metadata to contain only arm64." >&2
    exit 1
  fi

  case "$identifier" in
    ios-arm64)
      if /usr/libexec/PlistBuddy \
        -c "Print :AvailableLibraries:$index:SupportedPlatformVariant" \
        "$XCFRAMEWORK_INFO" >/dev/null 2>&1; then
        echo "Expected ios-arm64 to be a device slice without a platform variant." >&2
        exit 1
      fi
      ;;
    ios-arm64-simulator)
      variant="$(
        /usr/libexec/PlistBuddy \
          -c "Print :AvailableLibraries:$index:SupportedPlatformVariant" \
          "$XCFRAMEWORK_INFO"
      )"
      if [[ "$variant" != "simulator" ]]; then
        echo "Expected ios-arm64-simulator variant simulator, found: $variant" >&2
        exit 1
      fi
      ;;
    *)
      echo "Unexpected XCFramework slice identifier: $identifier" >&2
      exit 1
      ;;
  esac
}

validate_framework_slice() {
  local identifier="$1"
  local framework="$DESTINATION/$identifier/UniversalAiConnectorBridge.framework"
  local framework_info="$framework/Info.plist"
  local framework_header="$framework/Headers/UniversalAiConnectorBridge.h"
  local framework_binary="$framework/UniversalAiConnectorBridge"
  local minimum_os_version
  local architectures

  if [[ ! -f "$framework_info" ]]; then
    echo "Expected framework metadata was not generated at: $framework_info" >&2
    exit 1
  fi
  if [[ ! -f "$framework_header" ]]; then
    echo "Expected framework header was not generated at: $framework_header" >&2
    exit 1
  fi
  if [[ ! -f "$framework_binary" ]]; then
    echo "Expected framework binary was not generated at: $framework_binary" >&2
    exit 1
  fi

  minimum_os_version="$(
    /usr/libexec/PlistBuddy -c "Print :MinimumOSVersion" "$framework_info"
  )"
  if [[ "$minimum_os_version" != "17.0" ]]; then
    echo "Expected $identifier minimum iOS version 17.0, found: $minimum_os_version" >&2
    exit 1
  fi

  architectures="$(xcrun lipo -archs "$framework_binary")"
  if [[ "$architectures" != "arm64" ]]; then
    echo "Expected $identifier binary architecture arm64, found: $architectures" >&2
    exit 1
  fi

  if grep -q '@interface UACBUniversalAiConnector :' "$framework_header"; then
    echo "Product Kotlin client leaked into the $identifier callback-bridge header." >&2
    exit 1
  fi
  if grep -q 'Kotlinx_coroutines_coreFlow' "$framework_header"; then
    echo "Kotlin Flow leaked into the $identifier callback-bridge header." >&2
    exit 1
  fi
}

validate_xcframework_metadata 0
validate_xcframework_metadata 1
validate_framework_slice ios-arm64
validate_framework_slice ios-arm64-simulator

echo "XCFramework ready with ios-arm64 and ios-arm64-simulator slices: $DESTINATION"
