#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIRECTORY="$(mktemp -d)"
TEST_REPOSITORY="$TEST_DIRECTORY/repository"
SCANNER_UNDER_TEST="$TEST_REPOSITORY/scripts/secret-scan.sh"

cleanup() {
  rm -rf "$TEST_DIRECTORY"
}
trap cleanup EXIT

mkdir -p "$TEST_REPOSITORY/scripts"
cp "$ROOT/scripts/secret-scan.sh" "$SCANNER_UNDER_TEST"
chmod +x "$SCANNER_UNDER_TEST"

DETECTION_OUTPUT="$TEST_DIRECTORY/detection.log"
PROBE_FILE="$TEST_REPOSITORY/synthetic-secret.txt"
printf '%s%s\n' 'sk-' 'AAAAAAAAAAAAAAAAAAAAAAAA' > "$PROBE_FILE"

detection_status=0
"$SCANNER_UNDER_TEST" > "$DETECTION_OUTPUT" 2>&1 || detection_status=$?
if [[ "$detection_status" -ne 1 ]]; then
  echo "Expected the secret scan to reject the synthetic secret probe." >&2
  exit 1
fi
if ! grep -Fq "Potential secret material found." "$DETECTION_OUTPUT"; then
  echo "Secret scan did not report the synthetic secret probe." >&2
  exit 1
fi
rm -f "$PROBE_FILE"

FAKE_PATH="$TEST_DIRECTORY/path"
MISSING_TOOL_OUTPUT="$TEST_DIRECTORY/missing-tool.log"
mkdir -p "$FAKE_PATH"
ln -s "$(command -v bash)" "$FAKE_PATH/bash"
ln -s "$(command -v dirname)" "$FAKE_PATH/dirname"

missing_tool_status=0
env PATH="$FAKE_PATH" "$SCANNER_UNDER_TEST" > "$MISSING_TOOL_OUTPUT" 2>&1 || missing_tool_status=$?
if [[ "$missing_tool_status" -eq 0 ]]; then
  echo "Expected the secret scan to fail when ripgrep is unavailable." >&2
  exit 1
fi
if ! grep -Fq "ripgrep (rg) is required for the repository secret scan." "$MISSING_TOOL_OUTPUT"; then
  echo "Secret scan did not explain the missing ripgrep dependency." >&2
  exit 1
fi

OPERATIONAL_ERROR_OUTPUT="$TEST_DIRECTORY/operational-error.log"
printf '%s\n' '#!/usr/bin/env bash' 'exit 7' > "$FAKE_PATH/rg"
chmod +x "$FAKE_PATH/rg"

operational_error_status=0
env PATH="$FAKE_PATH" "$SCANNER_UNDER_TEST" > "$OPERATIONAL_ERROR_OUTPUT" 2>&1 || operational_error_status=$?
if [[ "$operational_error_status" -ne 7 ]]; then
  echo "Expected the secret scan to preserve an operational ripgrep error." >&2
  exit 1
fi
if ! grep -Fq "Repository secret scan could not complete (rg exit 7)." "$OPERATIONAL_ERROR_OUTPUT"; then
  echo "Secret scan did not report the operational ripgrep error." >&2
  exit 1
fi

echo "Secret scan regression tests passed."
