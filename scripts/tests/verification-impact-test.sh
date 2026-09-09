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
CLASSIFIER="$TEST_REPOSITORY/scripts/verification-impact.sh"

cleanup() {
  rm -rf "$TEST_DIRECTORY"
}
trap cleanup EXIT

commit_all() {
  local subject="$1"
  git -C "$TEST_REPOSITORY" add .
  git -C "$TEST_REPOSITORY" \
    -c user.name="Verification Impact Test" \
    -c user.email="verification-impact@example.invalid" \
    commit -qm "$subject"
  git -C "$TEST_REPOSITORY" rev-parse HEAD
}

expect_committed_impact() {
  local expected="$1"
  local base_sha="$2"
  local head_sha="$3"
  local actual
  actual="$(UAC_REPOSITORY_ROOT="$TEST_REPOSITORY" "$CLASSIFIER" "$base_sha" "$head_sha")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Expected verification impact $expected, got $actual." >&2
    exit 1
  fi
}

mkdir -p \
  "$TEST_REPOSITORY/docs/plans" \
  "$TEST_REPOSITORY/scripts" \
  "$TEST_REPOSITORY/src" \
  "$TEST_REPOSITORY/.github/workflows" \
  "$TEST_REPOSITORY/gradle"
cp "$ROOT/scripts/verification-impact.sh" "$CLASSIFIER"
chmod +x "$CLASSIFIER"
printf '%s\n' "readme" > "$TEST_REPOSITORY/README.md"
printf '%s\n' "license" > "$TEST_REPOSITORY/LICENSE.txt"
printf '%s\n' "plan" > "$TEST_REPOSITORY/docs/plans/plan.md"
printf '%s\n' "source" > "$TEST_REPOSITORY/src/source.kt"
printf '%s\n' "committed rename source" > "$TEST_REPOSITORY/src/committed-rename.kt"
printf '%s\n' "cached rename source" > "$TEST_REPOSITORY/src/cached-rename.kt"
printf '%s\n' "workflow" > "$TEST_REPOSITORY/.github/workflows/ci.yml"
printf '%s\n' "script" > "$TEST_REPOSITORY/scripts/tool.sh"
printf '%s\n' "plugins {}" > "$TEST_REPOSITORY/build.gradle.kts"
printf '%s\n' "dependency" > "$TEST_REPOSITORY/gradle/libs.versions.toml"

git -C "$TEST_REPOSITORY" init -q
BASE_SHA="$(commit_all baseline)"

printf '%s\n' "plan update" >> "$TEST_REPOSITORY/docs/plans/plan.md"
DOCS_SHA="$(commit_all 'docs only')"
expect_committed_impact docs-only "$BASE_SHA" "$DOCS_SHA"

printf '%s\n' "readme update" >> "$TEST_REPOSITORY/README.md"
README_SHA="$(commit_all 'readme only')"
expect_committed_impact docs-only "$DOCS_SHA" "$README_SHA"

printf '%s\n' "license update" >> "$TEST_REPOSITORY/LICENSE.txt"
LICENSE_SHA="$(commit_all 'license only')"
expect_committed_impact docs-only "$README_SHA" "$LICENSE_SHA"

mkdir -p "$TEST_REPOSITORY/third_party"
printf '%s\n' "nested license" > "$TEST_REPOSITORY/third_party/LICENSE.txt"
NESTED_LICENSE_SHA="$(commit_all 'nested license')"
expect_committed_impact full "$LICENSE_SHA" "$NESTED_LICENSE_SHA"

printf '%s\n' "source update" >> "$TEST_REPOSITORY/src/source.kt"
SOURCE_SHA="$(commit_all 'source change')"
expect_committed_impact full "$NESTED_LICENSE_SHA" "$SOURCE_SHA"

printf '%s\n' "workflow update" >> "$TEST_REPOSITORY/.github/workflows/ci.yml"
WORKFLOW_SHA="$(commit_all 'workflow change')"
expect_committed_impact full "$SOURCE_SHA" "$WORKFLOW_SHA"

printf '%s\n' "script update" >> "$TEST_REPOSITORY/scripts/tool.sh"
SCRIPT_SHA="$(commit_all 'script change')"
expect_committed_impact full "$WORKFLOW_SHA" "$SCRIPT_SHA"

printf '%s\n' "dependency update" >> "$TEST_REPOSITORY/gradle/libs.versions.toml"
DEPENDENCY_SHA="$(commit_all 'dependency change')"
expect_committed_impact full "$SCRIPT_SHA" "$DEPENDENCY_SHA"

