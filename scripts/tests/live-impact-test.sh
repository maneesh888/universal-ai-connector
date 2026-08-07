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
CLASSIFIER="$TEST_REPOSITORY/scripts/live-impact.sh"
MULTI_PROVIDER_CLASSIFIER="$TEST_REPOSITORY/scripts/live-impact-multi-provider.sh"
DUPLICATE_PROVIDER_CLASSIFIER="$TEST_REPOSITORY/scripts/live-impact-duplicate-provider.sh"
PROVIDER_DIRECTORY="$TEST_REPOSITORY/bridge/src/commonMain/kotlin/com/maneesh/universalai/connector/internal/provider"
OPENAI_DIRECTORY="$PROVIDER_DIRECTORY/openai"
ANTHROPIC_DIRECTORY="$PROVIDER_DIRECTORY/anthropic"
OPENROUTER_DIRECTORY="$PROVIDER_DIRECTORY/openrouter"

cleanup() {
  rm -rf "$TEST_DIRECTORY"
}
trap cleanup EXIT

mkdir -p "$TEST_REPOSITORY/scripts"
cp "$ROOT/scripts/live-impact.sh" "$CLASSIFIER"
awk '
  $0 == "DELIVERED_PROVIDERS=(\"openai\" \"anthropic\" \"openrouter\")" {
    print "DELIVERED_PROVIDERS=(\"openai\" \"anthropic\" \"openrouter\")"
    next
  }
  { print }
' "$CLASSIFIER" > "$MULTI_PROVIDER_CLASSIFIER"
awk '
  $0 == "DELIVERED_PROVIDERS=(\"openai\" \"anthropic\" \"openrouter\")" {
    print "DELIVERED_PROVIDERS=(\"openai\" \"openai\" \"anthropic\" \"openrouter\")"
    next
  }
  { print }
' "$CLASSIFIER" > "$DUPLICATE_PROVIDER_CLASSIFIER"
chmod +x \
  "$CLASSIFIER" \
  "$MULTI_PROVIDER_CLASSIFIER" \
  "$DUPLICATE_PROVIDER_CLASSIFIER"

git -C "$TEST_REPOSITORY" init -q
printf '%s\n' "baseline" > "$TEST_REPOSITORY/README.md"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "baseline"
BASE_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if "$DUPLICATE_PROVIDER_CLASSIFIER" "$BASE_SHA" "$BASE_SHA" >/dev/null 2>&1; then
  echo "Duplicate delivered-provider configuration must fail closed." >&2
  exit 1
fi

printf '%s\n' "pre-adapter documentation" >> "$TEST_REPOSITORY/README.md"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "pre-adapter docs"
PRE_ADAPTER_DOCS_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$BASE_SHA" "$PRE_ADAPTER_DOCS_SHA")" != "none" ]]; then
  echo "Documentation-only changes must remain secretless." >&2
  exit 1
fi

printf '%s\n' "foundation" > "$TEST_REPOSITORY/scripts/check-live.sh"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "gate foundation"
FOUNDATION_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$PRE_ADAPTER_DOCS_SHA" "$FOUNDATION_SHA")" != \
  "openai,anthropic,openrouter" ]]; then
  echo "Changing the installed live gate must require live verification." >&2
  exit 1
fi

SWIFT_SOURCE_DIRECTORY="$TEST_REPOSITORY/swift-package/Sources/UniversalAiConnector"
mkdir -p "$SWIFT_SOURCE_DIRECTORY"
printf '%s\n' "internal Swift façade marker" > "$SWIFT_SOURCE_DIRECTORY/UniversalAiConnector.swift"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "change Swift package boundary"
SWIFT_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$FOUNDATION_SHA" "$SWIFT_SHA")" != \
  "openai,anthropic,openrouter" ]]; then
  echo "Changing the Swift package boundary must require live verification." >&2
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

if [[ "$("$CLASSIFIER" "$SWIFT_SHA" "$ADAPTER_SHA")" != "openai" ]]; then
  echo "Adding an adapter outside any sentinel package must require live verification." >&2
  exit 1
fi

printf '%s\n' "documentation" >> "$TEST_REPOSITORY/README.md"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "docs only"
DOCS_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$ADAPTER_SHA" "$DOCS_SHA")" != "none" ]]; then
  echo "Documentation-only changes must not require live credentials." >&2
  exit 1
fi

CONTROL_CHARACTER_PATH="$TEST_REPOSITORY/bridge/src/commonMain/kotlin/control"$'\n'"character.kt"
mkdir -p "$(dirname "$CONTROL_CHARACTER_PATH")"
printf '%s\n' "control character path" > "$CONTROL_CHARACTER_PATH"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "add protected control-character path"
CONTROL_CHARACTER_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$DOCS_SHA" "$CONTROL_CHARACTER_SHA")" != \
  "openai,anthropic,openrouter" ]]; then
  echo "Protected control-character paths must require live verification." >&2
  exit 1
fi

printf '%s\n' "rootProject.name = \"live-impact-fixture\"" > "$TEST_REPOSITORY/settings.gradle.kts"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "change live build infrastructure"
BUILD_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$CONTROL_CHARACTER_SHA" "$BUILD_SHA")" != \
  "openai,anthropic,openrouter" ]]; then
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

if [[ "$("$CLASSIFIER" "$BUILD_SHA" "$RUNTIME_SHA")" != "openai" ]]; then
  echo "Changing an active adapter must require live verification." >&2
  exit 1
fi

mkdir -p "$ANTHROPIC_DIRECTORY"
printf '%s\n' "internal Anthropic adapter marker" > \
  "$ANTHROPIC_DIRECTORY/AnthropicMessagesAdapter.kt"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "add Anthropic adapter marker"
