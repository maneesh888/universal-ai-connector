#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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

while IFS= read -r changed_path; do
  case "$changed_path" in
    bridge/src/* | \
      bridge/build.gradle.kts | \
      gradle/libs.versions.toml | \
      scripts/check-live.sh | \
      scripts/live-impact.sh | \
      .github/workflows/live.yml)
      echo "true"
      exit 0
      ;;
  esac
done < <(
  git -C "$ROOT" diff \
    --name-only \
    --diff-filter=ACDMR \
    "$BASE_SHA...$HEAD_SHA"
)

echo "false"
