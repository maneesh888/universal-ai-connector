#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UAC_ADB="${UAC_ADB:-}"
UAC_ANDROID_SERIAL="${UAC_ANDROID_SERIAL:-}"

if [[ -z "$UAC_ADB" ]] && command -v adb >/dev/null 2>&1; then
  UAC_ADB="$(command -v adb)"
fi

if [[ -z "$UAC_ADB" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  UAC_ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
fi

if [[ -z "$UAC_ADB" && -n "${ANDROID_HOME:-}" ]]; then
  UAC_ADB="$ANDROID_HOME/platform-tools/adb"
fi

if [[ -z "$UAC_ADB" ]]; then
  mac_sdk_adb="/Users/$(id -un)/Library/Android/sdk/platform-tools/adb"
  if [[ -x "$mac_sdk_adb" ]]; then
    UAC_ADB="$mac_sdk_adb"
  fi
fi

if [[ ! -x "$UAC_ADB" ]]; then
  echo "adb was not found. Set UAC_ADB or add Android SDK platform-tools to PATH." >&2
  exit 1
fi

run_adb() {
  if [[ -n "$UAC_ANDROID_SERIAL" ]]; then
    "$UAC_ADB" -s "$UAC_ANDROID_SERIAL" "$@"
  else
    "$UAC_ADB" "$@"
  fi
}

run_adb wait-for-device
if [[ "$(run_adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]]; then
  echo "The selected Android device is connected but has not finished booting." >&2
  exit 1
fi

"$ROOT/gradlew" :samples:android:installDebug
run_adb shell am start -W \
  -n com.maneesh.universalai.samples.android/.MainActivity
