#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v python3 >/dev/null 2>&1; then
  echo "Android development bootstrap requires Python 3." >&2
  exit 1
fi
exec python3 -B "$ROOT/scripts/android-live-seed.py" "$@"
