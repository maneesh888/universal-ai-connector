#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
unset \
  GIT_ALTERNATE_OBJECT_DIRECTORIES \
  GIT_DIR \
  GIT_INDEX_FILE \
  GIT_OBJECT_DIRECTORY \
  GIT_WORK_TREE \
  OPENAI_API_KEY \
  OPENAI_LIVE_MODEL \
  ANTHROPIC_API_KEY \
  ANTHROPIC_LIVE_MODEL \
  OPENROUTER_API_KEY \
  OPENROUTER_LIVE_MODEL \
  GATEWAY_LIVE_BASE_URL \
  GATEWAY_API_KEY \
  GATEWAY_LIVE_MODEL \
  GATEWAY_LIVE_STRUCTURED_OUTPUT \
  UAC_LIVE_ENV_FILE \
  UAC_LIVE_EXPECTED_SHA \
  UAC_IOS_SAMPLE_LIVE_PROOF \
  UAC_IOS_SAMPLE_PROOF_CREDENTIAL \
  UAC_IOS_SAMPLE_PROOF_MODEL \
  UAC_IOS_SAMPLE_PROOF_BASE_URL \
  UAC_SKIP_XCFRAMEWORK_BUILD \
  SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_BOOTSTRAP \
  SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_PROVIDER \
  SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_CREDENTIAL \
  SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_MODEL \
  SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_BASE_URL

TEST_DIRECTORY="$(mktemp -d)"
TEST_REPOSITORY="$TEST_DIRECTORY/repository"
POISON_REPOSITORY="$TEST_DIRECTORY/foreign-repository"
FAKE_BIN="$TEST_DIRECTORY/bin"
DERIVED_DATA="$TEST_DIRECTORY/derived"
CALL_LOG="$TEST_DIRECTORY/calls.log"
OUTPUT="$TEST_DIRECTORY/output.log"
CREDENTIAL="simulator-proof-credential-that-must-not-appear"
MODEL="simulator-proof-model"
DEVICE_ID="00000000-0000-0000-0000-000000000000"

cleanup() {
  rm -rf -- "$TEST_DIRECTORY"
}
trap cleanup EXIT

mkdir -p "$TEST_REPOSITORY/scripts" "$FAKE_BIN"
cp "$ROOT/scripts/launch-ios-live-sample.sh" "$TEST_REPOSITORY/scripts/"
cp "$ROOT/scripts/local-config.sh" "$TEST_REPOSITORY/scripts/"

cat > "$TEST_REPOSITORY/scripts/simulator-destination.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
echo "platform=iOS Simulator,id=$DEVICE_ID"
EOF

cat > "$TEST_REPOSITORY/scripts/build-sample.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${UAC_SKIP_XCFRAMEWORK_BUILD:-}" ||
      -n "${UAC_IOS_SAMPLE_PROOF_CREDENTIAL:-}" ||
      -n "${UAC_IOS_SAMPLE_PROOF_MODEL:-}" ||
      -n "${UAC_IOS_SAMPLE_PROOF_BASE_URL:-}" ||
      -n "${PROOF_CREDENTIAL:-}" ||
      -n "${PROOF_MODEL:-}" ||
      -n "${PROOF_BASE_URL:-}" ||
      -n "${OPENAI_API_KEY:-}" ||
      -n "${OPENAI_LIVE_MODEL:-}" ||
      -n "${UAC_LIVE_ENV_FILE:-}" ]]; then
  echo "Simulator build inherited proof or stale-artifact inputs." >&2
  exit 20
fi
mkdir -p \
  "$UAC_SAMPLE_DERIVED_DATA/Build/Products/Debug-iphonesimulator/UniversalAiConnectorSample.app"
echo build >> "$UAC_TEST_CALL_LOG"
EOF

cat > "$FAKE_BIN/xcrun" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$*" == "simctl boot "* || "$*" == "simctl bootstatus "* ]]; then
  exit 0
fi
if [[ "$*" == "simctl install "* ]]; then
  echo install >> "$UAC_TEST_CALL_LOG"
  exit 0
