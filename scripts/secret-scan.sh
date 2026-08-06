#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v rg >/dev/null 2>&1; then
  echo "ripgrep (rg) is required for the repository secret scan." >&2
  exit 2
fi

if command -v git >/dev/null 2>&1 &&
  git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  while IFS= read -r -d '' tracked_live_input; do
    if [[ "$tracked_live_input" != ".env.live.example" ]]; then
      echo "A local live-input file is tracked and must be removed from the Git index." >&2
      exit 1
    fi
  done < <(git -C "$ROOT" ls-files -z -- '.env.live' '.env.live.*')
fi

SECRET_PATTERNS=(
  -e 'sk-[A-Za-z0-9_-]{20,}'
  -e 'Authorization:[[:space:]]*Bearer[[:space:]]+[A-Za-z0-9._-]+'
  -e 'api[_-]?key[[:space:]]*=[[:space:]]*"[^"]+"'
  -e "api[_-]?key[[:space:]]*=[[:space:]]*'[^']+'"
)

scan_status=0
rg --quiet \
  --no-config \
  --no-ignore \
  --hidden \
  --glob '!.git/**' \
  --glob '!**/build/**' \
  --glob '!swift-package/Artifacts/**' \
  --glob '!gradle/wrapper/gradle-wrapper.jar' \
  --glob '!.env.live' \
  --glob '!.env.live.*' \
  "${SECRET_PATTERNS[@]}" \
  "$ROOT" || scan_status=$?

if [[ "$scan_status" -eq 1 && -f "$ROOT/.env.live.example" ]]; then
  example_scan_status=0
  rg --quiet \
    --no-config \
    "${SECRET_PATTERNS[@]}" \
    "$ROOT/.env.live.example" || example_scan_status=$?
  case "$example_scan_status" in
    0)
      scan_status=0
      ;;
    1)
      ;;
    *)
      echo "Value-free live-input example scan could not complete (rg exit $example_scan_status)." >&2
      exit "$example_scan_status"
      ;;
  esac
fi

case "$scan_status" in
  0)
    echo "Potential secret material found." >&2
    exit 1
    ;;
  1)
    ;;
  *)
    echo "Repository secret scan could not complete (rg exit $scan_status)." >&2
    exit "$scan_status"
    ;;
esac

echo "Secret scan passed."
