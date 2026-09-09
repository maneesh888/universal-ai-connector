#!/usr/bin/env bash
set -euo pipefail

ROOT="${UAC_REPOSITORY_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
MODE="commits"
BASE_SHA=""
HEAD_SHA=""

usage() {
  cat >&2 <<'EOF'
Usage: ./scripts/verification-impact.sh --cached
       ./scripts/verification-impact.sh <base-sha> <head-sha>

Prints docs-only only when every changed path is a regular README.md, top-level LICENSE*, or plain
Markdown document under docs/. Prints full for every other non-empty change. Invalid inputs fail
closed.
EOF
}

case "$#" in
  1)
    if [[ "$1" != "--cached" ]]; then
      usage
      exit 2
    fi
    MODE="cached"
    ;;
  2)
    BASE_SHA="$1"
    HEAD_SHA="$2"
    ;;
  *)
    usage
    exit 2
    ;;
esac

if [[ "$MODE" == "commits" ]]; then
  for revision in "$BASE_SHA" "$HEAD_SHA"; do
    if ! git -C "$ROOT" cat-file -e "$revision^{commit}" 2>/dev/null; then
      echo "Verification-impact classification requires two valid commit SHAs." >&2
      exit 2
    fi
  done
  if ! git -C "$ROOT" merge-base "$BASE_SHA" "$HEAD_SHA" >/dev/null 2>&1; then
    echo "Verification-impact classification requires commits with a common ancestor." >&2
    exit 2
  fi
elif ! git -C "$ROOT" rev-parse --verify HEAD^{commit} >/dev/null 2>&1; then
  echo "Cached verification-impact classification requires an existing HEAD commit." >&2
  exit 2
fi

CHANGED_PATHS_FILE="$(mktemp)"
trap 'rm -f "$CHANGED_PATHS_FILE"' EXIT

if [[ "$MODE" == "cached" ]]; then
  if ! git -C "$ROOT" diff --cached --name-only --diff-filter=ACDMRT -z > "$CHANGED_PATHS_FILE"; then
    echo "Verification-impact classification could not inspect the staged changes." >&2
    exit 2
  fi
else
  if ! git -C "$ROOT" diff \
    --name-only \
    --diff-filter=ACDMRT \
    -z \
    "$BASE_SHA...$HEAD_SHA" > "$CHANGED_PATHS_FILE"; then
    echo "Verification-impact classification could not compare the requested commits." >&2
    exit 2
  fi
fi

path_is_plain_document() {
  local changed_path="$1"
  case "$changed_path" in
    README.md | docs/*.md)
      return 0
      ;;
    LICENSE*)
      [[ "$changed_path" != */* ]]
      return
      ;;
    *)
      return 1
      ;;
  esac
}

commit_path_is_plain_file() {
  local revision="$1"
  local changed_path="$2"
  local entry
  local mode

  entry="$(git -C "$ROOT" ls-tree "$revision" -- "$changed_path")"
  if [[ -z "$entry" ]]; then
    return 0
  fi
  mode="${entry%% *}"
  [[ "$mode" == "100644" ]]
}

index_path_is_plain_file() {
  local changed_path="$1"
  local entry
  local mode

  entry="$(git -C "$ROOT" ls-files --stage -- "$changed_path")"
  if [[ -z "$entry" ]]; then
    return 0
  fi
  mode="${entry%% *}"
  [[ "$mode" == "100644" ]]
}

changed_count=0
while IFS= read -r -d '' changed_path; do
  changed_count=$((changed_count + 1))
  if ! path_is_plain_document "$changed_path"; then
    echo "full"
    exit 0
  fi

  if [[ "$MODE" == "cached" ]]; then
    if ! commit_path_is_plain_file HEAD "$changed_path" ||
      ! index_path_is_plain_file "$changed_path"; then
      echo "full"
      exit 0
    fi
  elif ! commit_path_is_plain_file "$BASE_SHA" "$changed_path" ||
    ! commit_path_is_plain_file "$HEAD_SHA" "$changed_path"; then
    echo "full"
    exit 0
  fi
done < "$CHANGED_PATHS_FILE"

if (( changed_count == 0 )); then
  echo "full"
else
  echo "docs-only"
fi
