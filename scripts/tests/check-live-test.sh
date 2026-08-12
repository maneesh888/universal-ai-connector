#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
unset \
  GIT_INDEX_FILE \
  GIT_OBJECT_DIRECTORY \
  GIT_ALTERNATE_OBJECT_DIRECTORIES \
  GIT_WORK_TREE \
  GIT_DIR
unset \
  OPENAI_API_KEY \
  OPENAI_LIVE_MODEL \
  ANTHROPIC_API_KEY \
  ANTHROPIC_LIVE_MODEL \
  OPENROUTER_API_KEY \
  OPENROUTER_LIVE_MODEL \
  GATEWAY_LIVE_BASE_URL \
  GATEWAY_API_KEY \
  GATEWAY_LIVE_MODEL \
  GATEWAY_LIVE_STRUCTURED_OUTPUT \
  UAC_LIVE_ENV_FILE \
  UAC_LIVE_EXPECTED_SHA
TEST_DIRECTORY="$(mktemp -d)"
TEST_REPOSITORY="$TEST_DIRECTORY/repository"
POISON_REPOSITORY="$TEST_DIRECTORY/foreign-repository"
RUNNER="$TEST_REPOSITORY/scripts/check-live.sh"
LOCAL_CONFIG_HELPER="$TEST_REPOSITORY/scripts/local-config.sh"
CALL_LOG="$TEST_DIRECTORY/calls.log"
OUTPUT="$TEST_DIRECTORY/output.log"
SYNTHETIC_KEY="test-key-material-that-must-not-appear"
MODEL="test-model-2026-08-02"
ANTHROPIC_SYNTHETIC_KEY="test-anthropic-material-that-must-not-appear"
ANTHROPIC_MODEL="test-anthropic-model-2026-08-07"
OPENROUTER_SYNTHETIC_KEY="test-openrouter-material-that-must-not-appear"
OPENROUTER_MODEL="test-openrouter-model-2026-08-07"
GATEWAY_BASE_URL="http://127.0.0.1:8880/v1"
GATEWAY_SYNTHETIC_KEY="test-gateway-material-that-must-not-appear"
GATEWAY_MODEL="test-gateway-model-2026-08-11"
GATEWAY_STRUCTURED_OUTPUT="false"

cleanup() {
  rm -rf "$TEST_DIRECTORY"
}
trap cleanup EXIT

mkdir -p "$TEST_REPOSITORY/scripts"
cp "$ROOT/scripts/check-live.sh" "$RUNNER"
cp "$ROOT/scripts/local-config.sh" "$LOCAL_CONFIG_HELPER"
chmod +x "$RUNNER" "$LOCAL_CONFIG_HELPER"
printf '%s\n' \
  '.env.live' \
  '.env.live.*' \
  '!.env.live.example' > "$TEST_REPOSITORY/.gitignore"
cp "$ROOT/.env.live.example" "$TEST_REPOSITORY/.env.live.example"
TEST_REPOSITORY_PHYSICAL="$(cd "$TEST_REPOSITORY" && pwd -P)"

cat > "$TEST_REPOSITORY/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$*" == *":bridge:jvmTest"* ]]; then
  if [[ -n "${OPENAI_API_KEY:-}" ||
        -n "${OPENAI_LIVE_MODEL:-}" ||
        -n "${ANTHROPIC_API_KEY:-}" ||
        -n "${ANTHROPIC_LIVE_MODEL:-}" ||
        -n "${OPENROUTER_API_KEY:-}" ||
        -n "${OPENROUTER_LIVE_MODEL:-}" ||
        -n "${GATEWAY_LIVE_BASE_URL:-}" ||
        -n "${GATEWAY_API_KEY:-}" ||
        -n "${GATEWAY_LIVE_MODEL:-}" ||
        -n "${GATEWAY_LIVE_STRUCTURED_OUTPUT:-}" ||
        -n "${UAC_LIVE_ENV_FILE:-}" ||
        -n "${UAC_LIVE_EXPECTED_SHA:-}" ]]; then
    echo "Deterministic tests received live environment values." >&2
    exit 9
  fi
  echo "deterministic" >> "$UAC_TEST_CALL_LOG"
  if [[ -n "${UAC_TEST_DIRTY_AFTER_DETERMINISTIC:-}" ]]; then
    printf '%s\n' "deterministic mutation" > "$UAC_TEST_DIRTY_AFTER_DETERMINISTIC"
  fi
  exit 0
fi

