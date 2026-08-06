#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:---hygiene}"

usage() {
  cat <<'EOF'
Usage: ./scripts/check-environment.sh [--hygiene|--quick|--full]

  --hygiene  Check the standard shell, Git, and secret-scan tools.
  --quick    Check hygiene tools plus the Java and Android toolchains.
  --full     Check quick tools plus the Apple toolchain on macOS.
EOF
}

case "$MODE" in
  --hygiene|hygiene)
    MODE="--hygiene"
    ;;
  --quick|quick)
    MODE="--quick"
    ;;
  --full|full)
    MODE="--full"
    ;;
  --help|-h|help)
    usage
    exit 0
    ;;
  *)
    echo "Unknown environment-check mode: $MODE" >&2
    usage >&2
    exit 2
    ;;
esac

require_command() {
  local command_name="$1"
  local explanation="$2"

  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Contributor environment is missing '$command_name': $explanation" >&2
    return 1
  fi
}

require_standard_env() {
  local env_path
  local probe_output
  local probe_status

  env_path="$(command -v env || true)"
  if [[ -z "$env_path" ]]; then
    echo "Contributor environment is missing the standard 'env' command." >&2
    echo "Install the operating system's core command-line utilities." >&2
    return 1
  fi

  probe_status=0
  probe_output="$(
    env UAC_ENV_COMMAND_PROBE=works \
      /bin/sh -c 'printf "%s" "$UAC_ENV_COMMAND_PROBE"'
  )" || probe_status=$?

  if [[ "$probe_status" -ne 0 || "$probe_output" != "works" ]]; then
    echo "Contributor environment has a non-standard 'env' command: $env_path" >&2
    echo "The project requires 'env NAME=value command' to execute that command." >&2
    echo "A user executable may be shadowing the operating system utility." >&2
    echo "Run 'type -a env', rename the conflicting executable, and keep PATH setup in your shell profile." >&2
    return 1
  fi
}

require_java_21() {
  local java_path
  local java_settings
  local java_specification_version
  local java_status

  java_path="$("$ROOT/scripts/resolve-jdk-tool.sh" java)" || return 1
  "$ROOT/scripts/resolve-jdk-tool.sh" jar >/dev/null || return 1
  "$ROOT/scripts/resolve-jdk-tool.sh" javap >/dev/null || return 1

  java_status=0
  java_settings="$("$java_path" -XshowSettings:properties -version 2>&1)" || java_status=$?
  if [[ "$java_status" -ne 0 ]]; then
    echo "Contributor environment could not inspect Gradle's selected Java executable." >&2
    echo "Resolved Gradle java: $java_path" >&2
    return 1
  fi
  java_specification_version="$(
    printf '%s\n' "$java_settings" |
      sed -n 's/^[[:space:]]*java\.specification\.version = //p' |
      head -n 1
  )"
  java_specification_version="${java_specification_version%$'\r'}"

  if [[ "$java_specification_version" != "21" ]]; then
    echo "Contributor environment requires Java 21 for $MODE checks." >&2
    echo "Resolved Gradle java: $java_path" >&2
    echo "Detected Java specification version: ${java_specification_version:-unknown}" >&2
    echo "Set JAVA_HOME to a Java 21 JDK, or unset it and select Java 21 through PATH." >&2
    return 1
  fi
}

resolve_android_sdk() {
  local sdk_root

  sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "$sdk_root" ]]; then
    case "$(uname -s)" in
      Darwin)
        sdk_root="$HOME/Library/Android/sdk"
        ;;
      Linux)
        sdk_root="$HOME/Android/Sdk"
        ;;
    esac
  fi

  printf '%s' "$sdk_root"
}

require_android_sdk() {
  local sdk_root

  sdk_root="$(resolve_android_sdk)"
  if [[ -z "$sdk_root" || ! -d "$sdk_root" ]]; then
    echo "Contributor environment could not find the Android SDK." >&2
    echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to the installed SDK." >&2
    return 1
  fi
  if [[ ! -d "$sdk_root/platforms/android-36" ]]; then
    echo "Android SDK platform 36 is missing from: $sdk_root" >&2
    return 1
  fi
  if [[ ! -d "$sdk_root/build-tools/36.1.0" ]]; then
    echo "Android Build Tools 36.1.0 are missing from: $sdk_root" >&2
    return 1
  fi
}

require_apple_toolchain() {
  local simulator_sdk_path
  local simulator_sdk_status
  local xcodebuild_path
  local xcodebuild_status

  if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "The complete local gate requires macOS because it builds the Apple package and samples." >&2
    return 1
  fi

  require_command xcodebuild "Install and select Xcode." || return 1
  require_command xcrun "Install and select Xcode command-line tools." || return 1

  xcodebuild_path="$(command -v xcodebuild)"
  xcodebuild_status=0
  xcodebuild -version >/dev/null 2>&1 || xcodebuild_status=$?
  if [[ "$xcodebuild_status" -ne 0 ]]; then
    echo "Contributor environment could not execute the selected Xcode: $xcodebuild_path" >&2
    echo "Select a complete Xcode installation with xcode-select or DEVELOPER_DIR." >&2
    return 1
  fi

  simulator_sdk_status=0
  simulator_sdk_path="$(xcrun --sdk iphonesimulator --show-sdk-path 2>/dev/null)" ||
    simulator_sdk_status=$?
  if [[ "$simulator_sdk_status" -ne 0 || -z "$simulator_sdk_path" ]]; then
    echo "Contributor environment could not resolve the iOS Simulator SDK through xcrun: $(command -v xcrun)" >&2
    echo "Install the iOS Simulator platform for the selected Xcode." >&2
    return 1
  fi
}

require_standard_env
require_command bash "Bash runs the committed repository scripts."
require_command git "Git provides source and worktree checks."
require_command rg "Ripgrep performs fail-closed secret scanning."
require_command ruby "Ruby validates the parsed Dependabot YAML policy."

if [[ "$MODE" == "--quick" || "$MODE" == "--full" ]]; then
  require_java_21
  require_command unzip "Android artifact-boundary checks inspect AAR contents."
  require_android_sdk
fi

if [[ "$MODE" == "--full" ]]; then
  require_apple_toolchain
fi

echo "Contributor environment preflight passed for $MODE."
