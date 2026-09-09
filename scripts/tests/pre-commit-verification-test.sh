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

cleanup() {
  rm -rf "$TEST_DIRECTORY"
}
trap cleanup EXIT

mkdir -p "$TEST_REPOSITORY/.githooks" "$TEST_REPOSITORY/scripts" "$TEST_REPOSITORY/docs"
cp "$ROOT/.githooks/pre-commit" "$TEST_REPOSITORY/.githooks/pre-commit"
cp "$ROOT/scripts/verification-impact.sh" "$TEST_REPOSITORY/scripts/verification-impact.sh"

cat > "$TEST_REPOSITORY/scripts/check.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  --hygiene)
    echo "hygiene" >> "$UAC_TEST_CALL_LOG"
    ;;
  --quick)
    echo "quick" >> "$UAC_TEST_CALL_LOG"
    ;;
  *)
    echo "Pre-commit requested an invalid verification mode." >&2
    exit 10
    ;;
esac
EOF
chmod +x \
  "$TEST_REPOSITORY/.githooks/pre-commit" \
  "$TEST_REPOSITORY/scripts/check.sh" \
  "$TEST_REPOSITORY/scripts/verification-impact.sh"

git -C "$TEST_REPOSITORY" init -q
printf '%s\n' "baseline" > "$TEST_REPOSITORY/README.md"
git -C "$TEST_REPOSITORY" add .
git -C "$TEST_REPOSITORY" \
  -c user.name="Pre-commit Verification Test" \
  -c user.email="pre-commit-verification@example.invalid" \
  commit -qm baseline

run_hook() {
  (
    cd "$TEST_REPOSITORY"
    UAC_TEST_CALL_LOG="$CALL_LOG" ./.githooks/pre-commit
  ) >/dev/null
}

printf '%s\n' "docs" > "$TEST_REPOSITORY/docs/guide.md"
git -C "$TEST_REPOSITORY" add docs/guide.md
: > "$CALL_LOG"
run_hook
if [[ "$(cat "$CALL_LOG")" != "hygiene" ]]; then
  echo "A documentation-only commit must run only the hygiene gate." >&2
  exit 1
fi
git -C "$TEST_REPOSITORY" \
  -c core.hooksPath=/dev/null \
  -c user.name="Pre-commit Verification Test" \
  -c user.email="pre-commit-verification@example.invalid" \
  commit -qm docs

printf '%s\n' "source" > "$TEST_REPOSITORY/source.kt"
git -C "$TEST_REPOSITORY" add source.kt
: > "$CALL_LOG"
run_hook
if [[ "$(cat "$CALL_LOG")" != "quick" ]]; then
  echo "A source commit must run the quick gate." >&2
  exit 1
fi
git -C "$TEST_REPOSITORY" \
  -c core.hooksPath=/dev/null \
  -c user.name="Pre-commit Verification Test" \
  -c user.email="pre-commit-verification@example.invalid" \
  commit -qm source

printf '%s\n' "mixed docs" >> "$TEST_REPOSITORY/docs/guide.md"
printf '%s\n' "mixed source" >> "$TEST_REPOSITORY/source.kt"
git -C "$TEST_REPOSITORY" add docs/guide.md source.kt
: > "$CALL_LOG"
run_hook
if [[ "$(cat "$CALL_LOG")" != "quick" ]]; then
  echo "A mixed documentation/source commit must fail closed to the quick gate." >&2
  exit 1
fi

echo "Pre-commit verification-impact regression tests passed."
