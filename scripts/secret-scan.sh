#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v rg >/dev/null 2>&1; then
  echo "ripgrep (rg) is required for the repository secret scan." >&2
  exit 2
fi

scan_status=0
rg --quiet \
  --no-config \
  --no-ignore \
  --hidden \
  --glob '!.git/**' \
  --glob '!**/build/**' \
  --glob '!swift-package/Artifacts/**' \
  --glob '!gradle/wrapper/gradle-wrapper.jar' \
  -e 'sk-[A-Za-z0-9_-]{20,}' \
  -e 'Authorization:[[:space:]]*Bearer[[:space:]]+[A-Za-z0-9._-]+' \
  -e 'api[_-]?key[[:space:]]*=[[:space:]]*"[^"]+"' \
  -e "api[_-]?key[[:space:]]*=[[:space:]]*'[^']+'" \
  "$ROOT" || scan_status=$?

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
