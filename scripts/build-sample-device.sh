#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVICE_DERIVED_DATA="${UAC_SAMPLE_DEVICE_DERIVED_DATA:-${TMPDIR:-/tmp}/universal-ai-connector-sample-device-derived}"

UAC_SAMPLE_DESTINATION="generic/platform=iOS" \
  UAC_SAMPLE_DERIVED_DATA="$DEVICE_DERIVED_DATA" \
  "$ROOT/scripts/build-sample.sh"
