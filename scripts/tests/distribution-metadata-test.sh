#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECKER="$ROOT/scripts/check-distribution-metadata.sh"
TEST_DIRECTORY="$(mktemp -d)"
trap 'rm -rf "$TEST_DIRECTORY"' EXIT

if ! "$CHECKER" >/dev/null; then
  echo "The repository distribution contract must pass its metadata check." >&2
  exit 1
fi

copy_fixture() {
  local destination="$1"

  mkdir -p "$destination"
  git -C "$ROOT" ls-files --cached --others --exclude-standard -z |
    while IFS= read -r -d '' tracked_file; do
    mkdir -p "$destination/$(dirname "$tracked_file")"
    cp "$ROOT/$tracked_file" "$destination/$tracked_file"
  done
}

run_fixture_checker() {
  local fixture_root="$1"

  "$fixture_root/scripts/check-distribution-metadata.sh" >/dev/null 2>&1
}

VERSION_DRIFT="$TEST_DIRECTORY/version-drift"
copy_fixture "$VERSION_DRIFT"
sed -i.bak 's/^VERSION_NAME=.*/VERSION_NAME=0.1.0-alpha.1/' "$VERSION_DRIFT/gradle.properties"
rm "$VERSION_DRIFT/gradle.properties.bak"
if run_fixture_checker "$VERSION_DRIFT"; then
  echo "The distribution check must reject a P8 candidate that does not precede alpha.1." >&2
  exit 1
fi

ASSET_DRIFT="$TEST_DIRECTORY/asset-drift"
copy_fixture "$ASSET_DRIFT"
sed -i.bak 's/^APPLE_ASSET_NAME=.*/APPLE_ASSET_NAME=mutable.zip/' "$ASSET_DRIFT/distribution/release.properties"
rm "$ASSET_DRIFT/distribution/release.properties.bak"
if run_fixture_checker "$ASSET_DRIFT"; then
  echo "The distribution check must reject release-asset naming drift." >&2
  exit 1
fi

SECRET_METADATA="$TEST_DIRECTORY/secret-metadata"
copy_fixture "$SECRET_METADATA"
printf '%s\n' 'CENTRAL_TOKEN=do-not-retain-this-value' >> "$SECRET_METADATA/distribution/release.properties"
if run_fixture_checker "$SECRET_METADATA"; then
  echo "The distribution check must reject credential-bearing public metadata." >&2
  exit 1
fi

SOURCE_DRIFT="$TEST_DIRECTORY/source-drift"
copy_fixture "$SOURCE_DRIFT"
sed -i.bak \
  's/const val LIBRARY_VERSION: String = UNIVERSAL_AI_CONNECTOR_VERSION/const val LIBRARY_VERSION: String = "0.1.0-alpha.1"/' \
  "$SOURCE_DRIFT/bridge/src/commonMain/kotlin/com/myadidi/universalai/connector/UniversalAiConnector.kt"
rm "$SOURCE_DRIFT/bridge/src/commonMain/kotlin/com/myadidi/universalai/connector/UniversalAiConnector.kt.bak"
if run_fixture_checker "$SOURCE_DRIFT"; then
  echo "The distribution check must reject a hard-coded production version." >&2
  exit 1
fi

NAMESPACE_DRIFT="$TEST_DIRECTORY/namespace-drift"
copy_fixture "$NAMESPACE_DRIFT"
python3 - "$NAMESPACE_DRIFT/distribution/namespace-verification.json" <<'NAMESPACE_PY'
import json, sys
from pathlib import Path
p = Path(sys.argv[1]); record = json.loads(p.read_text()); record["status"] = "pending"
p.write_text(json.dumps(record))
NAMESPACE_PY
if run_fixture_checker "$NAMESPACE_DRIFT"; then
  echo "The distribution check must reject unverified namespace evidence." >&2
  exit 1
fi

PLAN_COORDINATE_DRIFT="$TEST_DIRECTORY/plan-coordinate-drift"
copy_fixture "$PLAN_COORDINATE_DRIFT"
sed -i.bak \
  's/com.myadidi:universal-ai-connector:<version>/io.github.maneesh888:universal-ai-connector:<version>/' \
  "$PLAN_COORDINATE_DRIFT/docs/plans/production-distribution-host-integration.md"
rm "$PLAN_COORDINATE_DRIFT/docs/plans/production-distribution-host-integration.md.bak"
if run_fixture_checker "$PLAN_COORDINATE_DRIFT"; then
  echo "The distribution check must reject active-plan Maven coordinate drift." >&2
  exit 1
fi

echo "Distribution metadata regression checks passed."
