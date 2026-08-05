#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROVIDER="${1:-}"

usage() {
  cat <<'EOF'
Usage: ./scripts/check-live.sh openai

Runs the deterministic adapter suite followed by the exact-head OpenAI live suite.
Required process environment:
  OPENAI_API_KEY       Dedicated revocable test-project credential.
  OPENAI_LIVE_MODEL    Explicit model identifier enabled for the test project.

Optional:
  UAC_LIVE_EXPECTED_SHA  Exact 40-character commit SHA expected by the caller.
EOF
}

fail() {
  echo "$1" >&2
  exit "${2:-1}"
}

fail_missing_live_input() {
  local name="$1"
  cat >&2 <<EOF
$name is required for OpenAI live verification.

Configure the ignored local file before retrying:
  cp .env.live.example .env.live
  chmod 600 .env.live
  Open .env.live in your local editor and set OPENAI_API_KEY and OPENAI_LIVE_MODEL.
  set -a
  source .env.live
  set +a
  ./scripts/check-live.sh openai

The runner never opens, reads, or sources .env.live automatically.
EOF
  exit 1
}

CHECKOUT_STATUS_FILE="$(mktemp)"
trap 'rm -f "$CHECKOUT_STATUS_FILE"' EXIT

require_clean_checkout() {
  if ! git -C "$ROOT" status \
    --porcelain=v1 \
    -z \
    --untracked-files=all > "$CHECKOUT_STATUS_FILE"; then
    fail "Live verification could not inspect checkout state."
  fi

  if [[ -s "$CHECKOUT_STATUS_FILE" ]]; then
    fail "Live verification requires a clean checkout bound to committed HEAD."
  fi
}

if [[ "$PROVIDER" != "openai" || "$#" -ne 1 ]]; then
  usage >&2
  exit 2
fi

if ! command -v git >/dev/null 2>&1; then
  fail "git is required for live verification."
fi

HEAD_SHA="$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" ||
  fail "Live verification requires a Git checkout with a committed HEAD."

if [[ ! "$HEAD_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  fail "Live verification could not resolve an exact 40-character HEAD SHA."
fi

require_clean_checkout

EXPECTED_SHA="${UAC_LIVE_EXPECTED_SHA:-$HEAD_SHA}"
if [[ ! "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ || "$EXPECTED_SHA" != "$HEAD_SHA" ]]; then
  fail "Live verification HEAD does not match UAC_LIVE_EXPECTED_SHA."
fi

if [[ -z "${OPENAI_API_KEY:-}" ]]; then
  fail_missing_live_input "OPENAI_API_KEY"
fi

if [[ "${#OPENAI_API_KEY}" -gt 8192 ||
      "$OPENAI_API_KEY" == *$'\n'* ||
      "$OPENAI_API_KEY" == *$'\r'* ]]; then
  fail "OPENAI_API_KEY has an invalid shape."
fi

if [[ -z "${OPENAI_LIVE_MODEL:-}" ]]; then
  fail_missing_live_input "OPENAI_LIVE_MODEL"
fi

if [[ "${#OPENAI_LIVE_MODEL}" -gt 128 ||
      ! "$OPENAI_LIVE_MODEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  fail "OPENAI_LIVE_MODEL must be a bounded OpenAI model identifier."
fi

if [[ ! -x "$ROOT/gradlew" ]]; then
  fail "The Gradle wrapper is required for OpenAI live verification."
fi

echo "Running deterministic OpenAI prerequisite tests for exact HEAD."
env \
  -u OPENAI_API_KEY \
  -u OPENAI_LIVE_MODEL \
  -u UAC_LIVE_EXPECTED_SHA \
  "$ROOT/gradlew" :bridge:jvmTest

POST_DETERMINISTIC_SHA="$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" ||
  fail "Live verification could not revalidate HEAD after deterministic tests."
if [[ "$POST_DETERMINISTIC_SHA" != "$HEAD_SHA" ]]; then
  fail "Live verification HEAD changed during deterministic tests."
fi
require_clean_checkout

echo "Running local OpenAI live smoke tests for exact HEAD."
OPENAI_API_KEY="$OPENAI_API_KEY" \
OPENAI_LIVE_MODEL="$OPENAI_LIVE_MODEL" \
UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
  "$ROOT/gradlew" \
    :bridge:openAiLiveTest \
    --no-daemon \
    --no-configuration-cache \
    "-PuacLiveExpectedSha=$HEAD_SHA"

POST_LIVE_SHA="$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" ||
  fail "Live verification could not revalidate HEAD after provider tests."
if [[ "$POST_LIVE_SHA" != "$HEAD_SHA" ]]; then
  fail "Live verification HEAD changed during provider tests."
fi
require_clean_checkout

echo "OpenAI live verification passed."
echo "provider=openai"
echo "model=$OPENAI_LIVE_MODEL"
echo "head_sha=$HEAD_SHA"
