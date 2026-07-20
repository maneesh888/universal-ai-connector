#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIRECTORY="$(mktemp -d)"
trap 'rm -rf "$TEST_DIRECTORY"' EXIT

FAKE_ROOT="$TEST_DIRECTORY/repository"
FAKE_ADB="$TEST_DIRECTORY/adb"
TEST_LOG="$TEST_DIRECTORY/invocations.log"
mkdir -p "$FAKE_ROOT"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'printf "gradle:ANDROID_SERIAL=%s:%s\n" "${ANDROID_SERIAL:-<unset>}" "$*" >> "$UAC_TEST_LOG"' \
  > "$FAKE_ROOT/gradlew"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'printf "adb:%s\n" "$*" >> "$UAC_TEST_LOG"' \
  'if [[ "$*" == *"shell getprop sys.boot_completed"* ]]; then' \
  "  printf '1\\r\\n'" \
  'fi' \
  > "$FAKE_ADB"
chmod +x "$FAKE_ROOT/gradlew" "$FAKE_ADB"

# shellcheck source=../run-android-sample.sh
source "$ROOT/scripts/run-android-sample.sh"
ROOT="$FAKE_ROOT"
UAC_ADB="$FAKE_ADB"
export UAC_TEST_LOG="$TEST_LOG"

UAC_ANDROID_SERIAL="emulator-5554"
main

grep -Fx "adb:-s emulator-5554 wait-for-device" "$TEST_LOG" >/dev/null
grep -Fx "adb:-s emulator-5554 shell getprop sys.boot_completed" "$TEST_LOG" >/dev/null
grep -Fx "gradle:ANDROID_SERIAL=emulator-5554::samples:android:installDebug" "$TEST_LOG" >/dev/null
grep -Fx "adb:-s emulator-5554 shell am start -W -n com.maneesh.universalai.samples.android/.MainActivity" "$TEST_LOG" >/dev/null

: > "$TEST_LOG"
UAC_ANDROID_SERIAL=""
main

grep -Fx "adb:wait-for-device" "$TEST_LOG" >/dev/null
grep -Fx "gradle:ANDROID_SERIAL=<unset>::samples:android:installDebug" "$TEST_LOG" >/dev/null
grep -Fx "adb:shell am start -W -n com.maneesh.universalai.samples.android/.MainActivity" "$TEST_LOG" >/dev/null

echo "Android sample launch script tests passed."
