#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT/gradlew" :bridge:iosSimulatorArm64Test
"$ROOT/scripts/test-swift-package.sh"
"$ROOT/scripts/build-sample.sh"
"$ROOT/scripts/secret-scan.sh"
git -C "$ROOT" diff --check

TEMP_INDEX_DIRECTORY="$(mktemp -d)"
trap 'rm -rf "$TEMP_INDEX_DIRECTORY"' EXIT
TEMP_INDEX="$TEMP_INDEX_DIRECTORY/index"

GIT_INDEX_FILE="$TEMP_INDEX" git -C "$ROOT" read-tree --empty
GIT_INDEX_FILE="$TEMP_INDEX" git -C "$ROOT" add --intent-to-add .
GIT_INDEX_FILE="$TEMP_INDEX" git -C "$ROOT" diff --check

echo "Universal AI Connector POC checks passed."