if [[ "$*" == *":bridge:openAiLiveTest"* ]]; then
  if [[ "${OPENAI_API_KEY:-}" != "$UAC_TEST_EXPECTED_KEY" ||
        "${OPENAI_LIVE_MODEL:-}" != "$UAC_TEST_EXPECTED_MODEL" ||
        -n "${ANTHROPIC_API_KEY:-}" ||
        -n "${ANTHROPIC_LIVE_MODEL:-}" ||
        -n "${OPENROUTER_API_KEY:-}" ||
        -n "${OPENROUTER_LIVE_MODEL:-}" ||
        -n "${GATEWAY_LIVE_BASE_URL:-}" ||
        -n "${GATEWAY_API_KEY:-}" ||
        -n "${GATEWAY_LIVE_MODEL:-}" ||
        -n "${GATEWAY_LIVE_STRUCTURED_OUTPUT:-}" ||
        "${UAC_LIVE_EXPECTED_SHA:-}" != "$UAC_TEST_EXPECTED_SHA" ]]; then
    echo "Live task did not receive its exact expected environment." >&2
    exit 10
  fi
  if [[ "$*" != *"-PuacLiveExpectedSha=$UAC_TEST_EXPECTED_SHA"* ||
        "$*" != *"--no-daemon"* ||
        "$*" != *"--no-configuration-cache"* ]]; then
    echo "Live task did not receive its exact-head arguments." >&2
    exit 11
  fi
  echo "live" >> "$UAC_TEST_CALL_LOG"
  if [[ -n "${UAC_TEST_DIRTY_AFTER_LIVE:-}" ]]; then
    printf '%s\n' "live mutation" > "$UAC_TEST_DIRTY_AFTER_LIVE"
  fi
  exit 0
fi

if [[ "$*" == *":bridge:anthropicLiveTest"* ]]; then
  if [[ "${ANTHROPIC_API_KEY:-}" != "$UAC_TEST_EXPECTED_KEY" ||
        "${ANTHROPIC_LIVE_MODEL:-}" != "$UAC_TEST_EXPECTED_MODEL" ||
        -n "${OPENAI_API_KEY:-}" ||
        -n "${OPENAI_LIVE_MODEL:-}" ||
        -n "${OPENROUTER_API_KEY:-}" ||
        -n "${OPENROUTER_LIVE_MODEL:-}" ||
        -n "${GATEWAY_LIVE_BASE_URL:-}" ||
        -n "${GATEWAY_API_KEY:-}" ||
        -n "${GATEWAY_LIVE_MODEL:-}" ||
        -n "${GATEWAY_LIVE_STRUCTURED_OUTPUT:-}" ||
        "${UAC_LIVE_EXPECTED_SHA:-}" != "$UAC_TEST_EXPECTED_SHA" ]]; then
    echo "Anthropic live task did not receive its exact expected environment." >&2
    exit 13
  fi
  if [[ "$*" != *"-PuacLiveExpectedSha=$UAC_TEST_EXPECTED_SHA"* ||
        "$*" != *"--no-daemon"* ||
        "$*" != *"--no-configuration-cache"* ]]; then
    echo "Anthropic live task did not receive its exact-head arguments." >&2
    exit 14
  fi
  echo "live" >> "$UAC_TEST_CALL_LOG"
  if [[ -n "${UAC_TEST_DIRTY_AFTER_LIVE:-}" ]]; then
    printf '%s\n' "live mutation" > "$UAC_TEST_DIRTY_AFTER_LIVE"
  fi
  exit 0
fi

if [[ "$*" == *":bridge:openRouterLiveTest"* ]]; then
  if [[ "${OPENROUTER_API_KEY:-}" != "$UAC_TEST_EXPECTED_KEY" ||
        "${OPENROUTER_LIVE_MODEL:-}" != "$UAC_TEST_EXPECTED_MODEL" ||
        -n "${OPENAI_API_KEY:-}" ||
        -n "${OPENAI_LIVE_MODEL:-}" ||
        -n "${ANTHROPIC_API_KEY:-}" ||
        -n "${ANTHROPIC_LIVE_MODEL:-}" ||
        -n "${GATEWAY_LIVE_BASE_URL:-}" ||
        -n "${GATEWAY_API_KEY:-}" ||
        -n "${GATEWAY_LIVE_MODEL:-}" ||
        -n "${GATEWAY_LIVE_STRUCTURED_OUTPUT:-}" ||
        "${UAC_LIVE_EXPECTED_SHA:-}" != "$UAC_TEST_EXPECTED_SHA" ]]; then
    echo "Live task did not receive its exact expected environment." >&2
    exit 10
  fi
  if [[ "$*" != *"-PuacLiveExpectedSha=$UAC_TEST_EXPECTED_SHA"* ||
        "$*" != *"--no-daemon"* ||
        "$*" != *"--no-configuration-cache"* ]]; then
    echo "Live task did not receive its exact-head arguments." >&2
    exit 11
  fi
  echo "live" >> "$UAC_TEST_CALL_LOG"
  if [[ -n "${UAC_TEST_DIRTY_AFTER_LIVE:-}" ]]; then
    printf '%s\n' "live mutation" > "$UAC_TEST_DIRTY_AFTER_LIVE"
  fi
  exit 0
