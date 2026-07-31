#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIRECTORY="$(mktemp -d)"
FAKE_PATH="$TEST_DIRECTORY/path"
FAKE_ANDROID_SDK="$TEST_DIRECTORY/android-sdk"
FAKE_JAVA_17_HOME="$TEST_DIRECTORY/java-17"
FAKE_JAVA_21_HOME="$TEST_DIRECTORY/java-21"
FAKE_PATH_JAVA_HOME="$TEST_DIRECTORY/path-java-21"
PREFLIGHT_OUTPUT="$TEST_DIRECTORY/preflight.log"
FAILURES=0

cleanup() {
  rm -rf "$TEST_DIRECTORY"
}
trap cleanup EXIT

write_executable() {
  local path="$1"
  shift

  printf '%s\n' "$@" > "$path"
  chmod +x "$path"
}

write_java() {
  local path="$1"
  local version="$2"
  local java_home="$3"

  write_executable "$path" \
    '#!/bin/sh' \
    "printf '%s\\n' '    java.home = $java_home' '    java.specification.version = $version' >&2"
}

write_windows_java() {
  local path="$1"
  local version="$2"
  local java_home="$3"

  write_executable "$path" \
    '#!/bin/sh' \
    "printf '%s\\r\\n' '    java.home = $java_home' '    java.specification.version = $version' >&2"
}

record_failure() {
  echo "$1" >&2
  FAILURES=$((FAILURES + 1))
}

mkdir -p \
  "$FAKE_PATH" \
  "$FAKE_ANDROID_SDK/platforms/android-36" \
  "$FAKE_ANDROID_SDK/build-tools/36.1.0" \
  "$FAKE_JAVA_17_HOME/bin" \
  "$FAKE_JAVA_21_HOME/bin" \
  "$FAKE_PATH_JAVA_HOME/bin"
ln -s /usr/bin/env "$FAKE_PATH/env"
ln -s "$(command -v bash)" "$FAKE_PATH/bash"
ln -s "$(command -v dirname)" "$FAKE_PATH/dirname"
ln -s "$(command -v git)" "$FAKE_PATH/git"
ln -s "$(command -v head)" "$FAKE_PATH/head"
ln -s "$(command -v rg)" "$FAKE_PATH/rg"
ln -s "$(command -v sed)" "$FAKE_PATH/sed"
ln -s "$(command -v uname)" "$FAKE_PATH/uname"
ln -s "$(command -v unzip)" "$FAKE_PATH/unzip"

write_java "$FAKE_JAVA_17_HOME/bin/java" 17 "$FAKE_JAVA_17_HOME"
write_executable "$FAKE_JAVA_17_HOME/bin/jar" '#!/bin/sh' 'exit 0'
write_java "$FAKE_JAVA_21_HOME/bin/java" 21 "$FAKE_JAVA_21_HOME"
write_executable "$FAKE_JAVA_21_HOME/bin/jar" '#!/bin/sh' 'exit 0'
write_windows_java "$FAKE_PATH/java" 21 "$FAKE_PATH_JAVA_HOME"
write_executable "$FAKE_PATH_JAVA_HOME/bin/jar" '#!/bin/sh' 'exit 0'
write_executable "$FAKE_PATH/jar" '#!/bin/sh' 'exit 69'

PATH="$FAKE_PATH" /bin/bash "$ROOT/scripts/check-environment.sh" --hygiene \
  > "$PREFLIGHT_OUTPUT" 2>&1
if ! grep -Fq "Contributor environment preflight passed for --hygiene." "$PREFLIGHT_OUTPUT"; then
  record_failure "Contributor environment preflight did not accept standard env behavior."
fi

rm -f "$FAKE_PATH/env"
write_executable "$FAKE_PATH/env" '#!/bin/sh' 'exit 0'

shadow_status=0
PATH="$FAKE_PATH" /bin/bash "$ROOT/scripts/check-environment.sh" --hygiene \
  > "$PREFLIGHT_OUTPUT" 2>&1 || shadow_status=$?
if [[ "$shadow_status" -ne 1 ]]; then
  record_failure "Contributor environment preflight did not reject a shadowed env command."
fi
if ! grep -Fq "Contributor environment has a non-standard 'env' command:" "$PREFLIGHT_OUTPUT"; then
  record_failure "Contributor environment preflight did not explain the shadowed env command."
fi

rm -f "$FAKE_PATH/env"
ln -s /usr/bin/env "$FAKE_PATH/env"

java_status=0
JAVA_HOME="$FAKE_JAVA_17_HOME" \
  ANDROID_HOME="$FAKE_ANDROID_SDK" \
  PATH="$FAKE_PATH" \
  /bin/bash "$ROOT/scripts/check-environment.sh" --quick \
  > "$PREFLIGHT_OUTPUT" 2>&1 || java_status=$?
