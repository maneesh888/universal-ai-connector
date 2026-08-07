#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/ci.yml"

for forbidden in \
  'pull_request_target' \
  'secrets.' \
  'OPENAI_API_KEY' \
  'OPENAI_LIVE_MODEL' \
  'ANTHROPIC_API_KEY' \
  'ANTHROPIC_LIVE_MODEL' \
  '.env.live' \
  './scripts/check-live.sh' \
  ':bridge:openAiLiveTest' \
  ':bridge:anthropicLiveTest' \
  'environment: live-provider' \
  'environment: live-policy'; do
  if grep -Fq "$forbidden" "$WORKFLOW"; then
    echo "Ordinary CI contains a provider credential, live input, or live execution path: $forbidden" >&2
    exit 1
  fi
done

for required in \
  'permissions:' \
  'contents: read' \
  './scripts/check.sh --full'; do
  if ! grep -Fq "$required" "$WORKFLOW"; then
    echo "Ordinary CI omitted a required read-only deterministic policy: $required" >&2
    exit 1
  fi
done

echo "Ordinary CI remains read-only, deterministic, and secretless."