fi

if [[ "$*" == *":bridge:gatewayLiveTest"* ]]; then
  if [[ "${GATEWAY_LIVE_BASE_URL:-}" != "$UAC_TEST_EXPECTED_BASE_URL" ||
        "${GATEWAY_API_KEY:-}" != "$UAC_TEST_EXPECTED_KEY" ||
        "${GATEWAY_LIVE_MODEL:-}" != "$UAC_TEST_EXPECTED_MODEL" ||
        "${GATEWAY_LIVE_STRUCTURED_OUTPUT:-}" != "$UAC_TEST_EXPECTED_STRUCTURED_OUTPUT" ||
        -n "${OPENAI_API_KEY:-}" ||
        -n "${OPENAI_LIVE_MODEL:-}" ||
        -n "${ANTHROPIC_API_KEY:-}" ||
        -n "${ANTHROPIC_LIVE_MODEL:-}" ||
        -n "${OPENROUTER_API_KEY:-}" ||
        -n "${OPENROUTER_LIVE_MODEL:-}" ||
        "${UAC_LIVE_EXPECTED_SHA:-}" != "$UAC_TEST_EXPECTED_SHA" ]]; then
    echo "Gateway live task did not receive its exact expected environment." >&2
    exit 15
  fi
  if [[ "$*" != *"-PuacLiveExpectedSha=$UAC_TEST_EXPECTED_SHA"* ||
        "$*" != *"--no-daemon"* ||
        "$*" != *"--no-configuration-cache"* ]]; then
    echo "Gateway live task did not receive its exact-head arguments." >&2
    exit 16
  fi
  echo "live" >> "$UAC_TEST_CALL_LOG"
  if [[ -n "${UAC_TEST_DIRTY_AFTER_LIVE:-}" ]]; then
    printf '%s\n' "live mutation" > "$UAC_TEST_DIRTY_AFTER_LIVE"
  fi
  exit 0
fi

echo "Unexpected Gradle invocation." >&2
exit 12
EOF
chmod +x "$TEST_REPOSITORY/gradlew"

git -C "$TEST_REPOSITORY" init -q
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Runner Test" \
  -c user.email="live-runner@example.invalid" \
  commit -qm "test fixture"
HEAD_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

mkdir -p "$POISON_REPOSITORY"
git -C "$POISON_REPOSITORY" init -q
printf '%s\n' foreign > "$POISON_REPOSITORY/foreign.txt"
git -C "$POISON_REPOSITORY" add foreign.txt
git -C "$POISON_REPOSITORY" \
  -c user.name="Live Runner Test" \
  -c user.email="live-runner@example.invalid" \
  commit -qm "foreign fixture"

expect_failure() {
  local expected_message="$1"
  shift
  : > "$OUTPUT"
  if "$@" > "$OUTPUT" 2>&1; then
    echo "Expected live runner failure: $expected_message" >&2
    exit 1
  fi
  if ! grep -Fq "$expected_message" "$OUTPUT"; then
    echo "Live runner did not report: $expected_message" >&2
    exit 1
  fi
  if grep -Fq "$SYNTHETIC_KEY" "$OUTPUT" ||
    grep -Fq "$ANTHROPIC_SYNTHETIC_KEY" "$OUTPUT" ||
    grep -Fq "$OPENROUTER_SYNTHETIC_KEY" "$OUTPUT" ||
    grep -Fq "$GATEWAY_SYNTHETIC_KEY" "$OUTPUT"; then
    echo "Live runner exposed credential material." >&2
    exit 1
  fi
}

expect_failure \
  "Usage: ./scripts/check-live.sh <provider>" \
  "$RUNNER" unsupported

expect_failure \
  "OPENAI_API_KEY is required" \
  "$RUNNER" openai
if ! grep -Fq "Copy $TEST_REPOSITORY_PHYSICAL/.env.live.example to that exact path." "$OUTPUT" ||
  ! grep -Fq "Expected file: $TEST_REPOSITORY_PHYSICAL/.env.live" "$OUTPUT" ||
  ! grep -Fq "chmod 600 \"$TEST_REPOSITORY_PHYSICAL/.env.live\"" "$OUTPUT" ||
  ! grep -Fq "never displays their values" "$OUTPUT"; then
  echo "Missing-key failure omitted safe local configuration guidance." >&2
  exit 1
