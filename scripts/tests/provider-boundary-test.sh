#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROVIDER_SOURCES="$ROOT/bridge/src/commonMain/kotlin/com/maneesh/universalai/connector/internal/provider"
PROVIDER_IMPLEMENTATION_PATTERN='(OpenAi|Anthropic|OpenRouter)[A-Za-z0-9_]*(Wire|Adapter|Translator)'
scan_status=0

rg \
  --no-config \
  --line-number \
  "^(public[[:space:]]+)?(data[[:space:]]+class|class|object|interface|enum[[:space:]]+class)[[:space:]]+$PROVIDER_IMPLEMENTATION_PATTERN" \
  "$PROVIDER_SOURCES/openai" \
  "$PROVIDER_SOURCES/anthropic" \
  "$PROVIDER_SOURCES/openrouter" \
  "$PROVIDER_SOURCES/openaicompatible" || scan_status=$?
case "$scan_status" in
  0)
    echo "A provider implementation or DTO declaration is not internal." >&2
    exit 1
    ;;
  1)
    ;;
  *)
    echo "The provider declaration audit could not complete (rg exit $scan_status)." >&2
    exit "$scan_status"
    ;;
esac

for supported_surface in \
  "$ROOT/bridge/src/commonMain/kotlin/com/maneesh/universalai/connector" \
  "$ROOT/bridge/src/iosMain/kotlin/com/maneesh/universalai/apple" \
  "$ROOT/swift-package/Sources" \
  "$ROOT/samples"; do
  scan_status=0
  rg \
    --no-config \
    --line-number \
    --glob '!**/internal/**' \
    --glob '!**/src/test/**' \
    --glob '!**/Tests/**' \
    "internal\\.provider\\.(openai|anthropic|openrouter|openaicompatible)|$PROVIDER_IMPLEMENTATION_PATTERN" \
    "$supported_surface" || scan_status=$?
  case "$scan_status" in
    0)
      echo "A provider implementation or DTO leaked into a supported host surface." >&2
      exit 1
      ;;
    1)
      ;;
    *)
      echo "The supported-host provider boundary audit could not complete (rg exit $scan_status)." >&2
      exit "$scan_status"
      ;;
  esac
done

echo "Provider source and supported-host boundary audits passed."
