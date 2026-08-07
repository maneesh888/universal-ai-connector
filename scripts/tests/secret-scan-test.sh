#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIRECTORY="$(mktemp -d)"
TEST_REPOSITORY="$TEST_DIRECTORY/repository"
SCANNER_UNDER_TEST="$TEST_REPOSITORY/scripts/secret-scan.sh"
LIVE_INPUT_EXAMPLE="$ROOT/.env.live.example"

cleanup() {
  rm -rf "$TEST_DIRECTORY"
}
trap cleanup EXIT

for documented_input in \
  OPENAI_API_KEY \
  OPENAI_LIVE_MODEL \
  ANTHROPIC_API_KEY \
  ANTHROPIC_LIVE_MODEL \
  OPENROUTER_API_KEY \
  OPENROUTER_LIVE_MODEL; do
  if ! grep -Fxq "$documented_input=" "$LIVE_INPUT_EXAMPLE"; then
    echo "Value-free live-input example omitted or populated $documented_input." >&2
    exit 1
  fi
done

mkdir -p "$TEST_REPOSITORY/scripts"
cp "$ROOT/scripts/secret-scan.sh" "$SCANNER_UNDER_TEST"
chmod +x "$SCANNER_UNDER_TEST"

DETECTION_OUTPUT="$TEST_DIRECTORY/detection.log"
PROBE_FILE="$TEST_REPOSITORY/synthetic-secret.txt"
SYNTHETIC_SECRET="$(printf '%s%s' 'sk-' 'AAAAAAAAAAAAAAAAAAAAAAAA')"
printf '%s\n' "$SYNTHETIC_SECRET" > "$PROBE_FILE"

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
if grep -Fq "$SYNTHETIC_SECRET" "$DETECTION_OUTPUT"; then
  echo "Secret scan exposed matched secret material in its output." >&2
  exit 1
fi
rm -f "$PROBE_FILE"

IGNORED_DETECTION_OUTPUT="$TEST_DIRECTORY/ignored-detection.log"
IGNORED_PROBE_FILE="$TEST_REPOSITORY/ignored-secret.txt"
printf '%s\n' 'ignored-secret.txt' > "$TEST_REPOSITORY/.ignore"
printf '%s\n' "$SYNTHETIC_SECRET" > "$IGNORED_PROBE_FILE"

ignored_detection_status=0
"$SCANNER_UNDER_TEST" > "$IGNORED_DETECTION_OUTPUT" 2>&1 || ignored_detection_status=$?
if [[ "$ignored_detection_status" -ne 1 ]]; then
  echo "Expected the secret scan to reject a probe hidden by .ignore." >&2
  exit 1
fi
if ! grep -Fq "Potential secret material found." "$IGNORED_DETECTION_OUTPUT"; then
  echo "Secret scan did not report the probe hidden by .ignore." >&2
  exit 1
fi
if grep -Fq "$SYNTHETIC_SECRET" "$IGNORED_DETECTION_OUTPUT"; then
  echo "Secret scan exposed ignored matched material in its output." >&2
  exit 1
fi
rm -f "$IGNORED_PROBE_FILE" "$TEST_REPOSITORY/.ignore"

ANTHROPIC_DETECTION_OUTPUT="$TEST_DIRECTORY/anthropic-detection.log"
ANTHROPIC_PROBE_FILE="$TEST_REPOSITORY/anthropic-config.txt"
SYNTHETIC_ANTHROPIC_SECRET="$(
  printf '%s%s' 'sk-ant-api03-' 'BBBBBBBBBBBBBBBBBBBBBBBB'
)"
printf 'ANTHROPIC_API_KEY="%s"\n' "$SYNTHETIC_ANTHROPIC_SECRET" > "$ANTHROPIC_PROBE_FILE"

anthropic_detection_status=0
"$SCANNER_UNDER_TEST" > "$ANTHROPIC_DETECTION_OUTPUT" 2>&1 ||
  anthropic_detection_status=$?
if [[ "$anthropic_detection_status" -ne 1 ]]; then
  echo "Expected the secret scan to reject the synthetic Anthropic input." >&2
  exit 1
fi
if ! grep -Fq "Potential secret material found." "$ANTHROPIC_DETECTION_OUTPUT"; then
  echo "Secret scan did not recognize the documented Anthropic credential input." >&2
  exit 1
fi
if grep -Fq "$SYNTHETIC_ANTHROPIC_SECRET" "$ANTHROPIC_DETECTION_OUTPUT"; then
  echo "Secret scan exposed matched Anthropic credential material." >&2
  exit 1
fi
rm -f "$ANTHROPIC_PROBE_FILE"

OPENROUTER_DETECTION_OUTPUT="$TEST_DIRECTORY/openrouter-detection.log"
OPENROUTER_PROBE_FILE="$TEST_REPOSITORY/openrouter-config.txt"
SYNTHETIC_OPENROUTER_SECRET="$(
  printf '%s%s' 'sk-or-v1-' 'CCCCCCCCCCCCCCCCCCCCCCCC'
)"
printf 'OPENROUTER_API_KEY="%s"\n' "$SYNTHETIC_OPENROUTER_SECRET" > \
  "$OPENROUTER_PROBE_FILE"