fi

expect_failure \
  "OPENAI_LIVE_MODEL is required" \
  env OPENAI_API_KEY="$SYNTHETIC_KEY" "$RUNNER" openai
if ! grep -Fq "Expected file: $TEST_REPOSITORY_PHYSICAL/.env.live" "$OUTPUT" ||
  ! grep -Fq "Non-empty process environment values take precedence" "$OUTPUT"; then
  echo "Missing-model failure omitted safe local configuration guidance." >&2
  exit 1
fi

expect_failure \
  "ANTHROPIC_API_KEY is required" \
  "$RUNNER" anthropic
if ! grep -Fq "ANTHROPIC_API_KEY and ANTHROPIC_LIVE_MODEL" "$OUTPUT" ||
  ! grep -Fq "./scripts/check-live.sh anthropic" "$OUTPUT"; then
  echo "Anthropic missing-key failure omitted provider-specific guidance." >&2
  exit 1
fi

expect_failure \
  "ANTHROPIC_LIVE_MODEL is required" \
  env ANTHROPIC_API_KEY="$ANTHROPIC_SYNTHETIC_KEY" "$RUNNER" anthropic

expect_failure \
  "OPENROUTER_API_KEY is required" \
  "$RUNNER" openrouter
if ! grep -Fq "OPENROUTER_API_KEY and OPENROUTER_LIVE_MODEL" "$OUTPUT" ||
  ! grep -Fq "./scripts/check-live.sh openrouter" "$OUTPUT"; then
  echo "Missing OpenRouter key failure omitted provider-specific guidance." >&2
  exit 1
fi

expect_failure \
  "OPENROUTER_LIVE_MODEL is required" \
  env OPENROUTER_API_KEY="$OPENROUTER_SYNTHETIC_KEY" "$RUNNER" openrouter

expect_failure \
  "OPENROUTER_LIVE_MODEL must be a bounded OpenRouter model slug." \
  env \
    OPENROUTER_API_KEY="$OPENROUTER_SYNTHETIC_KEY" \
    OPENROUTER_LIVE_MODEL="invalid model" \
    "$RUNNER" openrouter

expect_failure \
  "GATEWAY_LIVE_BASE_URL is required" \
  "$RUNNER" gateway
if ! grep -Fq "GATEWAY_LIVE_BASE_URL, GATEWAY_API_KEY, GATEWAY_LIVE_MODEL, and GATEWAY_LIVE_STRUCTURED_OUTPUT" "$OUTPUT" ||
  ! grep -Fq "./scripts/check-live.sh gateway" "$OUTPUT"; then
  echo "Missing Gateway base URL failure omitted Gateway-specific guidance." >&2
  exit 1
fi

expect_failure \
  "GATEWAY_API_KEY is required" \
  env GATEWAY_LIVE_BASE_URL="$GATEWAY_BASE_URL" "$RUNNER" gateway

expect_failure \
  "GATEWAY_LIVE_MODEL is required" \
  env \
    GATEWAY_LIVE_BASE_URL="$GATEWAY_BASE_URL" \
    GATEWAY_API_KEY="$GATEWAY_SYNTHETIC_KEY" \
    "$RUNNER" gateway

expect_failure \
  "GATEWAY_LIVE_BASE_URL must use HTTPS or loopback HTTP." \
  env \
    GATEWAY_LIVE_BASE_URL="http://gateway.example.invalid/v1" \
    GATEWAY_API_KEY="$GATEWAY_SYNTHETIC_KEY" \
    GATEWAY_LIVE_MODEL="$GATEWAY_MODEL" \
    "$RUNNER" gateway

expect_failure \
  "GATEWAY_LIVE_BASE_URL must end in /v1." \
  env \
    GATEWAY_LIVE_BASE_URL="https://gateway.example.invalid/api" \
    GATEWAY_API_KEY="$GATEWAY_SYNTHETIC_KEY" \
    GATEWAY_LIVE_MODEL="$GATEWAY_MODEL" \
    "$RUNNER" gateway

expect_failure \
  "GATEWAY_LIVE_STRUCTURED_OUTPUT is required" \
  env \
    GATEWAY_LIVE_BASE_URL="$GATEWAY_BASE_URL" \
    GATEWAY_API_KEY="$GATEWAY_SYNTHETIC_KEY" \
    GATEWAY_LIVE_MODEL="$GATEWAY_MODEL" \
    "$RUNNER" gateway

