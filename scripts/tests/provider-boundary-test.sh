#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OPENAI_SOURCES="$ROOT/bridge/src/commonMain/kotlin/com/maneesh/universalai/connector/internal/provider/openai"
OPENROUTER_SOURCES="$ROOT/bridge/src/commonMain/kotlin/com/maneesh/universalai/connector/internal/provider/openrouter"
PROVIDER_IMPLEMENTATION_PATTERN='Open(Ai|Router)[A-Za-z0-9_]*(Wire|Adapter|Translator)'
scan_status=0

rg \
  --no-config \
  --line-number \
  "^(public[[:space:]]+)?(data[[:space:]]+class|class|object|interface|enum[[:space:]]+class)[[:space:]]+$PROVIDER_IMPLEMENTATION_PATTERN" \
  "$OPENAI_SOURCES" \
  "$OPENROUTER_SOURCES" || scan_status=$?
case "$scan_status" in
  0)
    echo "A provider implementation or DTO declaration is not internal." >&2
    exit 1
    ;;
  1)
    ;;
  *)
    echo "The OpenAI provider declaration audit could not complete (rg exit $scan_status)." >&2
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
    "internal\\.provider\\.(openai|openrouter)|$PROVIDER_IMPLEMENTATION_PATTERN" \
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
