#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIRECTORY="$(mktemp -d)"
trap 'rm -rf "$TEST_DIRECTORY"' EXIT

# shellcheck source=../xcframework-header-audit.sh
source "$ROOT/scripts/xcframework-header-audit.sh"

HEADER="$TEST_DIRECTORY/UniversalAiConnectorBridge.h"
MATCH_OUTPUT="$TEST_DIRECTORY/match.log"
OPENROUTER_MATCH_OUTPUT="$TEST_DIRECTORY/openrouter-match.log"
OPERATIONAL_ERROR_OUTPUT="$TEST_DIRECTORY/operational-error.log"
FAKE_PATH="$TEST_DIRECTORY/path"
printf '%s\n' '@interface UACBAppleConnectorBridge : NSObject' > "$HEADER"

uac_reject_xcframework_header_pattern \
  "$HEADER" \
  'ConnectorTransport' \
  "A transport implementation type leaked into the callback-bridge header."
uac_reject_xcframework_header_pattern \
  "$HEADER" \
  "$UAC_PROVIDER_IMPLEMENTATION_HEADER_PATTERN" \
  "A provider implementation type leaked into the callback-bridge header."

printf '%s\n' 'OpenAiResponseWire' >> "$HEADER"
provider_match_status=0
uac_reject_xcframework_header_pattern \
  "$HEADER" \
  "$UAC_PROVIDER_IMPLEMENTATION_HEADER_PATTERN" \
  "A provider implementation type leaked into the callback-bridge header." \
  > "$MATCH_OUTPUT" 2>&1 || provider_match_status=$?
if [[ "$provider_match_status" -ne 1 ]]; then
  echo "Expected the XCFramework header audit to reject an OpenAI wire DTO." >&2
  exit 1
fi

sed -i.bak '/OpenAiResponseWire/d' "$HEADER"
rm -f "$HEADER.bak"
printf '%s\n' 'OpenRouterChatCompletionWire' >> "$HEADER"
openrouter_match_status=0
uac_reject_xcframework_header_pattern \
  "$HEADER" \
  "$UAC_PROVIDER_IMPLEMENTATION_HEADER_PATTERN" \
  "An OpenRouter provider implementation leaked into the callback-bridge header." \
  > "$OPENROUTER_MATCH_OUTPUT" 2>&1 || openrouter_match_status=$?
if [[ "$openrouter_match_status" -ne 1 ]]; then
  echo "Expected the XCFramework header audit to reject an OpenRouter wire DTO." >&2
  exit 1
fi
if ! grep -Fq \
  "An OpenRouter provider implementation leaked into the callback-bridge header." \
  "$OPENROUTER_MATCH_OUTPUT"; then
  echo "XCFramework header audit did not report the OpenRouter wire DTO." >&2
  exit 1
fi
if ! grep -Fq \
  "A provider implementation type leaked into the callback-bridge header." \
  "$MATCH_OUTPUT"; then
  echo "XCFramework header audit did not report the OpenAI wire DTO." >&2
  exit 1
fi
sed -i.bak '/OpenRouterChatCompletionWire/d' "$HEADER"
rm -f "$HEADER.bak"

printf '%s\n' 'OpenAiCompatibleChatCompletionWire' >> "$HEADER"
provider_match_status=0
uac_reject_xcframework_header_pattern \
  "$HEADER" \
  "$UAC_PROVIDER_IMPLEMENTATION_HEADER_PATTERN" \
  "A generic provider implementation leaked into the callback-bridge header." \
  > "$MATCH_OUTPUT" 2>&1 || provider_match_status=$?
if [[ "$provider_match_status" -ne 1 ]]; then
  echo "Expected the XCFramework header audit to reject a generic wire DTO." >&2
  exit 1
fi
sed -i.bak '/OpenAiCompatibleChatCompletionWire/d' "$HEADER"
rm -f "$HEADER.bak"

printf '%s\n' 'AnthropicMessageResponseWire' >> "$HEADER"
provider_match_status=0
uac_reject_xcframework_header_pattern \
  "$HEADER" \
  "$UAC_PROVIDER_IMPLEMENTATION_HEADER_PATTERN" \
  "A provider implementation type leaked into the callback-bridge header." \
  > "$MATCH_OUTPUT" 2>&1 || provider_match_status=$?
if [[ "$provider_match_status" -ne 1 ]]; then
  echo "Expected the XCFramework header audit to reject an Anthropic wire DTO." >&2
  exit 1
fi
if ! grep -Fq \
  "A provider implementation type leaked into the callback-bridge header." \
  "$MATCH_OUTPUT"; then
  echo "XCFramework header audit did not report the Anthropic wire DTO." >&2
  exit 1
fi
sed -i.bak '/AnthropicMessageResponseWire/d' "$HEADER"
rm -f "$HEADER.bak"

printf '%s\n' 'ConnectorTransport' >> "$HEADER"
match_status=0
uac_reject_xcframework_header_pattern \
  "$HEADER" \
  'ConnectorTransport' \
  "A transport implementation type leaked into the callback-bridge header." \
  > "$MATCH_OUTPUT" 2>&1 || match_status=$?
if [[ "$match_status" -ne 1 ]]; then
  echo "Expected the XCFramework header audit to reject a matching implementation type." >&2
  exit 1
fi
if ! grep -Fq \
  "A transport implementation type leaked into the callback-bridge header." \
  "$MATCH_OUTPUT"; then
  echo "XCFramework header audit did not report the matching implementation type." >&2
  exit 1
fi

mkdir -p "$FAKE_PATH"
printf '%s\n' '#!/bin/sh' 'exit 7' > "$FAKE_PATH/grep"
chmod +x "$FAKE_PATH/grep"

operational_error_status=0
PATH="$FAKE_PATH" \
  uac_reject_xcframework_header_pattern \
    "$HEADER" \
    'MissingPattern' \
    "An implementation type leaked into the callback-bridge header." \
    > "$OPERATIONAL_ERROR_OUTPUT" 2>&1 || operational_error_status=$?
if [[ "$operational_error_status" -ne 7 ]]; then
  echo "Expected the XCFramework header audit to preserve an operational grep error." >&2
  exit 1
fi
if ! grep -Fq \
  "XCFramework header scan could not complete for $HEADER (grep exit 7)." \
  "$OPERATIONAL_ERROR_OUTPUT"; then
  echo "XCFramework header audit did not report the operational grep error." >&2
  exit 1
fi

echo "XCFramework header audit regression tests passed."