printf '%s\n' "config update" >> "$TEST_REPOSITORY/build.gradle.kts"
CONFIG_SHA="$(commit_all 'build config change')"
expect_committed_impact full "$DEPENDENCY_SHA" "$CONFIG_SHA"

printf '%s\n' "mixed docs" >> "$TEST_REPOSITORY/docs/plans/plan.md"
printf '%s\n' "mixed source" >> "$TEST_REPOSITORY/src/source.kt"
MIXED_SHA="$(commit_all 'mixed change')"
expect_committed_impact full "$CONFIG_SHA" "$MIXED_SHA"

git -C "$TEST_REPOSITORY" mv \
  src/committed-rename.kt \
  docs/plans/committed-rename.md
COMMITTED_RENAME_SHA="$(commit_all 'rename source into documentation')"
expect_committed_impact full "$MIXED_SHA" "$COMMITTED_RENAME_SHA"

chmod +x "$TEST_REPOSITORY/docs/plans/plan.md"
MODE_SHA="$(commit_all 'executable documentation')"
expect_committed_impact full "$COMMITTED_RENAME_SHA" "$MODE_SHA"

if [[ "$(UAC_REPOSITORY_ROOT="$TEST_REPOSITORY" "$CLASSIFIER" "$MODE_SHA" "$MODE_SHA")" != "full" ]]; then
  echo "An empty committed diff must fail closed to full verification." >&2
  exit 1
fi

chmod -x "$TEST_REPOSITORY/docs/plans/plan.md"
PLAIN_DOC_SHA="$(commit_all 'restore plain documentation mode')"
expect_committed_impact full "$MODE_SHA" "$PLAIN_DOC_SHA"

printf '%s\n' "cached docs" >> "$TEST_REPOSITORY/docs/plans/plan.md"
git -C "$TEST_REPOSITORY" add docs/plans/plan.md
if [[ "$(UAC_REPOSITORY_ROOT="$TEST_REPOSITORY" "$CLASSIFIER" --cached)" != "docs-only" ]]; then
  echo "A staged plain-document change must use docs-only verification." >&2
  exit 1
fi
git -C "$TEST_REPOSITORY" restore --staged --worktree docs/plans/plan.md

printf '%s\n' "cached source" >> "$TEST_REPOSITORY/src/source.kt"
git -C "$TEST_REPOSITORY" add src/source.kt
if [[ "$(UAC_REPOSITORY_ROOT="$TEST_REPOSITORY" "$CLASSIFIER" --cached)" != "full" ]]; then
  echo "A staged source change must require full verification." >&2
  exit 1
fi
git -C "$TEST_REPOSITORY" restore --staged --worktree src/source.kt

git -C "$TEST_REPOSITORY" mv \
  src/cached-rename.kt \
  docs/plans/cached-rename.md
if [[ "$(UAC_REPOSITORY_ROOT="$TEST_REPOSITORY" "$CLASSIFIER" --cached)" != "full" ]]; then
  echo "A staged source rename into documentation must require full verification." >&2
  exit 1
fi
git -C "$TEST_REPOSITORY" restore \
  --staged \
  --worktree \
  src/cached-rename.kt \
  docs/plans/cached-rename.md

printf '%s\n' "regular document" > "$TEST_REPOSITORY/docs/z!.md"
LITERAL_PATH_BASE_SHA="$(commit_all 'literal path base')"
ln -s ../README.md "$TEST_REPOSITORY/docs/z[!a].md"
git -C "$TEST_REPOSITORY" add -- ':(literal)docs/z[!a].md'
if [[ "$(UAC_REPOSITORY_ROOT="$TEST_REPOSITORY" "$CLASSIFIER" --cached)" != "full" ]]; then
  echo "A cached Markdown symlink with pathspec metacharacters must require full verification." >&2
  exit 1
fi
LITERAL_PATH_HEAD_SHA="$(commit_all 'literal path symlink')"
expect_committed_impact full "$LITERAL_PATH_BASE_SHA" "$LITERAL_PATH_HEAD_SHA"

if UAC_REPOSITORY_ROOT="$TEST_REPOSITORY" \
  "$CLASSIFIER" missing/revision "$MODE_SHA" >/dev/null 2>&1; then
  echo "Invalid revisions must fail closed." >&2
  exit 1
fi

echo "Verification-impact classifier regression tests passed."
