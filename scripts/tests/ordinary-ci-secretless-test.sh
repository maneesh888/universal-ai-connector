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
  'OPENROUTER_API_KEY' \
  'OPENROUTER_LIVE_MODEL' \
  'GATEWAY_LIVE_BASE_URL' \
  'GATEWAY_API_KEY' \
  'GATEWAY_LIVE_MODEL' \
  'GATEWAY_LIVE_STRUCTURED_OUTPUT' \
  '.env.live' \
  './scripts/check-live.sh' \
  ':bridge:openAiLiveTest' \
  ':bridge:anthropicLiveTest' \
  ':bridge:openRouterLiveTest' \
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
  'Classify verification impact' \
  'git show "$base_sha:scripts/verification-impact.sh"' \
  'full_required=false' \
  "needs.impact.outputs.full_required == 'true'" \
  'Documentation-only verification expected every heavy job to be skipped.' \
  './scripts/check.sh --full'; do
  if ! grep -Fq "$required" "$WORKFLOW"; then
    echo "Ordinary CI omitted a required read-only deterministic policy: $required" >&2
    exit 1
  fi
done

heavy_job_conditions="$(grep -Fc "needs.impact.outputs.full_required == 'true'" "$WORKFLOW")"
if [[ "$heavy_job_conditions" -ne 3 ]]; then
  echo "Every heavy ordinary-CI job must be gated by the trusted verification impact." >&2
  exit 1
fi

AGGREGATOR_SCRIPT="$(mktemp)"
cleanup() {
  rm -f "$AGGREGATOR_SCRIPT"
}
trap cleanup EXIT
awk '
  $0 == "      - name: Confirm required jobs passed" { found = 1; next }
  found && $0 == "        run: |" { capture = 1; next }
  capture && /^          / { sub(/^          /, ""); print; next }
  capture && $0 == "" { print; next }
  capture { exit }
' "$WORKFLOW" > "$AGGREGATOR_SCRIPT"

run_aggregator() {
  env \
    IMPACT_RESULT="$1" \
    HYGIENE_RESULT="$2" \
    FULL_REQUIRED="$3" \
    JVM_ANDROID_LINUX_RESULT="$4" \
    JVM_WINDOWS_RESULT="$5" \
    APPLE_JVM_RESULT="$6" \
    bash "$AGGREGATOR_SCRIPT" >/dev/null
}

run_aggregator success success false skipped skipped skipped
run_aggregator success success true success success success
if run_aggregator success success true skipped skipped skipped; then
  echo "The required-check aggregator accepted skipped heavy jobs when full verification was required." >&2
  exit 1
fi
if run_aggregator success success false success skipped skipped; then
  echo "The required-check aggregator accepted a partial documentation-only heavy-job run." >&2
  exit 1
fi

setup_gradle_steps="$(grep -Fc 'uses: gradle/actions/setup-gradle@' "$WORKFLOW")"
wrapper_validation_settings="$(grep -Fc 'validate-wrappers: true' "$WORKFLOW")"
if [[ "$setup_gradle_steps" -eq 0 || "$wrapper_validation_settings" -ne "$setup_gradle_steps" ]]; then
  echo "Every setup-gradle step must explicitly enable Gradle wrapper JAR validation." >&2
  exit 1
fi

echo "Ordinary CI remains read-only, deterministic, and secretless."
