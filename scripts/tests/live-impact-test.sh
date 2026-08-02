#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIRECTORY="$(mktemp -d)"
TEST_REPOSITORY="$TEST_DIRECTORY/repository"
CLASSIFIER="$TEST_REPOSITORY/scripts/live-impact.sh"
OPENAI_DIRECTORY="$TEST_REPOSITORY/bridge/src/commonMain/kotlin/com/maneesh/universalai/connector/internal/provider/openai"

cleanup() {
  rm -rf "$TEST_DIRECTORY"
}
trap cleanup EXIT

mkdir -p "$TEST_REPOSITORY/scripts"
cp "$ROOT/scripts/live-impact.sh" "$CLASSIFIER"
chmod +x "$CLASSIFIER"

git -C "$TEST_REPOSITORY" init -q
printf '%s\n' "baseline" > "$TEST_REPOSITORY/README.md"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "baseline"
BASE_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

printf '%s\n' "foundation" > "$TEST_REPOSITORY/scripts/check-live.sh"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "gate foundation"
FOUNDATION_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$BASE_SHA" "$FOUNDATION_SHA")" != "false" ]]; then
  echo "Pre-adapter gate foundation must remain secretless." >&2
  exit 1
fi

mkdir -p "$OPENAI_DIRECTORY"
printf '%s\n' "internal adapter marker" > "$OPENAI_DIRECTORY/OpenAiResponsesAdapter.kt"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "add adapter"
ADAPTER_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$FOUNDATION_SHA" "$ADAPTER_SHA")" != "true" ]]; then
  echo "Adding the OpenAI adapter must require live verification." >&2
  exit 1
fi

printf '%s\n' "documentation" >> "$TEST_REPOSITORY/README.md"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "docs only"
DOCS_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$ADAPTER_SHA" "$DOCS_SHA")" != "false" ]]; then
  echo "Documentation-only changes must not require live credentials." >&2
  exit 1
fi

printf '%s\n' "rootProject.name = \"live-impact-fixture\"" > "$TEST_REPOSITORY/settings.gradle.kts"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "change live build infrastructure"
BUILD_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$DOCS_SHA" "$BUILD_SHA")" != "true" ]]; then
  echo "Changing build infrastructure with an active adapter must require live verification." >&2
  exit 1
fi

printf '%s\n' "runtime change" >> "$OPENAI_DIRECTORY/OpenAiResponsesAdapter.kt"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "change adapter"
RUNTIME_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$BUILD_SHA" "$RUNTIME_SHA")" != "true" ]]; then
  echo "Changing an active adapter must require live verification." >&2
  exit 1
fi

rm -rf "$OPENAI_DIRECTORY"
git -C "$TEST_REPOSITORY" add -A
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "remove adapter"
REMOVAL_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$RUNTIME_SHA" "$REMOVAL_SHA")" != "true" ]]; then
  echo "Removing an active adapter must require live verification." >&2
  exit 1
fi

if "$CLASSIFIER" invalid "$REMOVAL_SHA" >/dev/null 2>&1; then
  echo "Invalid revisions must fail closed." >&2
  exit 1
fi

echo "Live-impact classifier regression tests passed."
