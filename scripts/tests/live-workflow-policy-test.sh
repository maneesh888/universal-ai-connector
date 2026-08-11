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
  'OPENROUTER_API_KEY' \
  'OPENROUTER_LIVE_MODEL' \
  'GATEWAY_LIVE_BASE_URL' \
  'GATEWAY_API_KEY' \
  'GATEWAY_LIVE_MODEL' \
  'GATEWAY_LIVE_STRUCTURED_OUTPUT' \
  'protected-openai-live' \
  'environment: live-provider' \
  './scripts/check-live.sh' \
  ':bridge:openAiLiveTest' \
  ':bridge:anthropicLiveTest' \
  ':bridge:openRouterLiveTest' \
  ':bridge:gatewayLiveTest'; do
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
  'none | openai | anthropic | openrouter | gateway | \' \
  'openai,anthropic | openai,openrouter | openai,gateway | \' \
  'anthropic,openrouter | anthropic,gateway | openrouter,gateway | \' \
  'openai,anthropic,openrouter | openai,anthropic,gateway | \' \
  'openai,openrouter,gateway | anthropic,openrouter,gateway | \' \
  'openai,anthropic,openrouter,gateway' \
  'Local live verification: passed' \
  'No credential or provider response body retained.' \
  'Trust boundary: local execution is contributor-attested; GitHub verifies retained exact-head evidence only.' \
  'Local execution remains contributor-attested.' \
  'GitHub verified retained exact-head evidence only and did not receive provider credentials.'; do
  if ! grep -Fq "$required" "$WORKFLOW"; then
    echo "Secretless live-policy workflow omitted required evidence policy: $required" >&2
    exit 1
  fi
done

echo "Secretless live-workflow policy regression tests passed."
