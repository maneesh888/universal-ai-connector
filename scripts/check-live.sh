#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROVIDER="${1:-}"
LOCAL_CONFIG_HELPER="$ROOT/scripts/local-config.sh"

usage() {
  cat <<'EOF'
Usage: ./scripts/check-live.sh <provider>

Supported providers and required live inputs:
  openai
  OPENAI_API_KEY       Dedicated revocable test-project credential.
  OPENAI_LIVE_MODEL    Explicit model identifier enabled for the test project.

  anthropic
  ANTHROPIC_API_KEY       Dedicated revocable test-workspace credential.
  ANTHROPIC_LIVE_MODEL    Explicit model identifier enabled for the test workspace.

  openrouter
  OPENROUTER_API_KEY    Dedicated revocable spend-limited test credential.
  OPENROUTER_LIVE_MODEL Explicit bounded-cost model slug enabled for the credential.

  gateway
  GATEWAY_LIVE_BASE_URL Gateway base URL ending in /v1; HTTPS or loopback HTTP only.
  GATEWAY_API_KEY       Dedicated revocable Gateway test credential.
  GATEWAY_LIVE_MODEL    Explicit model identifier enabled for the Gateway key.
  GATEWAY_LIVE_STRUCTURED_OUTPUT
                        Explicit true or false for the selected model capability.

Optional:
  UAC_LIVE_EXPECTED_SHA  Exact 40-character commit SHA expected by the caller.
  UAC_LIVE_ENV_FILE      .env.live or .env.live.<name> in the primary checkout.

Non-empty process environment values override the canonical ignored local file.
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
  local input_names="$key_name and $model_name"
  if [[ -n "${BASE_URL_NAME:-}" ]]; then
    input_names="$BASE_URL_NAME, $key_name, $model_name, and GATEWAY_LIVE_STRUCTURED_OUTPUT"
  fi
  cat >&2 <<EOF
$name is required for $provider_label live verification.

Configure the ignored local file before retrying:
  Primary checkout: $PRIMARY_CHECKOUT
  Expected file: $LIVE_ENV_FILE_PATH
  Copy $PRIMARY_CHECKOUT/.env.live.example to that exact path.
  chmod 600 "$LIVE_ENV_FILE_PATH"
  Open the file in your local editor and set $input_names.
  ./scripts/check-live.sh $PROVIDER

The runner parses only documented literal assignments and never displays their values.
Non-empty process environment values take precedence over the file.
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
    BASE_URL_NAME=""
    KEY_NAME="OPENAI_API_KEY"
    MODEL_NAME="OPENAI_LIVE_MODEL"
    LIVE_TASK=":bridge:openAiLiveTest"
    ;;
  anthropic)
    PROVIDER_LABEL="Anthropic"
    BASE_URL_NAME=""
    KEY_NAME="ANTHROPIC_API_KEY"
    MODEL_NAME="ANTHROPIC_LIVE_MODEL"
    LIVE_TASK=":bridge:anthropicLiveTest"
    ;;
  openrouter)
    PROVIDER_LABEL="OpenRouter"
    BASE_URL_NAME=""
    KEY_NAME="OPENROUTER_API_KEY"
    MODEL_NAME="OPENROUTER_LIVE_MODEL"
    LIVE_TASK=":bridge:openRouterLiveTest"
    ;;
  gateway)
    PROVIDER_LABEL="Gateway"
    BASE_URL_NAME="GATEWAY_LIVE_BASE_URL"
    KEY_NAME="GATEWAY_API_KEY"
    MODEL_NAME="GATEWAY_LIVE_MODEL"
    LIVE_TASK=":bridge:gatewayLiveTest"
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

if ! command -v git >/dev/null 2>&1; then
  fail "git is required for live verification."
fi
if [[ ! -r "$LOCAL_CONFIG_HELPER" ]]; then
  fail "The local configuration safety helper is required for live verification."
fi
# shellcheck source=scripts/local-config.sh
source "$LOCAL_CONFIG_HELPER"

PRIMARY_CHECKOUT="$(uac_primary_checkout "$ROOT")" || exit $?
LIVE_ENV_FILE_PATH="$(uac_live_env_path "$ROOT")" || exit $?

REQUIRED_LIVE_INPUTS=("$KEY_NAME" "$MODEL_NAME")
if [[ -n "$BASE_URL_NAME" ]]; then
  REQUIRED_LIVE_INPUTS=(
    "$BASE_URL_NAME"
    "$KEY_NAME"
    "$MODEL_NAME"
    "GATEWAY_LIVE_STRUCTURED_OUTPUT"
  )
fi
NEEDS_LOCAL_CONFIG=false
for required_live_input in "${REQUIRED_LIVE_INPUTS[@]}"; do
  if [[ -z "${!required_live_input:-}" ]]; then
    NEEDS_LOCAL_CONFIG=true
    break
  fi
done
if [[ "$NEEDS_LOCAL_CONFIG" == "true" || -n "${UAC_LIVE_ENV_FILE:-}" ]]; then
  local_config_status=0
  uac_load_live_environment "$ROOT" "${REQUIRED_LIVE_INPUTS[@]}" ||
    local_config_status=$?
  case "$local_config_status" in
    0)
      ;;
    3)
      if [[ -n "${UAC_LIVE_ENV_FILE:-}" ]]; then
        exit 3
      fi
      ;;
    *)
      exit "$local_config_status"
      ;;
  esac
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