openrouter_detection_status=0
"$SCANNER_UNDER_TEST" > "$OPENROUTER_DETECTION_OUTPUT" 2>&1 ||
  openrouter_detection_status=$?
if [[ "$openrouter_detection_status" -ne 1 ]]; then
  echo "Expected the secret scan to reject the synthetic OpenRouter input." >&2
  exit 1
fi
if ! grep -Fq "Potential secret material found." "$OPENROUTER_DETECTION_OUTPUT"; then
  echo "Secret scan did not recognize the documented OpenRouter credential input." >&2
  exit 1
fi
if grep -Fq "$SYNTHETIC_OPENROUTER_SECRET" "$OPENROUTER_DETECTION_OUTPUT"; then
  echo "Secret scan exposed matched OpenRouter credential material." >&2
  exit 1
fi
rm -f "$OPENROUTER_PROBE_FILE"

LOCAL_LIVE_OUTPUT="$TEST_DIRECTORY/local-live.log"
LOCAL_LIVE_FILE="$TEST_REPOSITORY/.env.live"
printf '%s\n' "$SYNTHETIC_SECRET" > "$LOCAL_LIVE_FILE"

"$SCANNER_UNDER_TEST" > "$LOCAL_LIVE_OUTPUT" 2>&1
if grep -Fq "$SYNTHETIC_SECRET" "$LOCAL_LIVE_OUTPUT"; then
  echo "Secret scan exposed approved local live-input material." >&2
  exit 1
fi

git -C "$TEST_REPOSITORY" init --quiet
CONFIG_DETECTION_OUTPUT="$TEST_DIRECTORY/config-detection.log"
CONFIG_PROBE_FILE="$TEST_REPOSITORY/config-secret.txt"
RIPGREP_CONFIG="$TEST_DIRECTORY/ripgrep.conf"
printf '%s\n' '--glob=!config-secret.txt' > "$RIPGREP_CONFIG"
printf '%s\n' "$SYNTHETIC_SECRET" > "$CONFIG_PROBE_FILE"

config_detection_status=0
env RIPGREP_CONFIG_PATH="$RIPGREP_CONFIG" \
  "$SCANNER_UNDER_TEST" > "$CONFIG_DETECTION_OUTPUT" 2>&1 || config_detection_status=$?
if [[ "$config_detection_status" -ne 1 ]]; then
  echo "Expected the secret scan to disregard ripgrep configuration exclusions." >&2
  exit 1
fi
if ! grep -Fq "Potential secret material found." "$CONFIG_DETECTION_OUTPUT"; then
  echo "Secret scan did not report the probe hidden by ripgrep configuration." >&2
  exit 1
fi
if grep -Fq "$SYNTHETIC_SECRET" "$CONFIG_DETECTION_OUTPUT"; then
  echo "Secret scan exposed configured matched material in its output." >&2
  exit 1
fi
rm -f "$CONFIG_PROBE_FILE"

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

git -C "$TEST_REPOSITORY" add --force .env.live
tracked_live_status=0
"$SCANNER_UNDER_TEST" > "$LOCAL_LIVE_OUTPUT" 2>&1 || tracked_live_status=$?
if [[ "$tracked_live_status" -ne 1 ]]; then
  echo "Expected the secret scan to reject a tracked local live-input file." >&2
  exit 1
fi
if ! grep -Fq \
  "A local live-input file is tracked and must be removed from the Git index." \
  "$LOCAL_LIVE_OUTPUT"; then
  echo "Secret scan did not report the tracked local live-input file." >&2
  exit 1
fi
if grep -Fq "$SYNTHETIC_SECRET" "$LOCAL_LIVE_OUTPUT"; then
  echo "Secret scan exposed tracked local live-input material." >&2
  exit 1
fi

git -C "$TEST_REPOSITORY" rm --cached --quiet .env.live
NESTED_LIVE_FILE="$TEST_REPOSITORY/nested/.env.live.example"
mkdir -p "$(dirname "$NESTED_LIVE_FILE")"
printf '%s\n' "$SYNTHETIC_SECRET" > "$NESTED_LIVE_FILE"
git -C "$TEST_REPOSITORY" add --force nested/.env.live.example

nested_live_status=0
"$SCANNER_UNDER_TEST" > "$LOCAL_LIVE_OUTPUT" 2>&1 || nested_live_status=$?
if [[ "$nested_live_status" -ne 1 ]]; then
  echo "Expected the secret scan to reject a nested tracked live-input file." >&2
  exit 1
fi
if ! grep -Fq \
  "A local live-input file is tracked and must be removed from the Git index." \
  "$LOCAL_LIVE_OUTPUT"; then
  echo "Secret scan did not report the nested tracked live-input file." >&2
  exit 1
fi
if grep -Fq "$SYNTHETIC_SECRET" "$LOCAL_LIVE_OUTPUT"; then
  echo "Secret scan exposed nested tracked live-input material." >&2
  exit 1
fi

echo "Secret scan regression tests passed."
