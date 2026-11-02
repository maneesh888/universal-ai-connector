#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "$#" -gt 1 ]]; then
  echo "Usage: $0 [kotlin|swift|all]" >&2
  exit 2
fi
case "${1:-kotlin}" in
  kotlin) exec "$ROOT/gradlew" :bridge:checkKotlinAbi ;;
  swift) exec "$ROOT/scripts/check-apple-distribution.sh" build ;;
  all)
    "$ROOT/gradlew" :bridge:checkKotlinAbi
    exec "$ROOT/scripts/check-apple-distribution.sh" build
    ;;
  *) echo "Usage: $0 [kotlin|swift|all]" >&2; exit 2 ;;
esac