ANTHROPIC_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$RUNTIME_SHA" "$ANTHROPIC_SHA")" != "anthropic" ]]; then
  echo "An Anthropic-only change must select only the delivered Anthropic gate." >&2
  exit 1
fi
if [[ "$("$MULTI_PROVIDER_CLASSIFIER" "$RUNTIME_SHA" "$ANTHROPIC_SHA")" != "anthropic" ]]; then
  echo "An Anthropic-only change must select only the delivered Anthropic gate." >&2
  exit 1
fi

mkdir -p "$OPENROUTER_DIRECTORY"
printf '%s\n' "internal OpenRouter adapter marker" > \
  "$OPENROUTER_DIRECTORY/OpenRouterChatCompletionsAdapter.kt"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "add OpenRouter adapter marker"
OPENROUTER_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$ANTHROPIC_SHA" "$OPENROUTER_SHA")" != "openrouter" ]]; then
  echo "An OpenRouter-only change must select the delivered OpenRouter gate." >&2
  exit 1
fi
if [[ "$("$MULTI_PROVIDER_CLASSIFIER" "$ANTHROPIC_SHA" "$OPENROUTER_SHA")" != \
  "openrouter" ]]; then
  echo "An OpenRouter-only change must select only the delivered OpenRouter gate." >&2
  exit 1
fi

printf '%s\n' "shared behavior marker" > \
  "$TEST_REPOSITORY/bridge/src/commonMain/kotlin/SharedBehavior.kt"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "change shared behavior"
SHARED_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$OPENROUTER_SHA" "$SHARED_SHA")" != \
  "openai,anthropic,openrouter" ]]; then
  echo "A shared change must select the current delivered provider gates." >&2
  exit 1
fi
if [[ "$("$MULTI_PROVIDER_CLASSIFIER" "$OPENROUTER_SHA" "$SHARED_SHA")" != \
  "openai,anthropic,openrouter" ]]; then
  echo "A shared change must select every delivered provider gate." >&2
  exit 1
fi

mkdir -p "$PROVIDER_DIRECTORY/future"
printf '%s\n' "ambiguous provider marker" > \
  "$PROVIDER_DIRECTORY/future/FutureAdapter.kt"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "change ambiguous provider behavior"
AMBIGUOUS_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$SHARED_SHA" "$AMBIGUOUS_SHA")" != \
  "openai,anthropic,openrouter" ]]; then
  echo "An ambiguous provider change must select the current delivered provider gates." >&2
  exit 1
fi
if [[ "$("$MULTI_PROVIDER_CLASSIFIER" "$SHARED_SHA" "$AMBIGUOUS_SHA")" != \
  "openai,anthropic,openrouter" ]]; then
  echo "An ambiguous provider change must fail closed to every delivered provider." >&2
  exit 1
fi

rm "$OPENAI_DIRECTORY/OpenAiResponsesAdapter.kt"
ln -s "synthetic-adapter-target.kt" "$OPENAI_DIRECTORY/OpenAiResponsesAdapter.kt"
git -C "$TEST_REPOSITORY" add -A
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "change adapter file type"
TYPE_CHANGE_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$AMBIGUOUS_SHA" "$TYPE_CHANGE_SHA")" != "openai" ]]; then
  echo "Changing a protected path file type must require live verification." >&2
  exit 1
fi

rm -rf "$OPENAI_DIRECTORY"
git -C "$TEST_REPOSITORY" add -A
git -C "$TEST_REPOSITORY" \
  -c user.name="Live Impact Test" \
  -c user.email="live-impact@example.invalid" \
  commit -qm "remove adapter"
REMOVAL_SHA="$(git -C "$TEST_REPOSITORY" rev-parse HEAD)"

if [[ "$("$CLASSIFIER" "$TYPE_CHANGE_SHA" "$REMOVAL_SHA")" != "openai" ]]; then
  echo "Removing an active adapter must require live verification." >&2
  exit 1
fi

if "$CLASSIFIER" invalid "$REMOVAL_SHA" >/dev/null 2>&1; then
  echo "Invalid revisions must fail closed." >&2
  exit 1
fi

EMPTY_TREE_SHA="$(git -C "$TEST_REPOSITORY" hash-object -t tree /dev/null)"
UNRELATED_SHA="$(
  git -C "$TEST_REPOSITORY" \
    -c user.name="Live Impact Test" \
    -c user.email="live-impact@example.invalid" \
    commit-tree "$EMPTY_TREE_SHA" -m "unrelated history"
)"
if "$CLASSIFIER" "$REMOVAL_SHA" "$UNRELATED_SHA" >/dev/null 2>&1; then
  echo "Unrelated valid commits must fail closed." >&2
  exit 1
fi

OPENAI_RELATIVE_DIRECTORY="${OPENAI_DIRECTORY#"$TEST_REPOSITORY"/}"
BROKEN_TREE_OBJECT="$(
  git -C "$TEST_REPOSITORY" rev-parse "$RUNTIME_SHA:$OPENAI_RELATIVE_DIRECTORY"
)"
BROKEN_TREE_OBJECT_PATH="$TEST_REPOSITORY/.git/objects/${BROKEN_TREE_OBJECT:0:2}/${BROKEN_TREE_OBJECT:2}"
if [[ ! -f "$BROKEN_TREE_OBJECT_PATH" ]]; then
  echo "Expected a loose adapter tree object for the failure regression test." >&2
  exit 1
fi
rm "$BROKEN_TREE_OBJECT_PATH"
if "$CLASSIFIER" "$RUNTIME_SHA" "$TYPE_CHANGE_SHA" >/dev/null 2>&1; then
  echo "Diff tree failures must fail closed." >&2
  exit 1
fi

echo "Live-impact classifier regression tests passed."
