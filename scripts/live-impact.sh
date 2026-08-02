#!/usr/bin/env bash
set -euo pipefail

ROOT="${UAC_REPOSITORY_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BASE_SHA="${1:-}"
HEAD_SHA="${2:-}"
OPENAI_ADAPTER_PATH="bridge/src/commonMain/kotlin/com/maneesh/universalai/connector/internal/provider/openai"

usage() {
  echo "Usage: ./scripts/live-impact.sh <base-sha> <head-sha>" >&2
}

if [[ "$#" -ne 2 ]]; then
  usage
  exit 2
fi

for revision in "$BASE_SHA" "$HEAD_SHA"; do
  if ! git -C "$ROOT" cat-file -e "$revision^{commit}" 2>/dev/null; then
    echo "Live-impact classification requires two valid commit SHAs." >&2
    exit 2
  fi
done

if ! git -C "$ROOT" merge-base "$BASE_SHA" "$HEAD_SHA" >/dev/null 2>&1; then
  echo "Live-impact classification requires commits with a common ancestor." >&2
  exit 2
fi

ADAPTER_PATHS_FILE="$(mktemp)"
CHANGED_PATHS_FILE="$(mktemp)"
trap 'rm -f "$ADAPTER_PATHS_FILE" "$CHANGED_PATHS_FILE"' EXIT

tree_contains_adapter() {
  local revision="$1"

  if ! git -C "$ROOT" ls-tree \
    -r \
    --name-only \
    -z \
    "$revision" \
    -- "$OPENAI_ADAPTER_PATH" > "$ADAPTER_PATHS_FILE"; then
    echo "Live-impact classification could not inspect the requested commit." >&2
    exit 2
  fi

  [[ -s "$ADAPTER_PATHS_FILE" ]]
}

base_contains_adapter=false
head_contains_adapter=false
if tree_contains_adapter "$BASE_SHA"; then
  base_contains_adapter=true
fi
if tree_contains_adapter "$HEAD_SHA"; then
  head_contains_adapter=true
fi

if [[ "$base_contains_adapter" != "true" && "$head_contains_adapter" != "true" ]]; then
  echo "false"
  exit 0
fi

if ! git -C "$ROOT" diff \
  --name-only \
  --diff-filter=ACDMRT \
  -z \
  "$BASE_SHA...$HEAD_SHA" > "$CHANGED_PATHS_FILE"; then
  echo "Live-impact classification could not compare the requested commits." >&2
  exit 2
fi

while IFS= read -r -d '' changed_path; do
  case "$changed_path" in
    bridge/src/* | \
      bridge/build.gradle.kts | \
      build.gradle.kts | \
      settings.gradle.kts | \
      gradle.properties | \
      gradle/* | \
      buildSrc/* | \
      build-logic/* | \
      gradlew | \
      gradlew.bat | \
      scripts/check-live.sh | \
      scripts/live-impact.sh | \
      .github/workflows/live.yml)
      echo "true"
      exit 0
      ;;
  esac
done < "$CHANGED_PATHS_FILE"

echo "false"