expect_failure \
  "GATEWAY_LIVE_STRUCTURED_OUTPUT must be true or false." \
  env \
    GATEWAY_LIVE_BASE_URL="$GATEWAY_BASE_URL" \
    GATEWAY_API_KEY="$GATEWAY_SYNTHETIC_KEY" \
    GATEWAY_LIVE_MODEL="$GATEWAY_MODEL" \
    GATEWAY_LIVE_STRUCTURED_OUTPUT="unknown" \
    "$RUNNER" gateway

expect_failure \
  "GATEWAY_LIVE_MODEL must be a bounded Gateway model identifier." \
  env \
    GATEWAY_LIVE_BASE_URL="$GATEWAY_BASE_URL" \
    GATEWAY_API_KEY="$GATEWAY_SYNTHETIC_KEY" \
    GATEWAY_LIVE_MODEL="invalid model" \
    GATEWAY_LIVE_STRUCTURED_OUTPUT="$GATEWAY_STRUCTURED_OUTPUT" \
    "$RUNNER" gateway

expect_failure \
  "Live verification HEAD does not match UAC_LIVE_EXPECTED_SHA." \
  env \
    OPENAI_API_KEY="$SYNTHETIC_KEY" \
    OPENAI_LIVE_MODEL="$MODEL" \
    UAC_LIVE_EXPECTED_SHA="0000000000000000000000000000000000000000" \
    "$RUNNER" openai

printf '%s\n' "dirty" > "$TEST_REPOSITORY/dirty.txt"
expect_failure \
  "Live verification requires a clean checkout" \
  env \
    OPENAI_API_KEY="$SYNTHETIC_KEY" \
    OPENAI_LIVE_MODEL="$MODEL" \
    "$RUNNER" openai
rm "$TEST_REPOSITORY/dirty.txt"

cp "$TEST_REPOSITORY/.git/index" "$TEST_DIRECTORY/index.backup"
printf '%s\n' "invalid index" > "$TEST_REPOSITORY/.git/index"
expect_failure \
  "Live verification could not inspect checkout state." \
  env \
    OPENAI_API_KEY="$SYNTHETIC_KEY" \
    OPENAI_LIVE_MODEL="$MODEL" \
    "$RUNNER" openai
cp "$TEST_DIRECTORY/index.backup" "$TEST_REPOSITORY/.git/index"

# The runner securely loads selected values from the primary checkout when process inputs are absent.
printf '%s\n' \
  "OPENAI_API_KEY='$SYNTHETIC_KEY'" \
  "OPENAI_LIVE_MODEL='$MODEL'" > "$TEST_REPOSITORY/.env.live"
chmod 600 "$TEST_REPOSITORY/.env.live"
: > "$CALL_LOG"
env \
  UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
  UAC_TEST_CALL_LOG="$CALL_LOG" \
  UAC_TEST_EXPECTED_KEY="$SYNTHETIC_KEY" \
  UAC_TEST_EXPECTED_MODEL="$MODEL" \
  UAC_TEST_EXPECTED_SHA="$HEAD_SHA" \
  "$RUNNER" openai > "$OUTPUT" 2>&1
if [[ "$(sed -n '1p' "$CALL_LOG")" != "deterministic" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live" ||
      -n "$(sed -n '3p' "$CALL_LOG")" ]]; then
  echo "Live runner did not use canonical primary-checkout configuration." >&2
  exit 1
fi
if grep -Fq "$SYNTHETIC_KEY" "$OUTPUT"; then
  echo "Canonical local configuration execution exposed credential material." >&2
  exit 1
fi
rm "$TEST_REPOSITORY/.env.live"

: > "$CALL_LOG"
POST_DETERMINISTIC_DIRTY_PATH="$TEST_REPOSITORY/post-deterministic-dirty.txt"
expect_failure \
  "Live verification requires a clean checkout" \
  env \
    OPENAI_API_KEY="$SYNTHETIC_KEY" \
    OPENAI_LIVE_MODEL="$MODEL" \
    ANTHROPIC_API_KEY="$ANTHROPIC_SYNTHETIC_KEY" \
    ANTHROPIC_LIVE_MODEL="$ANTHROPIC_MODEL" \
    OPENROUTER_API_KEY="$OPENROUTER_SYNTHETIC_KEY" \
    OPENROUTER_LIVE_MODEL="$OPENROUTER_MODEL" \
    UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
    UAC_TEST_CALL_LOG="$CALL_LOG" \
    UAC_TEST_DIRTY_AFTER_DETERMINISTIC="$POST_DETERMINISTIC_DIRTY_PATH" \
    "$RUNNER" openai
rm "$POST_DETERMINISTIC_DIRTY_PATH"
if [[ "$(sed -n '1p' "$CALL_LOG")" != "deterministic" ||
      -n "$(sed -n '2p' "$CALL_LOG")" ]]; then
  echo "Live runner continued after deterministic tests dirtied the checkout." >&2
  exit 1
