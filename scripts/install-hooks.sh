#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
EXPECTED_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOOKS_PATH="$(git -C "$ROOT" config --local --get core.hooksPath || true)"

if [[ "$ROOT" != "$EXPECTED_ROOT" ]]; then
  echo "Run this script from the Universal AI Connector repository." >&2
  exit 1
fi

if [[ -n "$HOOKS_PATH" && "$HOOKS_PATH" != ".githooks" ]]; then
  echo "A different core.hooksPath is already configured: $HOOKS_PATH" >&2
  echo "Remove or migrate that configuration before installing these hooks." >&2
  exit 1
fi

chmod +x "$ROOT/.githooks/pre-commit"
git -C "$ROOT" config --local core.hooksPath .githooks

echo "Universal AI Connector Git hooks enabled from .githooks/."
