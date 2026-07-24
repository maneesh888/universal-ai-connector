#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:---all}"
CONTRACTS_ROOT="$ROOT/contracts"
SCHEMAS_ROOT="$CONTRACTS_ROOT/schemas/v1"
FIXTURES_ROOT="$CONTRACTS_ROOT/fixtures/v1"
MANIFEST="$CONTRACTS_ROOT/fixture-manifest.json"

usage() {
  cat <<'EOF'
Usage: ./scripts/check-contracts.sh [--all|--layout-only]

  --all          Validate tracked contract layout and run the JVM, Android-host,
                 and iOS Simulator contract suites. This is the default.
  --layout-only  Validate deterministic repository layout without invoking Gradle.
EOF
}

verify_layout() {
  local required_path
  local json_file
  local schema_count
  local fixture_count
  local final_byte
  local tracked_generated

  for required_path in \
    "$CONTRACTS_ROOT/README.md" \
    "$SCHEMAS_ROOT" \
    "$FIXTURES_ROOT" \
    "$MANIFEST"; do
    if [[ ! -e "$required_path" ]]; then
      echo "Required contract path is missing: $required_path" >&2
      return 1
    fi
  done

  schema_count="$(find "$SCHEMAS_ROOT" -type f -name '*.schema.json' | wc -l | tr -d ' ')"
  fixture_count="$(find "$FIXTURES_ROOT" -type f -name '*.json' | wc -l | tr -d ' ')"
  if (( schema_count == 0 )); then
    echo "At least one authoritative contract schema is required." >&2
    return 1
  fi
  if (( fixture_count == 0 )); then
    echo "At least one contract fixture is required." >&2
    return 1
  fi

  while IFS= read -r -d '' json_file; do
    if [[ ! -s "$json_file" ]]; then
      echo "Contract JSON file must not be empty: $json_file" >&2
      return 1
    fi
    final_byte="$(tail -c 1 "$json_file" | od -An -tuC | tr -d '[:space:]')"
    if [[ "$final_byte" != "10" ]]; then
      echo "Contract JSON file must end with a newline: $json_file" >&2
      return 1
    fi
  done < <(find "$CONTRACTS_ROOT" -type f -name '*.json' -print0)

  tracked_generated="$(
    git -C "$ROOT" ls-files 'contracts/generated/**' 'contracts/**/generated/**'
  )"
  if [[ -n "$tracked_generated" ]]; then
    echo "Generated contract output must remain untracked." >&2
    return 1
  fi

  echo "Contract repository layout passed."
}

verify_provider_neutral_public_surfaces() {
  local prohibited_vendor_pattern
  local scan_status
  local supported_surface_paths=(
    "$ROOT/bridge/src/commonMain/kotlin/com/maneesh/universalai/connector/contract"
    "$ROOT/bridge/src/commonMain/kotlin/com/maneesh/universalai/connector/UniversalAiConnector.kt"
    "$ROOT/swift-package/Sources/UniversalAiConnector/UniversalAiConnector.swift"
    "$ROOT/swift-package/Sources/UniversalAiConnector/UniversalAiConnectorModels.swift"
  )

  if ! command -v rg >/dev/null 2>&1; then
    echo "Provider-neutral public-surface audit requires rg." >&2
    return 1
  fi

  prohibited_vendor_pattern="$(
    printf '%s' \
      'openai|anthropic|openrouter|claude|gemini|vertex[ _-]?ai|' \
      'bedrock|mistral|cohere|groq|gateway'
  )"
  if rg \
    --no-config \
    --no-ignore \
    --hidden \
    --line-number \
    --ignore-case \
    --glob '*.kt' \
    --glob '*.swift' \
    "$prohibited_vendor_pattern" \
    "${supported_surface_paths[@]}"; then
    echo "A provider or vendor name leaked into a supported canonical public surface." >&2
    return 1
  else
    scan_status=$?
    if (( scan_status != 1 )); then
      echo "Could not audit supported canonical public surfaces." >&2
      return 1
    fi
  fi

  echo "Provider-neutral public-surface audit passed."
}

case "$MODE" in
  --all|all)
    verify_layout
    verify_provider_neutral_public_surfaces
    "$ROOT/gradlew" \
      :bridge:jvmTest \
      :bridge:testAndroidHostTest \
      :bridge:iosSimulatorArm64Test
    echo "Universal AI Connector contract checks passed."
    ;;
  --layout-only|layout-only)
    verify_layout
    verify_provider_neutral_public_surfaces
    ;;
  --help|-h|help)
    usage
    ;;
  *)
    echo "Unknown contract-check mode: $MODE" >&2
    usage >&2
    exit 2
    ;;
esac
