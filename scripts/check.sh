#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:---full}"

usage() {
  cat <<'EOF'
Usage: ./scripts/check.sh [--hygiene|--quick|--full]

  --hygiene  Validate shell syntax, secrets, and whitespace, including untracked files.
  --quick    Run hygiene plus deterministic JVM, Android, iOS Simulator, and consumer checks.
  --full     Run quick coverage plus XCFramework, Swift Package, and iOS simulator/device sample checks.
             This is the default.
EOF
}

run_hygiene() {
  while IFS= read -r shell_file; do
    bash -n "$ROOT/$shell_file"
  done < <(git -C "$ROOT" ls-files '*.sh' '.githooks/*')

  "$ROOT/scripts/secret-scan.sh"
  "$ROOT/scripts/tests/secret-scan-test.sh"
  git -C "$ROOT" diff --check

  local temp_index_directory
  local temp_index
  local temp_object_directory
  local repository_object_directory
  temp_index_directory="$(mktemp -d)"
  temp_index="$temp_index_directory/index"
  temp_object_directory="$temp_index_directory/objects"
  repository_object_directory="$(git -C "$ROOT" rev-parse --git-path objects)"
  mkdir -p "$temp_object_directory"

  GIT_INDEX_FILE="$temp_index" \
    GIT_OBJECT_DIRECTORY="$temp_object_directory" \
    GIT_ALTERNATE_OBJECT_DIRECTORIES="$repository_object_directory" \
    git -C "$ROOT" read-tree --empty
  GIT_INDEX_FILE="$temp_index" \
    GIT_OBJECT_DIRECTORY="$temp_object_directory" \
    GIT_ALTERNATE_OBJECT_DIRECTORIES="$repository_object_directory" \
    git -C "$ROOT" add --intent-to-add .
  GIT_INDEX_FILE="$temp_index" \
    GIT_OBJECT_DIRECTORY="$temp_object_directory" \
    GIT_ALTERNATE_OBJECT_DIRECTORIES="$repository_object_directory" \
    git -C "$ROOT" diff --check
  rm -rf "$temp_index_directory"

  echo "Universal AI Connector hygiene checks passed."
}

run_script_tests() {
  "$ROOT/scripts/tests/run-android-sample-test.sh"
}

run_cross_platform_gradle_checks() {
  "$ROOT/gradlew" \
    :bridge:jvmTest \
    :bridge:testAndroidHostTest \
    :bridge:bundleAndroidMainAar \
    :bridge:iosSimulatorArm64Test \
    :samples:jvm-console:consumerCheck \
    :samples:android:consumerCheck
}

run_quick() {
  run_hygiene
  run_script_tests
  run_cross_platform_gradle_checks
  echo "Universal AI Connector quick checks passed."
}

run_full() {
  run_hygiene
  run_script_tests
  run_cross_platform_gradle_checks

  # Build once, then reuse the artifact for package tests and both sample destinations.
  "$ROOT/scripts/build-xcframework.sh"
  UAC_SKIP_XCFRAMEWORK_BUILD=1 "$ROOT/scripts/test-swift-package.sh"
  UAC_SKIP_XCFRAMEWORK_BUILD=1 "$ROOT/scripts/build-sample.sh"
  UAC_SKIP_XCFRAMEWORK_BUILD=1 "$ROOT/scripts/build-sample-device.sh"

  echo "Universal AI Connector complete deterministic checks passed."
}

case "$MODE" in
  --hygiene|hygiene)
    run_hygiene
    ;;
  --quick|quick)
    run_quick
    ;;
  --full|full)
    run_full
    ;;
  --help|-h|help)
    usage
    ;;
  *)
    echo "Unknown check mode: $MODE" >&2
    usage >&2
    exit 2
    ;;
esac
