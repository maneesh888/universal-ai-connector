#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROVIDER="${1:-}"

usage() {
  cat <<'EOF'
Usage: ./scripts/check-live.sh <provider>

Supported providers and required process environment:
  openai
  OPENAI_API_KEY       Dedicated revocable test-project credential.
  OPENAI_LIVE_MODEL    Explicit model identifier enabled for the test project.

  anthropic
  ANTHROPIC_API_KEY       Dedicated revocable test-workspace credential.
  ANTHROPIC_LIVE_MODEL    Explicit model identifier enabled for the test workspace.

  openrouter
  OPENROUTER_API_KEY    Dedicated revocable spend-limited test credential.
  OPENROUTER_LIVE_MODEL Explicit bounded-cost model slug enabled for the credential.

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
  cat >&2 <<EOF
$name is required for $provider_label live verification.

Configure the ignored local file before retrying:
  cp .env.live.example .env.live
  chmod 600 .env.live
  Open .env.live in your local editor and set $key_name and $model_name.
  set -a
  source .env.live
  set +a
  ./scripts/check-live.sh $PROVIDER

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
    LIVE_TASK=":bridge:openAiLiveTest"
    ;;
  anthropic)
    PROVIDER_LABEL="Anthropic"
    KEY_NAME="ANTHROPIC_API_KEY"
    MODEL_NAME="ANTHROPIC_LIVE_MODEL"
    LIVE_TASK=":bridge:anthropicLiveTest"
    ;;
  openrouter)
    PROVIDER_LABEL="OpenRouter"
    KEY_NAME="OPENROUTER_API_KEY"
    MODEL_NAME="OPENROUTER_LIVE_MODEL"
    LIVE_TASK=":bridge:openRouterLiveTest"
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

if [[ -z "${!KEY_NAME:-}" ]]; then
  fail_missing_live_input "$KEY_NAME" "$PROVIDER_LABEL" "$KEY_NAME" "$MODEL_NAME"
fi
KEY_VALUE="${!KEY_NAME}"

if [[ "${#KEY_VALUE}" -gt 8192 ||
      "$KEY_VALUE" == *$'\n'* ||
      "$KEY_VALUE" == *$'\r'* ]]; then
  fail "$KEY_NAME has an invalid shape."
fi

if [[ -z "${!MODEL_NAME:-}" ]]; then
  fail_missing_live_input "$MODEL_NAME" "$PROVIDER_LABEL" "$KEY_NAME" "$MODEL_NAME"
fi
MODEL_VALUE="${!MODEL_NAME}"

case "$PROVIDER" in
  openai)
    if [[ "${#OPENAI_LIVE_MODEL}" -gt 128 ||
          ! "$OPENAI_LIVE_MODEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
      fail "OPENAI_LIVE_MODEL must be a bounded OpenAI model identifier."
    fi
    ;;
  anthropic)
    if [[ "${#ANTHROPIC_LIVE_MODEL}" -gt 128 ||
          ! "$ANTHROPIC_LIVE_MODEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
      fail "ANTHROPIC_LIVE_MODEL must be a bounded Anthropic model identifier."
    fi
    ;;
  openrouter)
    if [[ "${#OPENROUTER_LIVE_MODEL}" -gt 256 ||
          ! "$OPENROUTER_LIVE_MODEL" =~ ^[A-Za-z0-9][A-Za-z0-9._:/@+-]*$ ]]; then
      fail "OPENROUTER_LIVE_MODEL must be a bounded OpenRouter model slug."
    fi
    ;;
esac

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
env \
  -u OPENAI_API_KEY \
  -u OPENAI_LIVE_MODEL \
  -u ANTHROPIC_API_KEY \
  -u ANTHROPIC_LIVE_MODEL \
  -u OPENROUTER_API_KEY \
  -u OPENROUTER_LIVE_MODEL \
  "$KEY_NAME=$KEY_VALUE" \
  "$MODEL_NAME=$MODEL_VALUE" \
  UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
  "$ROOT/gradlew" \
    "$LIVE_TASK" \
    --no-daemon \
    --no-configuration-cache \
    "-PuacLiveExpectedSha=$HEAD_SHA"

POST_LIVE_SHA="$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" ||
  fail "Live verification could not revalidate HEAD after provider tests."
if [[ "$POST_LIVE_SHA" != "$HEAD_SHA" ]]; then
  fail "Live verification HEAD changed during provider tests."
fi
require_clean_checkout

echo "$PROVIDER_LABEL live verification passed."
echo "provider=$PROVIDER"
echo "model=$MODEL_VALUE"
echo "head_sha=$HEAD_SHA"
