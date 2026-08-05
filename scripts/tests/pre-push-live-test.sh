#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
unset \
  GIT_INDEX_FILE \
  GIT_OBJECT_DIRECTORY \
  GIT_ALTERNATE_OBJECT_DIRECTORIES \
  GIT_WORK_TREE \
  GIT_DIR
TEST_DIRECTORY="$(mktemp -d)"
TEST_REPOSITORY="$TEST_DIRECTORY/repository"
CALL_LOG="$TEST_DIRECTORY/calls.log"
OUTPUT="$TEST_DIRECTORY/output.log"

cleanup() {
  rm -rf "$TEST_DIRECTORY"
}
trap cleanup EXIT

mkdir -p \
  "$TEST_REPOSITORY/.githooks" \
  "$TEST_REPOSITORY/scripts" \
  "$TEST_REPOSITORY/bridge/src/commonMain/kotlin"
cp "$ROOT/.githooks/pre-push" "$TEST_REPOSITORY/.githooks/pre-push"
cp "$ROOT/scripts/live-impact.sh" "$TEST_REPOSITORY/scripts/live-impact.sh"
chmod +x \
  "$TEST_REPOSITORY/.githooks/pre-push" \
  "$TEST_REPOSITORY/scripts/live-impact.sh"

cat > "$TEST_REPOSITORY/scripts/check.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" != "--full" ]]; then
  echo "Pre-push did not request the full deterministic gate." >&2
  exit 10
fi
echo "full" >> "$UAC_TEST_CALL_LOG"
EOF

cat > "$TEST_REPOSITORY/scripts/check-live.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" != "openai" ||
      "${UAC_LIVE_EXPECTED_SHA:-}" != "$(git rev-parse HEAD)" ]]; then
  echo "Pre-push did not exact-head bind the OpenAI live gate." >&2
  exit 11
fi
echo "live" >> "$UAC_TEST_CALL_LOG"
EOF
chmod +x "$TEST_REPOSITORY/scripts/check.sh" "$TEST_REPOSITORY/scripts/check-live.sh"

git -C "$TEST_REPOSITORY" init -q
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Pre-push Live Test" \
  -c user.email="pre-push-live@example.invalid" \
  commit -qm "baseline"
BASE_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"
git -C "$TEST_REPOSITORY" update-ref refs/remotes/origin/main "$BASE_SHA"

run_hook() {
  local head_sha="$1"
  (
    cd "$TEST_REPOSITORY"
    printf '%s %s %s %s\n' \
      refs/heads/test \
      "$head_sha" \
      refs/heads/test \
      0000000000000000000000000000000000000000 |
      UAC_TEST_CALL_LOG="$CALL_LOG" ./.githooks/pre-push
  ) > "$OUTPUT" 2>&1
}

printf '%s\n' "documentation" > "$TEST_REPOSITORY/README.md"
git -C "$TEST_REPOSITORY" add README.md
git -C "$TEST_REPOSITORY" \
  -c user.name="Pre-push Live Test" \
  -c user.email="pre-push-live@example.invalid" \
  commit -qm "documentation"
DOCS_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

: > "$CALL_LOG"
run_hook "$DOCS_SHA"
if [[ "$(sed -n '1p' "$CALL_LOG")" != "full" ||
      -n "$(sed -n '2p' "$CALL_LOG")" ]]; then
  echo "Documentation-only push unexpectedly ran live verification." >&2
  exit 1
fi

printf '%s\n' "provider behavior" > \
  "$TEST_REPOSITORY/bridge/src/commonMain/kotlin/ProviderBehavior.kt"
git -C "$TEST_REPOSITORY" add bridge/src/commonMain/kotlin/ProviderBehavior.kt
git -C "$TEST_REPOSITORY" \
  -c user.name="Pre-push Live Test" \
  -c user.email="pre-push-live@example.invalid" \
  commit -qm "provider behavior"
PROVIDER_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

: > "$CALL_LOG"
run_hook "$PROVIDER_SHA"
if [[ "$(sed -n '1p' "$CALL_LOG")" != "full" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live" ||
      -n "$(sed -n '3p' "$CALL_LOG")" ]]; then
  echo "Provider-impacting push did not run full then live verification exactly once." >&2
  exit 1
fi

if UAC_TEST_CALL_LOG="$CALL_LOG" \
  UAC_LIVE_BASE_REF=missing/base \
  run_hook "$PROVIDER_SHA"; then
  echo "Missing live base unexpectedly passed pre-push verification." >&2
  exit 1
fi
if ! grep -Fq "Provider-impact classification could not resolve missing/base." "$OUTPUT"; then
  echo "Missing live base did not fail with actionable guidance." >&2
  exit 1
fi

echo "Pre-push live-impact regression tests passed."
