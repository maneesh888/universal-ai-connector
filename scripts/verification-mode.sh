#!/usr/bin/env bash
set -euo pipefail
# Print a class only. A missing trusted policy selects full; a broken policy fails.
ROOT="$(git rev-parse --show-toplevel)"
STAGE="${1:-}"
BASE_REF="${2:-origin/main}"
case "$STAGE" in
  staged) CANDIDATE="$(git write-tree)" ;;
  head) CANDIDATE="$(git rev-parse HEAD)" ;;
  *) echo "Usage: verification-mode.sh staged|head [trusted-base-ref]" >&2; exit 2 ;;
esac
if ! BASE="$(git merge-base "$BASE_REF" HEAD 2>/dev/null)"; then
  echo "Trusted base unavailable; retaining full verification." >&2
  echo full
  exit 0
fi
if ! git cat-file -e "$BASE:scripts/verification-policy.py" 2>/dev/null; then
  echo "Trusted classifier unavailable; retaining full verification." >&2
  echo full
  exit 0
fi
POLICY="$(mktemp)"
trap 'rm -f "$POLICY"' EXIT
git show "$BASE:scripts/verification-policy.py" > "$POLICY"
CLASS="$(python3 -I "$POLICY" classify "$ROOT" "$BASE" "$CANDIDATE")"
case "$CLASS" in
  docs|full) echo "$CLASS" ;;
  *) echo "Trusted classifier returned an unknown class." >&2; exit 1 ;;
esac