fi

: > "$CALL_LOG"
POST_LIVE_DIRTY_PATH="$TEST_REPOSITORY/post-live-dirty.txt"
expect_failure \
  "Live verification requires a clean checkout" \
  env \
    OPENAI_API_KEY="$SYNTHETIC_KEY" \
    OPENAI_LIVE_MODEL="$MODEL" \
    ANTHROPIC_API_KEY="$ANTHROPIC_SYNTHETIC_KEY" \
    ANTHROPIC_LIVE_MODEL="$ANTHROPIC_MODEL" \
    OPENROUTER_API_KEY="$OPENROUTER_SYNTHETIC_KEY" \
    OPENROUTER_LIVE_MODEL="$OPENROUTER_MODEL" \
    UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
    UAC_TEST_CALL_LOG="$CALL_LOG" \
    UAC_TEST_DIRTY_AFTER_LIVE="$POST_LIVE_DIRTY_PATH" \
    UAC_TEST_EXPECTED_KEY="$SYNTHETIC_KEY" \
    UAC_TEST_EXPECTED_MODEL="$MODEL" \
    UAC_TEST_EXPECTED_SHA="$HEAD_SHA" \
    "$RUNNER" openai
rm "$POST_LIVE_DIRTY_PATH"
if [[ "$(sed -n '1p' "$CALL_LOG")" != "deterministic" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live" ||
      -n "$(sed -n '3p' "$CALL_LOG")" ]]; then
  echo "Live runner did not detect a checkout mutation after provider tests." >&2
  exit 1
fi

: > "$CALL_LOG"
env \
  OPENAI_API_KEY="$SYNTHETIC_KEY" \
  OPENAI_LIVE_MODEL="$MODEL" \
  ANTHROPIC_API_KEY="$ANTHROPIC_SYNTHETIC_KEY" \
  ANTHROPIC_LIVE_MODEL="$ANTHROPIC_MODEL" \
  OPENROUTER_API_KEY="$OPENROUTER_SYNTHETIC_KEY" \
  OPENROUTER_LIVE_MODEL="$OPENROUTER_MODEL" \
  GATEWAY_LIVE_BASE_URL="$GATEWAY_BASE_URL" \
  GATEWAY_API_KEY="$GATEWAY_SYNTHETIC_KEY" \
  GATEWAY_LIVE_MODEL="$GATEWAY_MODEL" \
  GATEWAY_LIVE_STRUCTURED_OUTPUT="$GATEWAY_STRUCTURED_OUTPUT" \
  UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
  UAC_TEST_CALL_LOG="$CALL_LOG" \
  UAC_TEST_EXPECTED_KEY="$SYNTHETIC_KEY" \
  UAC_TEST_EXPECTED_MODEL="$MODEL" \
  UAC_TEST_EXPECTED_SHA="$HEAD_SHA" \
  "$RUNNER" openai > "$OUTPUT" 2>&1

if [[ "$(sed -n '1p' "$CALL_LOG")" != "deterministic" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live" ||
      -n "$(sed -n '3p' "$CALL_LOG")" ]]; then
  echo "Live runner did not execute deterministic then live tasks exactly once." >&2
  exit 1
fi
if grep -Fq "$SYNTHETIC_KEY" "$OUTPUT"; then
  echo "Successful live runner output exposed credential material." >&2
  exit 1
fi
if grep -Fq "$ANTHROPIC_SYNTHETIC_KEY" "$OUTPUT"; then
  echo "Successful OpenAI runner output exposed non-selected provider material." >&2
  exit 1
fi
if grep -Fq "$OPENROUTER_SYNTHETIC_KEY" "$OUTPUT"; then
  echo "Successful OpenAI runner output exposed OpenRouter credential material." >&2
  exit 1
fi
if grep -Fq "$GATEWAY_SYNTHETIC_KEY" "$OUTPUT" || grep -Fq "$GATEWAY_BASE_URL" "$OUTPUT"; then
  echo "Successful OpenAI runner output exposed Gateway material." >&2
  exit 1
fi
if ! grep -Fq "head_sha=$HEAD_SHA" "$OUTPUT" ||
  ! grep -Fq "model=$MODEL" "$OUTPUT"; then
  echo "Successful live runner output omitted bounded evidence metadata." >&2
  exit 1
fi

