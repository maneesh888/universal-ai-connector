#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:---full}"

usage() {
  cat <<'EOF'
Usage: ./scripts/check.sh [--hygiene|--quick|--full]

  --hygiene  Scan for secrets and whitespace issues, including untracked files.
  --quick    Run Kotlin shared tests, the JVM consumer, and hygiene checks.
  --full     Run the complete deterministic Kotlin, JVM-consumer, and Apple suite.
             This is the default.
EOF
}

run_hygiene() {
  "$ROOT/scripts/secret-scan.sh"
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

run_quick() {
  "$ROOT/gradlew" \
    :bridge:jvmTest \
    :bridge:iosSimulatorArm64Test \
    :samples:jvm-console:consumerCheck
  run_hygiene
  echo "Universal AI Connector quick checks passed."
}

run_full() {
  "$ROOT/gradlew" \
    :bridge:jvmTest \
    :bridge:iosSimulatorArm64Test \
    :samples:jvm-console:consumerCheck

  # Build once, then reuse the same generated artifact for both Swift consumers.
  "$ROOT/scripts/build-xcframework.sh"
  UAC_SKIP_XCFRAMEWORK_BUILD=1 "$ROOT/scripts/test-swift-package.sh"
  UAC_SKIP_XCFRAMEWORK_BUILD=1 "$ROOT/scripts/build-sample.sh"

  run_hygiene
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
