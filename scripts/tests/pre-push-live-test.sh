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
awk '
  $0 == "DELIVERED_PROVIDERS=(\"openai\" \"anthropic\" \"openrouter\")" {
    print "DELIVERED_PROVIDERS=(\"openai\" \"anthropic\" \"openrouter\")"
    next
  }
  { print }
' "$ROOT/scripts/live-impact.sh" > "$TEST_REPOSITORY/scripts/live-impact.sh"
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
if [[ -n "${OPENAI_API_KEY:-}" ||
      -n "${OPENAI_LIVE_MODEL:-}" ||
      -n "${ANTHROPIC_API_KEY:-}" ||
      -n "${ANTHROPIC_LIVE_MODEL:-}" ||
      -n "${OPENROUTER_API_KEY:-}" ||
      -n "${OPENROUTER_LIVE_MODEL:-}" ||
      -n "${UAC_LIVE_EXPECTED_SHA:-}" ]]; then
  echo "Pre-push exposed live inputs to the deterministic gate." >&2
  exit 12
fi
echo "full" >> "$UAC_TEST_CALL_LOG"
EOF

cat > "$TEST_REPOSITORY/scripts/check-live.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${UAC_LIVE_EXPECTED_SHA:-}" != "$(git rev-parse HEAD)" ]]; then
  echo "Pre-push did not exact-head bind a selected live gate." >&2
  exit 11
fi
case "${1:-}" in
  openai)
    if [[ "${OPENAI_API_KEY:-}" != "synthetic-openai-key" ||
          "${OPENAI_LIVE_MODEL:-}" != "synthetic-openai-model" ||
          -n "${ANTHROPIC_API_KEY:-}" ||
          -n "${ANTHROPIC_LIVE_MODEL:-}" ||
          -n "${OPENROUTER_API_KEY:-}" ||
          -n "${OPENROUTER_LIVE_MODEL:-}" ]]; then
      echo "Pre-push did not isolate the OpenAI live gate." >&2
      exit 12
    fi
    ;;
  anthropic)
    if [[ "${ANTHROPIC_API_KEY:-}" != "synthetic-anthropic-key" ||
          "${ANTHROPIC_LIVE_MODEL:-}" != "synthetic-anthropic-model" ||
          -n "${OPENAI_API_KEY:-}" ||
          -n "${OPENAI_LIVE_MODEL:-}" ||
          -n "${OPENROUTER_API_KEY:-}" ||
          -n "${OPENROUTER_LIVE_MODEL:-}" ]]; then
      echo "Pre-push did not isolate the Anthropic live gate." >&2
      exit 13
    fi
    ;;
  openrouter)
    if [[ "${OPENROUTER_API_KEY:-}" != "synthetic-openrouter-key" ||
          "${OPENROUTER_LIVE_MODEL:-}" != "synthetic-openrouter-model" ||
          -n "${OPENAI_API_KEY:-}" ||
          -n "${OPENAI_LIVE_MODEL:-}" ||
          -n "${ANTHROPIC_API_KEY:-}" ||
          -n "${ANTHROPIC_LIVE_MODEL:-}" ]]; then
      echo "Pre-push did not isolate the OpenRouter live gate." >&2
      exit 14
    fi
    ;;
  *)
    echo "Pre-push selected an unsupported live gate." >&2
    exit 15
    ;;
esac
echo "live:$1" >> "$UAC_TEST_CALL_LOG"
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
      OPENAI_API_KEY="synthetic-openai-key" \
      OPENAI_LIVE_MODEL="synthetic-openai-model" \
      ANTHROPIC_API_KEY="synthetic-anthropic-key" \
      ANTHROPIC_LIVE_MODEL="synthetic-anthropic-model" \
      OPENROUTER_API_KEY="synthetic-openrouter-key" \
      OPENROUTER_LIVE_MODEL="synthetic-openrouter-model" \
      UAC_TEST_CALL_LOG="$CALL_LOG" \
      ./.githooks/pre-push
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

git -C "$TEST_REPOSITORY" update-ref refs/remotes/origin/main "$DOCS_SHA"
OPENAI_PATH="$TEST_REPOSITORY/bridge/src/commonMain/kotlin/com/example/internal/provider/openai"
mkdir -p "$OPENAI_PATH"
printf '%s\n' "OpenAI provider behavior" > "$OPENAI_PATH/OpenAiAdapter.kt"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Pre-push Live Test" \
  -c user.email="pre-push-live@example.invalid" \
  commit -qm "OpenAI provider behavior"
OPENAI_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

: > "$CALL_LOG"
run_hook "$OPENAI_SHA"
if [[ "$(sed -n '1p' "$CALL_LOG")" != "full" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live:openai" ||
      -n "$(sed -n '3p' "$CALL_LOG")" ]]; then
  echo "OpenAI-only push did not run full then the OpenAI gate exactly once." >&2
  exit 1
