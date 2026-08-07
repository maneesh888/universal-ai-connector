#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROVIDER="${1:-}"

usage() {
  cat <<'EOF'
Usage: ./scripts/check-live.sh <openai|anthropic>

Runs the deterministic adapter suite followed by the selected exact-head provider live suite.
OpenAI process environment:
  OPENAI_API_KEY       Dedicated revocable test-project credential.
  OPENAI_LIVE_MODEL    Explicit model identifier enabled for the test project.

Anthropic process environment:
  ANTHROPIC_API_KEY       Dedicated revocable test-workspace credential.
  ANTHROPIC_LIVE_MODEL    Explicit model identifier enabled for the test workspace.

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
  local provider_label="$2"
  local key_name="$3"
  local model_name="$4"
  local provider_route="$5"
  cat >&2 <<EOF
$name is required for $provider_label live verification.

Configure the ignored local file before retrying:
  cp .env.live.example .env.live
  chmod 600 .env.live
  Open .env.live in your local editor and set $key_name and $model_name.
  set -a
  source .env.live
  set +a
  ./scripts/check-live.sh $provider_route

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

if [[ "$#" -ne 1 ]]; then
  usage >&2
  exit 2
fi

case "$PROVIDER" in
  openai)
    PROVIDER_LABEL="OpenAI"
    KEY_NAME="OPENAI_API_KEY"
    MODEL_NAME="OPENAI_LIVE_MODEL"
    LIVE_TASK="openAiLiveTest"
    ;;
  anthropic)
    PROVIDER_LABEL="Anthropic"
    KEY_NAME="ANTHROPIC_API_KEY"
    MODEL_NAME="ANTHROPIC_LIVE_MODEL"
    LIVE_TASK="anthropicLiveTest"
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

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

API_KEY="${!KEY_NAME:-}"
LIVE_MODEL="${!MODEL_NAME:-}"

if [[ -z "$API_KEY" ]]; then
  fail_missing_live_input "$KEY_NAME" "$PROVIDER_LABEL" "$KEY_NAME" "$MODEL_NAME" "$PROVIDER"
fi

if [[ "${#API_KEY}" -gt 8192 ||
      "$API_KEY" == *$'\n'* ||
      "$API_KEY" == *$'\r'* ]]; then
  fail "$KEY_NAME has an invalid shape."
fi

if [[ -z "$LIVE_MODEL" ]]; then
  fail_missing_live_input "$MODEL_NAME" "$PROVIDER_LABEL" "$KEY_NAME" "$MODEL_NAME" "$PROVIDER"
fi

if [[ "${#LIVE_MODEL}" -gt 128 ||
      ! "$LIVE_MODEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  fail "$MODEL_NAME must be a bounded $PROVIDER_LABEL model identifier."
fi

if [[ ! -x "$ROOT/gradlew" ]]; then
  fail "The Gradle wrapper is required for $PROVIDER_LABEL live verification."
fi

echo "Running deterministic $PROVIDER_LABEL prerequisite tests for exact HEAD."
env \
  -u OPENAI_API_KEY \
  -u OPENAI_LIVE_MODEL \
  -u ANTHROPIC_API_KEY \
  -u ANTHROPIC_LIVE_MODEL \
  -u OPENROUTER_API_KEY \
  -u OPENROUTER_LIVE_MODEL \
  -u UAC_LIVE_EXPECTED_SHA \
  "$ROOT/gradlew" :bridge:jvmTest

POST_DETERMINISTIC_SHA="$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" ||
  fail "Live verification could not revalidate HEAD after deterministic tests."
if [[ "$POST_DETERMINISTIC_SHA" != "$HEAD_SHA" ]]; then
  fail "Live verification HEAD changed during deterministic tests."
fi
require_clean_checkout

echo "Running local $PROVIDER_LABEL live smoke tests for exact HEAD."
case "$PROVIDER" in
  openai)
    env \
      -u ANTHROPIC_API_KEY \
      -u ANTHROPIC_LIVE_MODEL \
      -u OPENROUTER_API_KEY \
      -u OPENROUTER_LIVE_MODEL \
      OPENAI_API_KEY="$API_KEY" \
      OPENAI_LIVE_MODEL="$LIVE_MODEL" \
      UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
      "$ROOT/gradlew" \
        ":bridge:$LIVE_TASK" \
        --no-daemon \
        --no-configuration-cache \
        "-PuacLiveExpectedSha=$HEAD_SHA"
    ;;
  anthropic)
    env \
      -u OPENAI_API_KEY \
      -u OPENAI_LIVE_MODEL \
      -u OPENROUTER_API_KEY \
      -u OPENROUTER_LIVE_MODEL \
      ANTHROPIC_API_KEY="$API_KEY" \
      ANTHROPIC_LIVE_MODEL="$LIVE_MODEL" \
      UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
      "$ROOT/gradlew" \
        ":bridge:$LIVE_TASK" \
        --no-daemon \
        --no-configuration-cache \
        "-PuacLiveExpectedSha=$HEAD_SHA"
    ;;
esac

POST_LIVE_SHA="$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" ||
  fail "Live verification could not revalidate HEAD after provider tests."
if [[ "$POST_LIVE_SHA" != "$HEAD_SHA" ]]; then
  fail "Live verification HEAD changed during provider tests."
fi
require_clean_checkout

echo "$PROVIDER_LABEL live verification passed."
echo "provider=$PROVIDER"
echo "model=$LIVE_MODEL"
echo "head_sha=$HEAD_SHA"