# Ambient Git variables cannot redirect clean-state or exact-head evidence to another repository.
: > "$CALL_LOG"
env \
  GIT_DIR="$POISON_REPOSITORY/.git" \
  GIT_WORK_TREE="$POISON_REPOSITORY" \
  OPENAI_API_KEY="$SYNTHETIC_KEY" \
  OPENAI_LIVE_MODEL="$MODEL" \
  UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
  UAC_TEST_CALL_LOG="$CALL_LOG" \
  UAC_TEST_EXPECTED_KEY="$SYNTHETIC_KEY" \
  UAC_TEST_EXPECTED_MODEL="$MODEL" \
  UAC_TEST_EXPECTED_SHA="$HEAD_SHA" \
  "$RUNNER" openai > "$OUTPUT" 2>&1
if [[ "$(sed -n '1p' "$CALL_LOG")" != "deterministic" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live" ||
      -n "$(sed -n '3p' "$CALL_LOG")" ]] ||
  ! grep -Fq "head_sha=$HEAD_SHA" "$OUTPUT"; then
  echo "Ambient Git variables redirected live verification evidence." >&2
  exit 1
fi

: > "$CALL_LOG"
env \
  OPENAI_API_KEY="$SYNTHETIC_KEY" \
  OPENAI_LIVE_MODEL="$MODEL" \
  ANTHROPIC_API_KEY="$ANTHROPIC_SYNTHETIC_KEY" \
  ANTHROPIC_LIVE_MODEL="$ANTHROPIC_MODEL" \
  OPENROUTER_API_KEY="$OPENROUTER_SYNTHETIC_KEY" \
  OPENROUTER_LIVE_MODEL="$OPENROUTER_MODEL" \
  GATEWAY_LIVE_BASE_URL="$GATEWAY_BASE_URL" \
  GATEWAY_API_KEY="$GATEWAY_SYNTHETIC_KEY" \
  GATEWAY_LIVE_MODEL="$GATEWAY_MODEL" \
  GATEWAY_LIVE_STRUCTURED_OUTPUT="$GATEWAY_STRUCTURED_OUTPUT" \
  UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
  UAC_TEST_CALL_LOG="$CALL_LOG" \
  UAC_TEST_EXPECTED_KEY="$ANTHROPIC_SYNTHETIC_KEY" \
  UAC_TEST_EXPECTED_MODEL="$ANTHROPIC_MODEL" \
  UAC_TEST_EXPECTED_SHA="$HEAD_SHA" \
  "$RUNNER" anthropic > "$OUTPUT" 2>&1

if [[ "$(sed -n '1p' "$CALL_LOG")" != "deterministic" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live" ||
      -n "$(sed -n '3p' "$CALL_LOG")" ]]; then
  echo "Anthropic runner did not execute deterministic then live tasks exactly once." >&2
  exit 1
fi
if grep -Fq "$ANTHROPIC_SYNTHETIC_KEY" "$OUTPUT" ||
  grep -Fq "$SYNTHETIC_KEY" "$OUTPUT" ||
  grep -Fq "$OPENROUTER_SYNTHETIC_KEY" "$OUTPUT" ||
  grep -Fq "$GATEWAY_SYNTHETIC_KEY" "$OUTPUT" ||
  grep -Fq "$GATEWAY_BASE_URL" "$OUTPUT"; then
  echo "Successful Anthropic runner output exposed credential material." >&2
  exit 1
fi
if ! grep -Fq "provider=anthropic" "$OUTPUT" ||
  ! grep -Fq "head_sha=$HEAD_SHA" "$OUTPUT" ||
  ! grep -Fq "model=$ANTHROPIC_MODEL" "$OUTPUT"; then
  echo "Successful Anthropic runner output omitted bounded evidence metadata." >&2
  exit 1
fi

: > "$CALL_LOG"
env \
  OPENAI_API_KEY="$SYNTHETIC_KEY" \
  OPENAI_LIVE_MODEL="$MODEL" \
  ANTHROPIC_API_KEY="$ANTHROPIC_SYNTHETIC_KEY" \
  ANTHROPIC_LIVE_MODEL="$ANTHROPIC_MODEL" \
  OPENROUTER_API_KEY="$OPENROUTER_SYNTHETIC_KEY" \
  OPENROUTER_LIVE_MODEL="$OPENROUTER_MODEL" \
  GATEWAY_LIVE_BASE_URL="$GATEWAY_BASE_URL" \
  GATEWAY_API_KEY="$GATEWAY_SYNTHETIC_KEY" \
  GATEWAY_LIVE_MODEL="$GATEWAY_MODEL" \
  GATEWAY_LIVE_STRUCTURED_OUTPUT="$GATEWAY_STRUCTURED_OUTPUT" \
  UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
  UAC_TEST_CALL_LOG="$CALL_LOG" \
  UAC_TEST_EXPECTED_KEY="$OPENROUTER_SYNTHETIC_KEY" \
  UAC_TEST_EXPECTED_MODEL="$OPENROUTER_MODEL" \
  UAC_TEST_EXPECTED_SHA="$HEAD_SHA" \
  "$RUNNER" openrouter > "$OUTPUT" 2>&1

