#!/usr/bin/env bash
set -euo pipefail

ROOT="${UAC_REPOSITORY_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BASE_SHA="${1:-}"
HEAD_SHA="${2:-}"
DELIVERED_PROVIDERS=("openai" "anthropic")

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

SELECT_ALL_DELIVERED="false"
SELECT_OPENAI="false"
SELECT_ANTHROPIC="false"
SELECT_OPENROUTER="false"

provider_is_delivered() {
  local requested_provider="$1"
  local delivered_provider
  for delivered_provider in "${DELIVERED_PROVIDERS[@]}"; do
    if [[ "$delivered_provider" == "$requested_provider" ]]; then
      return 0
    fi
  done
  return 1
}

select_provider() {
  local requested_provider="$1"
  if ! provider_is_delivered "$requested_provider"; then
    SELECT_ALL_DELIVERED="true"
    return
  fi
  case "$requested_provider" in
    openai)
      SELECT_OPENAI="true"
      ;;
    anthropic)
      SELECT_ANTHROPIC="true"
      ;;
    openrouter)
      SELECT_OPENROUTER="true"
      ;;
  esac
}

DELIVERED_OPENAI_SEEN="false"
DELIVERED_ANTHROPIC_SEEN="false"
DELIVERED_OPENROUTER_SEEN="false"
for delivered_provider in "${DELIVERED_PROVIDERS[@]}"; do
  case "$delivered_provider" in
    openai)
      if [[ "$DELIVERED_OPENAI_SEEN" == "true" ]]; then
        echo "Live-impact classifier contains a duplicate delivered provider." >&2
        exit 2
      fi
      DELIVERED_OPENAI_SEEN="true"
      ;;
    anthropic)
      if [[ "$DELIVERED_ANTHROPIC_SEEN" == "true" ]]; then
        echo "Live-impact classifier contains a duplicate delivered provider." >&2
        exit 2
      fi
      DELIVERED_ANTHROPIC_SEEN="true"
      ;;
    openrouter)
      if [[ "$DELIVERED_OPENROUTER_SEEN" == "true" ]]; then
        echo "Live-impact classifier contains a duplicate delivered provider." >&2
        exit 2
      fi
      DELIVERED_OPENROUTER_SEEN="true"
      ;;
    *)
      echo "Live-impact classifier contains an unsupported delivered provider." >&2
      exit 2
      ;;
  esac
done

while IFS= read -r -d '' changed_path; do
  case "$changed_path" in
    bridge/src/*/internal/provider/openai/*)
      select_provider "openai"
      ;;
    bridge/src/*/internal/provider/anthropic/*)
      select_provider "anthropic"
      ;;
    bridge/src/*/internal/provider/openrouter/*)
      select_provider "openrouter"
      ;;
    bridge/src/*/internal/provider/* | \
      bridge/src/* | \
      bridge/build.gradle.kts | \
      swift-package/* | \
      build.gradle.kts | \
      settings.gradle.kts | \
      gradle.properties | \
      gradle/* | \
      buildSrc/* | \
      build-logic/* | \
      gradlew | \
      gradlew.bat | \
      .env.live.example | \
      .githooks/pre-push | \
      scripts/check-live.sh | \
      scripts/live-impact.sh | \
      scripts/secret-scan.sh | \
      .github/workflows/live.yml)
      SELECT_ALL_DELIVERED="true"
      ;;
  esac
done < "$CHANGED_PATHS_FILE"

provider_result=""
for delivered_provider in "${DELIVERED_PROVIDERS[@]}"; do
  provider_selected="$SELECT_ALL_DELIVERED"
  case "$delivered_provider" in
    openai)
      if [[ "$SELECT_OPENAI" == "true" ]]; then
        provider_selected="true"
      fi
      ;;
    anthropic)
      if [[ "$SELECT_ANTHROPIC" == "true" ]]; then
        provider_selected="true"
      fi
      ;;
    openrouter)
      if [[ "$SELECT_OPENROUTER" == "true" ]]; then
        provider_selected="true"
      fi
      ;;
  esac
  if [[ "$provider_selected" == "true" ]]; then
    if [[ -z "$provider_result" ]]; then
      provider_result="$delivered_provider"
    else
      provider_result="$provider_result,$delivered_provider"
    fi
  fi
done

if [[ -z "$provider_result" ]]; then
  provider_result="none"
fi
echo "$provider_result"
