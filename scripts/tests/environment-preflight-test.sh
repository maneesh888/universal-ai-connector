#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIRECTORY="$(mktemp -d)"
FAKE_PATH="$TEST_DIRECTORY/path"
PREFLIGHT_OUTPUT="$TEST_DIRECTORY/preflight.log"

cleanup() {
  rm -rf "$TEST_DIRECTORY"
}
trap cleanup EXIT

mkdir -p "$FAKE_PATH"
ln -s /usr/bin/env "$FAKE_PATH/env"
ln -s "$(command -v bash)" "$FAKE_PATH/bash"
ln -s "$(command -v dirname)" "$FAKE_PATH/dirname"
ln -s "$(command -v git)" "$FAKE_PATH/git"
ln -s "$(command -v rg)" "$FAKE_PATH/rg"

PATH="$FAKE_PATH" /bin/bash "$ROOT/scripts/check-environment.sh" --hygiene \
  > "$PREFLIGHT_OUTPUT" 2>&1
if ! grep -Fq "Contributor environment preflight passed for --hygiene." "$PREFLIGHT_OUTPUT"; then
  echo "Contributor environment preflight did not accept standard env behavior." >&2
  exit 1
fi

rm -f "$FAKE_PATH/env"
printf '%s\n' '#!/bin/sh' 'exit 0' > "$FAKE_PATH/env"
chmod +x "$FAKE_PATH/env"

shadow_status=0
PATH="$FAKE_PATH" /bin/bash "$ROOT/scripts/check-environment.sh" --hygiene \
  > "$PREFLIGHT_OUTPUT" 2>&1 || shadow_status=$?
if [[ "$shadow_status" -ne 1 ]]; then
  echo "Contributor environment preflight did not reject a shadowed env command." >&2
  exit 1
fi
if ! grep -Fq "Contributor environment has a non-standard 'env' command:" "$PREFLIGHT_OUTPUT"; then
  echo "Contributor environment preflight did not explain the shadowed env command." >&2
  exit 1
fi

echo "Contributor environment preflight regression tests passed."