BASE_URL_VALUE=""
if [[ -n "$BASE_URL_NAME" ]]; then
  if [[ -z "${!BASE_URL_NAME:-}" ]]; then
    fail_missing_live_input "$BASE_URL_NAME" "$PROVIDER_LABEL" "$KEY_NAME" "$MODEL_NAME"
  fi
  BASE_URL_VALUE="${!BASE_URL_NAME}"
  if [[ "${#BASE_URL_VALUE}" -gt 2048 ||
        "$BASE_URL_VALUE" == *$'\n'* ||
        "$BASE_URL_VALUE" == *$'\r'* ||
        "$BASE_URL_VALUE" == *$'\t'* ||
        "$BASE_URL_VALUE" == *' '* ||
        "$BASE_URL_VALUE" == *'@'* ||
        "$BASE_URL_VALUE" == *'?'* ||
        "$BASE_URL_VALUE" == *'#'* ]]; then
    fail "$BASE_URL_NAME has an invalid shape."
  fi
  if [[ "$BASE_URL_VALUE" != https://* &&
        ! "$BASE_URL_VALUE" =~ ^http://(localhost|127\.0\.0\.1|\[::1\])(:[0-9]{1,5})?(/.*)?$ ]]; then
    fail "$BASE_URL_NAME must use HTTPS or loopback HTTP."
  fi
  if [[ "$BASE_URL_VALUE" != */v1 && "$BASE_URL_VALUE" != */v1/ ]]; then
    fail "$BASE_URL_NAME must end in /v1."
  fi
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

STRUCTURED_OUTPUT_VALUE=""
if [[ "$PROVIDER" == "gateway" ]]; then
  if [[ -z "${GATEWAY_LIVE_STRUCTURED_OUTPUT:-}" ]]; then
    fail_missing_live_input \
      "GATEWAY_LIVE_STRUCTURED_OUTPUT" \
      "$PROVIDER_LABEL" \
      "$KEY_NAME" \
      "$MODEL_NAME"
  fi
  STRUCTURED_OUTPUT_VALUE="$GATEWAY_LIVE_STRUCTURED_OUTPUT"
  if [[ "$STRUCTURED_OUTPUT_VALUE" != "true" && "$STRUCTURED_OUTPUT_VALUE" != "false" ]]; then
    fail "GATEWAY_LIVE_STRUCTURED_OUTPUT must be true or false."
  fi
fi

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
  gateway)
    if [[ "${#GATEWAY_LIVE_MODEL}" -gt 256 ||
          ! "$GATEWAY_LIVE_MODEL" =~ ^[A-Za-z0-9][A-Za-z0-9._:/@+-]*$ ]]; then
      fail "GATEWAY_LIVE_MODEL must be a bounded Gateway model identifier."
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
  -u GATEWAY_LIVE_BASE_URL \
  -u GATEWAY_API_KEY \
  -u GATEWAY_LIVE_MODEL \
  -u GATEWAY_LIVE_STRUCTURED_OUTPUT \
  -u UAC_LIVE_ENV_FILE \
  -u UAC_LIVE_EXPECTED_SHA \
  "$ROOT/gradlew" :bridge:jvmTest

POST_DETERMINISTIC_SHA="$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" ||
  fail "Live verification could not revalidate HEAD after deterministic tests."
if [[ "$POST_DETERMINISTIC_SHA" != "$HEAD_SHA" ]]; then
  fail "Live verification HEAD changed during deterministic tests."
fi
require_clean_checkout

echo "Running local $PROVIDER_LABEL live smoke tests for exact HEAD."
LIVE_ENVIRONMENT=(
  "$KEY_NAME=$KEY_VALUE"
  "$MODEL_NAME=$MODEL_VALUE"
)
if [[ -n "$BASE_URL_NAME" ]]; then
  LIVE_ENVIRONMENT+=("$BASE_URL_NAME=$BASE_URL_VALUE")
  LIVE_ENVIRONMENT+=("GATEWAY_LIVE_STRUCTURED_OUTPUT=$STRUCTURED_OUTPUT_VALUE")
fi
env \
  -u OPENAI_API_KEY \
  -u OPENAI_LIVE_MODEL \
  -u ANTHROPIC_API_KEY \
  -u ANTHROPIC_LIVE_MODEL \
  -u OPENROUTER_API_KEY \
  -u OPENROUTER_LIVE_MODEL \
  -u GATEWAY_LIVE_BASE_URL \
  -u GATEWAY_API_KEY \
  -u GATEWAY_LIVE_MODEL \
  -u GATEWAY_LIVE_STRUCTURED_OUTPUT \
  -u UAC_LIVE_ENV_FILE \
  "${LIVE_ENVIRONMENT[@]}" \
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
if [[ "$PROVIDER" == "gateway" ]]; then
  echo "structured_output=$STRUCTURED_OUTPUT_VALUE"
fi
echo "head_sha=$HEAD_SHA"
