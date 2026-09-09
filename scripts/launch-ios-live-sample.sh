#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_CONFIG_HELPER="$ROOT/scripts/local-config.sh"
PROVIDER="${1:-}"
EXPECTED_SHA="${UAC_LIVE_EXPECTED_SHA:-}"
unset PROOF_CREDENTIAL PROOF_MODEL PROOF_BASE_URL
PROOF_CREDENTIAL="${UAC_IOS_SAMPLE_PROOF_CREDENTIAL:-}"
PROOF_MODEL="${UAC_IOS_SAMPLE_PROOF_MODEL:-}"
PROOF_BASE_URL="${UAC_IOS_SAMPLE_PROOF_BASE_URL:-}"
DERIVED_DATA="${UAC_IOS_LIVE_SAMPLE_DERIVED_DATA:-${TMPDIR:-/tmp}/universal-ai-connector-ios-live-sample-derived}"
BUNDLE_ID="com.maneesh.universalai.connector.sample"

unset \
  UAC_IOS_SAMPLE_PROOF_CREDENTIAL \
  UAC_IOS_SAMPLE_PROOF_MODEL \
  UAC_IOS_SAMPLE_PROOF_BASE_URL

fail() {
  echo "$1" >&2
  exit 1
}

if [[ ! -r "$LOCAL_CONFIG_HELPER" ]]; then
  fail "The local configuration safety helper is required for Simulator live proof."
fi
# shellcheck source=scripts/local-config.sh
source "$LOCAL_CONFIG_HELPER"

if [[ "$#" -ne 1 ]]; then
  fail "Usage: ./scripts/launch-ios-live-sample.sh <provider>"
fi

case "$PROVIDER" in
  openai | anthropic | openrouter)
    APP_PROVIDER="$PROVIDER"
    if [[ -n "$PROOF_BASE_URL" ]]; then
      fail "Only Gateway Simulator proof accepts a custom base URL."
    fi
    ;;
  gateway)
    APP_PROVIDER="openai-compatible"
    if [[ -z "$PROOF_BASE_URL" ]]; then
      fail "Gateway Simulator proof requires its validated base URL."
    fi
    ;;
  *)
    fail "Simulator live proof requires a delivered provider or Gateway."
    ;;
esac

if [[ -z "$PROOF_CREDENTIAL" ||
      "${#PROOF_CREDENTIAL}" -gt 8192 ||
      "$PROOF_CREDENTIAL" == *$'\n'* ||
      "$PROOF_CREDENTIAL" == *$'\r'* ]]; then
  fail "Simulator live proof requires a valid host credential."
fi
if [[ -z "$PROOF_MODEL" ||
      "${#PROOF_MODEL}" -gt 256 ||
      "$PROOF_MODEL" == *$'\n'* ||
      "$PROOF_MODEL" == *$'\r'* ]]; then
  fail "Simulator live proof requires a valid exact model identifier."
fi

HEAD_SHA="$(uac_git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" ||
  fail "Simulator live proof requires a Git checkout."
if [[ ! "$HEAD_SHA" =~ ^[0-9a-f]{40}$ || "$EXPECTED_SHA" != "$HEAD_SHA" ]]; then
  fail "Simulator live proof is not bound to the expected exact HEAD."
fi
if [[ -n "$(uac_git -C "$ROOT" status --porcelain=v1 --untracked-files=all)" ]]; then
  fail "Simulator live proof requires a clean checkout."
fi
if ! command -v xcrun >/dev/null 2>&1; then
  fail "Simulator live proof requires Xcode command-line tools."
fi

DESTINATION="$($ROOT/scripts/simulator-destination.sh)"
if [[ ! "$DESTINATION" =~ id=([0-9A-Fa-f-]{36})$ ]]; then
  fail "Simulator live proof could not resolve a device identifier."
fi
DEVICE_ID="${BASH_REMATCH[1]}"

xcrun simctl boot "$DEVICE_ID" >/dev/null 2>&1 || true
xcrun simctl bootstatus "$DEVICE_ID" -b >/dev/null

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
  -u UAC_IOS_SAMPLE_LIVE_PROOF \
  -u UAC_SKIP_XCFRAMEWORK_BUILD \
  UAC_SAMPLE_DESTINATION="$DESTINATION" \
  UAC_SAMPLE_DERIVED_DATA="$DERIVED_DATA" \
  "$ROOT/scripts/build-sample.sh" >/dev/null

APP_PATH="$DERIVED_DATA/Build/Products/Debug-iphonesimulator/UniversalAiConnectorSample.app"
if [[ ! -d "$APP_PATH" ]]; then
  fail "Simulator live proof could not locate the built sample application."
fi

xcrun simctl install "$DEVICE_ID" "$APP_PATH" >/dev/null

(
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
    SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_BOOTSTRAP \
    SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_PROVIDER \
    SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_CREDENTIAL \
    SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_MODEL \
    SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_BASE_URL
  export SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_BOOTSTRAP=1
  export SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_PROVIDER="$APP_PROVIDER"
  export SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_CREDENTIAL="$PROOF_CREDENTIAL"
  export SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_MODEL="$PROOF_MODEL"
  if [[ -n "$PROOF_BASE_URL" ]]; then
    export SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_BASE_URL="$PROOF_BASE_URL"
  fi
  xcrun simctl launch --terminate-running-process \
    "$DEVICE_ID" \
    "$BUNDLE_ID" >/dev/null
)

if [[ "$(uac_git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" != "$HEAD_SHA" ||
      -n "$(uac_git -C "$ROOT" status --porcelain=v1 --untracked-files=all)" ]]; then
  fail "Simulator live proof invalidated the clean exact-head checkout."
fi

echo "iOS Simulator sample is ready for visible exact-model interaction."
echo "provider=$PROVIDER"
echo "model_identity=seeded"
echo "head_sha=$HEAD_SHA"
