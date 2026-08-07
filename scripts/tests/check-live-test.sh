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
  UAC_LIVE_EXPECTED_SHA
TEST_DIRECTORY="$(mktemp -d)"
TEST_REPOSITORY="$TEST_DIRECTORY/repository"
RUNNER="$TEST_REPOSITORY/scripts/check-live.sh"
CALL_LOG="$TEST_DIRECTORY/calls.log"
OUTPUT="$TEST_DIRECTORY/output.log"
SYNTHETIC_KEY="test-key-material-that-must-not-appear"
MODEL="test-model-2026-08-02"
ANTHROPIC_SYNTHETIC_KEY="test-anthropic-material-that-must-not-appear"
ANTHROPIC_MODEL="test-anthropic-model-2026-08-07"

cleanup() {
  rm -rf "$TEST_DIRECTORY"
}
trap cleanup EXIT

mkdir -p "$TEST_REPOSITORY/scripts"
cp "$ROOT/scripts/check-live.sh" "$RUNNER"
chmod +x "$RUNNER"

cat > "$TEST_REPOSITORY/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$*" == *":bridge:jvmTest"* ]]; then
  if [[ -n "${OPENAI_API_KEY:-}" ||
        -n "${OPENAI_LIVE_MODEL:-}" ||
        -n "${ANTHROPIC_API_KEY:-}" ||
        -n "${ANTHROPIC_LIVE_MODEL:-}" ||
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
  if grep -Fq "$SYNTHETIC_KEY" "$OUTPUT"; then
    echo "Live runner exposed credential material." >&2
    exit 1
  fi
}

expect_failure \
  "Usage: ./scripts/check-live.sh openai" \
  "$RUNNER" unsupported

expect_failure \
  "OPENAI_API_KEY is required" \
  "$RUNNER" openai
if ! grep -Fq "cp .env.live.example .env.live" "$OUTPUT" ||
  ! grep -Fq "chmod 600 .env.live" "$OUTPUT" ||
  ! grep -Fq "Open .env.live in your local editor" "$OUTPUT" ||
  ! grep -Fq "never opens, reads, or sources .env.live automatically" "$OUTPUT"; then
  echo "Missing-key failure omitted safe local configuration guidance." >&2
  exit 1
fi

expect_failure \
  "OPENAI_LIVE_MODEL is required" \
  env OPENAI_API_KEY="$SYNTHETIC_KEY" "$RUNNER" openai
if ! grep -Fq "cp .env.live.example .env.live" "$OUTPUT" ||
  ! grep -Fq "source .env.live" "$OUTPUT"; then
  echo "Missing-model failure omitted safe local configuration guidance." >&2
  exit 1
fi

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

: > "$CALL_LOG"
POST_DETERMINISTIC_DIRTY_PATH="$TEST_REPOSITORY/post-deterministic-dirty.txt"
expect_failure \
  "Live verification requires a clean checkout" \
  env \
    OPENAI_API_KEY="$SYNTHETIC_KEY" \
    OPENAI_LIVE_MODEL="$MODEL" \
    ANTHROPIC_API_KEY="$ANTHROPIC_SYNTHETIC_KEY" \
    ANTHROPIC_LIVE_MODEL="$ANTHROPIC_MODEL" \
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
if ! grep -Fq "head_sha=$HEAD_SHA" "$OUTPUT" ||
  ! grep -Fq "model=$MODEL" "$OUTPUT"; then
  echo "Successful live runner output omitted bounded evidence metadata." >&2
  exit 1
fi

echo "Live verification runner regression tests passed."
