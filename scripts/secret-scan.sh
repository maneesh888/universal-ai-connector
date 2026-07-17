#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if rg -n \
  --hidden \
  --glob '!.git/**' \
  --glob '!**/build/**' \
  --glob '!swift-package/Artifacts/**' \
  --glob '!gradle/wrapper/gradle-wrapper.jar' \
  -e 'sk-[A-Za-z0-9_-]{20,}' \
  -e 'Authorization:[[:space:]]*Bearer[[:space:]]+[A-Za-z0-9._-]+' \
  -e 'api[_-]?key[[:space:]]*=[[:space:]]*"[^"]+"' \
  -e "api[_-]?key[[:space:]]*=[[:space:]]*'[^']+'" \
  "$ROOT"; then
  echo "Potential secret material found." >&2
  exit 1
fi

echo "Secret scan passed."