fi

git -C "$TEST_REPOSITORY" update-ref refs/remotes/origin/main "$OPENAI_SHA"
ANTHROPIC_PATH="$TEST_REPOSITORY/bridge/src/commonMain/kotlin/com/example/internal/provider/anthropic"
mkdir -p "$ANTHROPIC_PATH"
printf '%s\n' "Anthropic provider behavior" > "$ANTHROPIC_PATH/AnthropicAdapter.kt"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Pre-push Live Test" \
  -c user.email="pre-push-live@example.invalid" \
  commit -qm "Anthropic provider behavior"
ANTHROPIC_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

: > "$CALL_LOG"
run_hook "$ANTHROPIC_SHA"
if [[ "$(sed -n '1p' "$CALL_LOG")" != "full" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live:anthropic" ||
      -n "$(sed -n '3p' "$CALL_LOG")" ]]; then
  echo "Anthropic-only push did not run full then the Anthropic gate exactly once." >&2
  exit 1
fi

git -C "$TEST_REPOSITORY" update-ref refs/remotes/origin/main "$ANTHROPIC_SHA"
OPENROUTER_PATH="$TEST_REPOSITORY/bridge/src/commonMain/kotlin/com/example/internal/provider/openrouter"
mkdir -p "$OPENROUTER_PATH"
printf '%s\n' "OpenRouter provider behavior" > "$OPENROUTER_PATH/OpenRouterAdapter.kt"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Pre-push Live Test" \
  -c user.email="pre-push-live@example.invalid" \
  commit -qm "OpenRouter provider behavior"
OPENROUTER_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

: > "$CALL_LOG"
run_hook "$OPENROUTER_SHA"
if [[ "$(sed -n '1p' "$CALL_LOG")" != "full" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live:openrouter" ||
      -n "$(sed -n '3p' "$CALL_LOG")" ]]; then
  echo "OpenRouter-only push did not run full then the OpenRouter gate exactly once." >&2
  exit 1
fi

git -C "$TEST_REPOSITORY" update-ref refs/remotes/origin/main "$OPENROUTER_SHA"
OPENAI_COMPATIBLE_PATH="$TEST_REPOSITORY/bridge/src/commonMain/kotlin/com/example/internal/provider/openaicompatible"
mkdir -p "$OPENAI_COMPATIBLE_PATH"
printf '%s\n' "generic OpenAI-compatible behavior" > \
  "$OPENAI_COMPATIBLE_PATH/OpenAiCompatibleAdapter.kt"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Pre-push Live Test" \
  -c user.email="pre-push-live@example.invalid" \
  commit -qm "generic OpenAI-compatible behavior"
OPENAI_COMPATIBLE_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

: > "$CALL_LOG"
run_hook "$OPENAI_COMPATIBLE_SHA"
if [[ "$(sed -n '1p' "$CALL_LOG")" != "full" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live:openrouter" ||
      -n "$(sed -n '3p' "$CALL_LOG")" ]]; then
  echo "Generic-adapter push did not run full then the OpenRouter gate exactly once." >&2
  exit 1
fi

git -C "$TEST_REPOSITORY" update-ref refs/remotes/origin/main "$OPENAI_COMPATIBLE_SHA"
printf '%s\n' "shared provider behavior" > \
  "$TEST_REPOSITORY/bridge/src/commonMain/kotlin/SharedBehavior.kt"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Pre-push Live Test" \
  -c user.email="pre-push-live@example.invalid" \
  commit -qm "shared provider behavior"
SHARED_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

: > "$CALL_LOG"
run_hook "$SHARED_SHA"
if [[ "$(sed -n '1p' "$CALL_LOG")" != "full" ||
      "$(sed -n '2p' "$CALL_LOG")" != "live:openai" ||
      "$(sed -n '3p' "$CALL_LOG")" != "live:anthropic" ||
      "$(sed -n '4p' "$CALL_LOG")" != "live:openrouter" ||
      -n "$(sed -n '5p' "$CALL_LOG")" ]]; then
  echo "Shared push did not run full then every selected provider gate." >&2
  exit 1
fi

if UAC_TEST_CALL_LOG="$CALL_LOG" \
  UAC_LIVE_BASE_REF=missing/base \
  run_hook "$SHARED_SHA"; then
  echo "Missing live base unexpectedly passed pre-push verification." >&2
  exit 1
fi
if ! grep -Fq "Provider-impact classification could not resolve missing/base." "$OUTPUT"; then
  echo "Missing live base did not fail with actionable guidance." >&2
  exit 1
fi

echo "Pre-push live-impact regression tests passed."