if [[ "$(sed -n '1p' "$CALL_LOG")" != "deterministic" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live" ||
      -n "$(sed -n '3p' "$CALL_LOG")" ]]; then
  echo "OpenRouter live runner did not execute deterministic then live tasks exactly once." >&2
  exit 1
fi
if grep -Fq "$SYNTHETIC_KEY" "$OUTPUT" ||
  grep -Fq "$ANTHROPIC_SYNTHETIC_KEY" "$OUTPUT" ||
  grep -Fq "$OPENROUTER_SYNTHETIC_KEY" "$OUTPUT" ||
  grep -Fq "$GATEWAY_SYNTHETIC_KEY" "$OUTPUT" ||
  grep -Fq "$GATEWAY_BASE_URL" "$OUTPUT"; then
  echo "Successful OpenRouter runner output exposed credential material." >&2
  exit 1
fi
if ! grep -Fq "provider=openrouter" "$OUTPUT" ||
  ! grep -Fq "head_sha=$HEAD_SHA" "$OUTPUT" ||
  ! grep -Fq "model=$OPENROUTER_MODEL" "$OUTPUT"; then
  echo "Successful OpenRouter runner output omitted bounded evidence metadata." >&2
  exit 1
fi

: > "$CALL_LOG"
env \
  OPENAI_API_KEY="$SYNTHETIC_KEY" \
  OPENAI_LIVE_MODEL="$MODEL" \
  ANTHROPIC_API_KEY="$ANTHROPIC_SYNTHETIC_KEY" \
  ANTHROPIC_LIVE_MODEL="$ANTHROPIC_MODEL" \
  OPENROUTER_API_KEY="$OPENROUTER_SYNTHETIC_KEY" \
  OPENROUTER_LIVE_MODEL="$OPENROUTER_MODEL" \
  GATEWAY_LIVE_BASE_URL="$GATEWAY_BASE_URL" \
  GATEWAY_API_KEY="$GATEWAY_SYNTHETIC_KEY" \
  GATEWAY_LIVE_MODEL="$GATEWAY_MODEL" \
  GATEWAY_LIVE_STRUCTURED_OUTPUT="$GATEWAY_STRUCTURED_OUTPUT" \
  UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
  UAC_TEST_CALL_LOG="$CALL_LOG" \
  UAC_TEST_EXPECTED_BASE_URL="$GATEWAY_BASE_URL" \
  UAC_TEST_EXPECTED_KEY="$GATEWAY_SYNTHETIC_KEY" \
  UAC_TEST_EXPECTED_MODEL="$GATEWAY_MODEL" \
  UAC_TEST_EXPECTED_STRUCTURED_OUTPUT="$GATEWAY_STRUCTURED_OUTPUT" \
  UAC_TEST_EXPECTED_SHA="$HEAD_SHA" \
  "$RUNNER" gateway > "$OUTPUT" 2>&1

if [[ "$(sed -n '1p' "$CALL_LOG")" != "deterministic" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live" ||
      -n "$(sed -n '3p' "$CALL_LOG")" ]]; then
  echo "Gateway live runner did not execute deterministic then live tasks exactly once." >&2
  exit 1
fi
if grep -Fq "$SYNTHETIC_KEY" "$OUTPUT" ||
  grep -Fq "$ANTHROPIC_SYNTHETIC_KEY" "$OUTPUT" ||
  grep -Fq "$OPENROUTER_SYNTHETIC_KEY" "$OUTPUT" ||
  grep -Fq "$GATEWAY_SYNTHETIC_KEY" "$OUTPUT"; then
  echo "Successful Gateway runner output exposed credential material." >&2
  exit 1
fi
if grep -Fq "$GATEWAY_BASE_URL" "$OUTPUT"; then
  echo "Successful Gateway runner output retained the host-supplied base URL." >&2
  exit 1
fi
if ! grep -Fq "provider=gateway" "$OUTPUT" ||
  ! grep -Fq "head_sha=$HEAD_SHA" "$OUTPUT" ||
  ! grep -Fq "model=$GATEWAY_MODEL" "$OUTPUT" ||
  ! grep -Fq "structured_output=$GATEWAY_STRUCTURED_OUTPUT" "$OUTPUT"; then
  echo "Successful Gateway runner output omitted bounded evidence metadata." >&2
  exit 1
fi

echo "Live verification runner regression tests passed."
