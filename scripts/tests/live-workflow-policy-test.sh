#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/live.yml"

for forbidden in \
  'pull_request_target' \
  'secrets.' \
  'OPENAI_API_KEY' \
  'OPENAI_LIVE_MODEL' \
  'ANTHROPIC_API_KEY' \
  'ANTHROPIC_LIVE_MODEL' \
  'protected-openai-live' \
  'environment: live-provider' \
  './scripts/check-live.sh' \
  ':bridge:openAiLiveTest' \
  ':bridge:anthropicLiveTest'; do
  if grep -Fq "$forbidden" "$WORKFLOW"; then
    echo "Secretless live-policy workflow contains forbidden provider execution: $forbidden" >&2
    exit 1
  fi
done

for required in \
  'environment: live-policy' \
  'live_providers' \
  'live_providers=openai' \
  'live_providers=none' \
  'none | openai | anthropic | openai,anthropic' \
  'Local live verification: passed' \
  'No credential or provider response body retained.' \
  'GitHub did not execute provider tests or receive provider credentials.'; do
  if ! grep -Fq "$required" "$WORKFLOW"; then
    echo "Secretless live-policy workflow omitted required evidence policy: $required" >&2
    exit 1
  fi
done

echo "Secretless live-workflow policy regression tests passed."