fi
if [[ "$*" == "simctl launch --terminate-running-process "* ]]; then
  if [[ "${SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_BOOTSTRAP:-}" != "1" ||
        "${SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_PROVIDER:-}" != "openai" ||
        "${SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_CREDENTIAL:-}" != "$UAC_TEST_EXPECTED_CREDENTIAL" ||
        "${SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_MODEL:-}" != "$UAC_TEST_EXPECTED_MODEL" ||
        -n "${SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_BASE_URL:-}" ]]; then
    echo "Simulator launch inherited or lost proof seed values." >&2
    exit 21
  fi
  echo launch >> "$UAC_TEST_CALL_LOG"
  exit 0
fi

echo "Unexpected xcrun invocation." >&2
exit 22
EOF

chmod +x \
  "$TEST_REPOSITORY/scripts/launch-ios-live-sample.sh" \
  "$TEST_REPOSITORY/scripts/local-config.sh" \
  "$TEST_REPOSITORY/scripts/simulator-destination.sh" \
  "$TEST_REPOSITORY/scripts/build-sample.sh" \
  "$FAKE_BIN/xcrun"

git -C "$TEST_REPOSITORY" init -q
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Simulator Launcher Test" \
  -c user.email="simulator-launcher@example.invalid" \
  commit -qm "test fixture"
HEAD_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

mkdir -p "$POISON_REPOSITORY"
git -C "$POISON_REPOSITORY" init -q
printf '%s\n' foreign > "$POISON_REPOSITORY/foreign.txt"
git -C "$POISON_REPOSITORY" add foreign.txt
git -C "$POISON_REPOSITORY" \
  -c user.name="Simulator Launcher Test" \
  -c user.email="simulator-launcher@example.invalid" \
  commit -qm "foreign fixture"

env \
  PATH="$FAKE_BIN:$PATH" \
  GIT_DIR="$POISON_REPOSITORY/.git" \
  GIT_INDEX_FILE="$POISON_REPOSITORY/.git/index" \
  GIT_WORK_TREE="$POISON_REPOSITORY" \
  UAC_LIVE_EXPECTED_SHA="$HEAD_SHA" \
  UAC_IOS_LIVE_SAMPLE_DERIVED_DATA="$DERIVED_DATA" \
  UAC_IOS_SAMPLE_PROOF_CREDENTIAL="$CREDENTIAL" \
  UAC_IOS_SAMPLE_PROOF_MODEL="$MODEL" \
  PROOF_CREDENTIAL="ambient-exported-proof-credential-alias" \
  PROOF_MODEL="ambient-exported-proof-model-alias" \
  PROOF_BASE_URL="ambient-exported-proof-base-url-alias" \
  UAC_SKIP_XCFRAMEWORK_BUILD=1 \
  SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_BOOTSTRAP=ambient \
  SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_PROVIDER=ambient \
  SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_CREDENTIAL=ambient \
  SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_MODEL=ambient \
  SIMCTL_CHILD_UAC_IOS_SAMPLE_LIVE_BASE_URL=https://unintended.example/v1 \
  UAC_TEST_CALL_LOG="$CALL_LOG" \
  UAC_TEST_EXPECTED_CREDENTIAL="$CREDENTIAL" \
  UAC_TEST_EXPECTED_MODEL="$MODEL" \
  "$TEST_REPOSITORY/scripts/launch-ios-live-sample.sh" openai \
  > "$OUTPUT" 2>&1

if [[ "$(sed -n '1p' "$CALL_LOG")" != "build" ||
      "$(sed -n '2p' "$CALL_LOG")" != "install" ||
      "$(sed -n '3p' "$CALL_LOG")" != "launch" ||
      -n "$(sed -n '4p' "$CALL_LOG")" ]]; then
  echo "Simulator launcher did not rebuild, install, and launch exactly once." >&2
  exit 1
fi
if grep -Fq "$CREDENTIAL" "$OUTPUT" || grep -Fq "$MODEL" "$OUTPUT"; then
  echo "Simulator launcher output exposed proof seed material." >&2
  exit 1
fi
if ! grep -Fq "head_sha=$HEAD_SHA" "$OUTPUT"; then
  echo "Simulator launcher did not retain exact-head evidence." >&2
  exit 1
fi

echo "iOS Simulator live-sample launcher tests passed."
