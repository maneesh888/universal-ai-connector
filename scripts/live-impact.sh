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

adapter_active=false
if git -C "$ROOT" ls-tree -r --name-only "$BASE_SHA" -- "$OPENAI_ADAPTER_PATH" |
    grep -q . ||
  git -C "$ROOT" ls-tree -r --name-only "$HEAD_SHA" -- "$OPENAI_ADAPTER_PATH" |
    grep -q .; then
  adapter_active=true
fi

if [[ "$adapter_active" != "true" ]]; then
  echo "false"
  exit 0
fi

CHANGED_PATHS_FILE="$(mktemp)"
trap 'rm -f "$CHANGED_PATHS_FILE"' EXIT

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