if [[ "$java_status" -ne 1 ]]; then
  record_failure "Contributor environment preflight did not reject Gradle's Java 17 from JAVA_HOME."
fi
if ! grep -Fq "Resolved Gradle java: $FAKE_JAVA_17_HOME/bin/java" "$PREFLIGHT_OUTPUT"; then
  record_failure "Contributor environment preflight did not report Gradle's JAVA_HOME-selected executable."
fi

write_java "$FAKE_PATH/java" 17 "$FAKE_JAVA_17_HOME"
java_status=0
JAVA_HOME="$FAKE_JAVA_21_HOME" \
  ANDROID_HOME="$FAKE_ANDROID_SDK" \
  PATH="$FAKE_PATH" \
  /bin/bash "$ROOT/scripts/check-environment.sh" --quick \
  > "$PREFLIGHT_OUTPUT" 2>&1 || java_status=$?
if [[ "$java_status" -ne 0 ]]; then
  record_failure "Contributor environment preflight rejected Gradle's Java 21 from JAVA_HOME."
fi

write_windows_java "$FAKE_PATH/java" 21 "$FAKE_PATH_JAVA_HOME"
resolved_jar="$(
  JAVA_HOME="" PATH="$FAKE_PATH" \
    /bin/bash "$ROOT/scripts/resolve-jdk-tool.sh" jar
)"
if [[ "$resolved_jar" != "$FAKE_PATH_JAVA_HOME/bin/jar" ]]; then
  record_failure "Contributor environment did not resolve jar from the PATH-selected Java 21 JDK."
fi

java_status=0
JAVA_HOME="" \
  ANDROID_HOME="$FAKE_ANDROID_SDK" \
  PATH="$FAKE_PATH" \
  /bin/bash "$ROOT/scripts/check-environment.sh" --quick \
  > "$PREFLIGHT_OUTPUT" 2>&1 || java_status=$?
if [[ "$java_status" -ne 0 ]]; then
  record_failure "Contributor environment rejected the complete PATH-selected Java 21 JDK."
fi

rm -f "$FAKE_PATH/uname"
write_executable "$FAKE_PATH/uname" '#!/bin/sh' 'printf "%s\n" Darwin'
write_executable "$FAKE_PATH/xcodebuild" '#!/bin/sh' 'exit 69'
write_executable "$FAKE_PATH/xcrun" \
  '#!/bin/sh' \
  'printf "%s\n" /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk'

apple_status=0
JAVA_HOME="$FAKE_JAVA_21_HOME" \
  ANDROID_HOME="$FAKE_ANDROID_SDK" \
  PATH="$FAKE_PATH" \
  /bin/bash "$ROOT/scripts/check-environment.sh" --full \
  > "$PREFLIGHT_OUTPUT" 2>&1 || apple_status=$?
if [[ "$apple_status" -ne 1 ]]; then
  record_failure "Contributor environment preflight did not reject a nonfunctional selected Xcode."
fi
if ! grep -Fq "Contributor environment could not execute the selected Xcode:" "$PREFLIGHT_OUTPUT"; then
  record_failure "Contributor environment preflight did not explain the nonfunctional selected Xcode."
fi

write_executable "$FAKE_PATH/xcodebuild" \
  '#!/bin/sh' \
  'printf "%s\n" "Xcode 16.4" "Build version 16F6"'
write_executable "$FAKE_PATH/xcrun" '#!/bin/sh' 'exit 69'

apple_status=0
JAVA_HOME="$FAKE_JAVA_21_HOME" \
  ANDROID_HOME="$FAKE_ANDROID_SDK" \
  PATH="$FAKE_PATH" \
  /bin/bash "$ROOT/scripts/check-environment.sh" --full \
  > "$PREFLIGHT_OUTPUT" 2>&1 || apple_status=$?
if [[ "$apple_status" -ne 1 ]]; then
  record_failure "Contributor environment preflight did not reject a failed iOS Simulator SDK lookup."
fi
if ! grep -Fq "Contributor environment could not resolve the iOS Simulator SDK through xcrun:" "$PREFLIGHT_OUTPUT"; then
  record_failure "Contributor environment preflight did not explain the failed iOS Simulator SDK lookup."
fi

write_executable "$FAKE_PATH/xcrun" \
  '#!/bin/sh' \
  'printf "%s\n" /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk'

apple_status=0
JAVA_HOME="$FAKE_JAVA_21_HOME" \
  ANDROID_HOME="$FAKE_ANDROID_SDK" \
  PATH="$FAKE_PATH" \
  /bin/bash "$ROOT/scripts/check-environment.sh" --full \
  > "$PREFLIGHT_OUTPUT" 2>&1 || apple_status=$?
if [[ "$apple_status" -ne 0 ]]; then
  record_failure "Contributor environment preflight rejected an executable Xcode 16.4 toolchain."
fi

if (( FAILURES > 0 )); then
  exit 1
fi

echo "Contributor environment preflight regression tests passed."
