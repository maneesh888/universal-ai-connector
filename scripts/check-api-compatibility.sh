#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${1:-kotlin}" != "kotlin" || "$#" -gt 1 ]]; then
  echo "Usage: $0 [kotlin]; Swift compatibility joins its Apple distribution package." >&2
  exit 2
fi
exec "$ROOT/gradlew" :bridge:checkKotlinAbi
