#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
TEST_DIRECTORY="$(mktemp -d)"
PRIMARY_REPOSITORY="$TEST_DIRECTORY/machine one/checkout with spaces"
SECOND_REPOSITORY="$TEST_DIRECTORY/another machine/different clone location"
LINKED_WORKTREE="$TEST_DIRECTORY/disposable worktrees/linked checkout"
REPLACEMENT_WORKTREE="$TEST_DIRECTORY/disposable worktrees/replacement checkout"
OUTSIDE_DIRECTORY="$TEST_DIRECTORY/outside trusted directory"
OUTPUT="$TEST_DIRECTORY/output.log"
SYNTHETIC_SECRET="synthetic-private-material-that-must-not-appear"

unset \
  GIT_ALTERNATE_OBJECT_DIRECTORIES \
  GIT_COMMON_DIR \
  GIT_CONFIG \
  GIT_CONFIG_COUNT \
  GIT_CONFIG_PARAMETERS \
  GIT_DIR \
  GIT_GRAFT_FILE \
  GIT_IMPLICIT_WORK_TREE \
  GIT_INDEX_FILE \
  GIT_NO_REPLACE_OBJECTS \
  GIT_OBJECT_DIRECTORY \
  GIT_PREFIX \
  GIT_REPLACE_REF_BASE \
  GIT_SHALLOW_FILE \
  GIT_WORK_TREE \
  UAC_LIVE_ENV_FILE \
  OPENAI_API_KEY \
  OPENAI_LIVE_MODEL \
  ANTHROPIC_API_KEY \
  ANTHROPIC_LIVE_MODEL \
  OPENROUTER_API_KEY \
  OPENROUTER_LIVE_MODEL \
  GATEWAY_LIVE_BASE_URL \
  GATEWAY_API_KEY \
  GATEWAY_LIVE_MODEL \
  GATEWAY_LIVE_STRUCTURED_OUTPUT

cleanup() {
  rm -rf "$TEST_DIRECTORY"
}
trap cleanup EXIT

create_repository() {
  local repository="$1"

  mkdir -p "$repository/scripts"
  cp "$ROOT/scripts/local-config.sh" "$repository/scripts/local-config.sh"
  chmod +x "$repository/scripts/local-config.sh"
  printf '%s\n' \
    '.env.live' \
    '.env.live.*' \
    '!.env.live.example' > "$repository/.gitignore"
  printf '%s\n' \
    'OPENAI_API_KEY=' \
    'OPENAI_LIVE_MODEL=' \
    'ANTHROPIC_API_KEY=' \
    'ANTHROPIC_LIVE_MODEL=' \
    'OPENROUTER_API_KEY=' \
    'OPENROUTER_LIVE_MODEL=' \
    'GATEWAY_LIVE_BASE_URL=' \
    'GATEWAY_API_KEY=' \
    'GATEWAY_LIVE_MODEL=' \
    'GATEWAY_LIVE_STRUCTURED_OUTPUT=' > "$repository/.env.live.example"
  git -C "$repository" -c init.defaultBranch=main init -q
  git -C "$repository" add .
  git -C "$repository" \
    -c user.name="Local Config Test" \
    -c user.email="local-config@example.invalid" \
    commit -qm "fixture"
}

write_valid_config() {
  local file_path="$1"

  printf '%s\n' \
    "OPENAI_API_KEY='$SYNTHETIC_SECRET'" \
    'OPENAI_LIVE_MODEL=test-model' \
    'GATEWAY_LIVE_STRUCTURED_OUTPUT=false' > "$file_path"
  chmod 600 "$file_path"
}

assert_secret_absent() {
  if grep -Fq "$SYNTHETIC_SECRET" "$OUTPUT"; then
    echo "Local configuration tooling exposed synthetic secret material." >&2
    exit 1
  fi
}

expect_failure() {
  local expected_message="$1"
  shift
  local status=0

  : > "$OUTPUT"
  "$@" > "$OUTPUT" 2>&1 || status=$?
  if [[ "$status" -eq 0 ]]; then
    echo "Expected local configuration failure: $expected_message" >&2
    exit 1
  fi
  if ! grep -Fq "$expected_message" "$OUTPUT"; then
    echo "Local configuration failure omitted: $expected_message" >&2
    exit 1
  fi
  assert_secret_absent
}

create_repository "$PRIMARY_REPOSITORY"
PRIMARY_PHYSICAL="$(cd "$PRIMARY_REPOSITORY" && pwd -P)"
PRIMARY_CONFIG="$PRIMARY_PHYSICAL/.env.live"
write_valid_config "$PRIMARY_CONFIG"

# Primary-checkout execution and paths containing spaces.
if [[ "$("$PRIMARY_REPOSITORY/scripts/local-config.sh" primary-checkout)" != \
      "$PRIMARY_PHYSICAL" ]]; then
  echo "Primary checkout resolution failed from the primary checkout." >&2
  exit 1
fi
if [[ "$("$PRIMARY_REPOSITORY/scripts/local-config.sh" live-env-path)" != \
      "$PRIMARY_CONFIG" ]]; then
  echo "Canonical live configuration path was not rooted in the primary checkout." >&2
  exit 1
fi
"$PRIMARY_REPOSITORY/scripts/local-config.sh" validate-live-env > "$OUTPUT" 2>&1
assert_secret_absent

# Literal parsing loads only requested names and preserves process-environment overrides.
env \
  UAC_TEST_REPOSITORY="$PRIMARY_REPOSITORY" \
  UAC_TEST_EXPECTED_SECRET="$SYNTHETIC_SECRET" \
  bash -c '
    source "$UAC_TEST_REPOSITORY/scripts/local-config.sh"
    uac_load_live_environment \
      "$UAC_TEST_REPOSITORY" \
      OPENAI_API_KEY \
      OPENAI_LIVE_MODEL
    [[ "$OPENAI_API_KEY" == "$UAC_TEST_EXPECTED_SECRET" ]]
    [[ "$OPENAI_LIVE_MODEL" == "test-model" ]]
    [[ -z "${GATEWAY_LIVE_STRUCTURED_OUTPUT:-}" ]]
  ' > "$OUTPUT" 2>&1
assert_secret_absent

env \
  OPENAI_API_KEY="process-environment-wins" \
  UAC_TEST_REPOSITORY="$PRIMARY_REPOSITORY" \
  bash -c '
    source "$UAC_TEST_REPOSITORY/scripts/local-config.sh"
    uac_load_live_environment "$UAC_TEST_REPOSITORY" OPENAI_API_KEY
    [[ "$OPENAI_API_KEY" == "process-environment-wins" ]]
  ' > "$OUTPUT" 2>&1
assert_secret_absent

# Linked worktrees reuse the primary checkout's ignored file without copying it.
mkdir -p "$(dirname "$LINKED_WORKTREE")"
git -C "$PRIMARY_REPOSITORY" worktree add -qb linked-test "$LINKED_WORKTREE"
if [[ "$("$LINKED_WORKTREE/scripts/local-config.sh" primary-checkout)" != \
      "$PRIMARY_PHYSICAL" ||
      "$("$LINKED_WORKTREE/scripts/local-config.sh" live-env-path)" != \
      "$PRIMARY_CONFIG" ]]; then
  echo "Linked worktree did not discover the primary checkout configuration." >&2
  exit 1
fi
"$LINKED_WORKTREE/scripts/local-config.sh" validate-live-env > "$OUTPUT" 2>&1
assert_secret_absent
if [[ -e "$LINKED_WORKTREE/.env.live" ]]; then
  echo "Persistent local configuration was copied into a linked worktree." >&2
  exit 1
fi

# Relative and absolute file-path overrides remain direct children of the trusted directory.
OVERRIDE_CONFIG="$PRIMARY_PHYSICAL/.env.live.gateway"
write_valid_config "$OVERRIDE_CONFIG"
if [[ "$(
        UAC_LIVE_ENV_FILE=.env.live.gateway \
          "$LINKED_WORKTREE/scripts/local-config.sh" live-env-path
      )" != "$OVERRIDE_CONFIG" ]]; then
  echo "Allowed relative local configuration override did not resolve canonically." >&2
  exit 1
fi
UAC_LIVE_ENV_FILE="$OVERRIDE_CONFIG" \
  "$LINKED_WORKTREE/scripts/local-config.sh" validate-live-env > "$OUTPUT" 2>&1
assert_secret_absent

printf '%s\n' "OPENAI_API_KEY='$SYNTHETIC_SECRET'" > "$LINKED_WORKTREE/.env.live"
chmod 600 "$LINKED_WORKTREE/.env.live"
git -C "$LINKED_WORKTREE" add --force .env.live
git -C "$LINKED_WORKTREE" \
  -c user.name="Local Config Test" \
  -c user.email="local-config@example.invalid" \
  commit -qm "active tracked rejection fixture"
expect_failure \
  "Local live configuration name must not be tracked in the active worktree: .env.live" \
  "$LINKED_WORKTREE/scripts/local-config.sh" validate-live-env

# A different clone location resolves its own per-machine primary checkout.
create_repository "$SECOND_REPOSITORY"
SECOND_PHYSICAL="$(cd "$SECOND_REPOSITORY" && pwd -P)"
SECOND_CONFIG="$SECOND_PHYSICAL/.env.live"
if [[ "$("$SECOND_REPOSITORY/scripts/local-config.sh" live-env-path)" != \
      "$SECOND_CONFIG" || "$SECOND_CONFIG" == "$PRIMARY_CONFIG" ]]; then
  echo "Independent clone locations did not resolve independent canonical configuration." >&2
  exit 1
fi
expect_failure \
  "Local live configuration is missing: $SECOND_CONFIG" \
  "$SECOND_REPOSITORY/scripts/local-config.sh" validate-live-env

# Tracked files are rejected even when their names otherwise match the allowlist.
TRACKED_CONFIG="$SECOND_PHYSICAL/.env.live.tracked"
write_valid_config "$TRACKED_CONFIG"
git -C "$SECOND_REPOSITORY" add --force .env.live.tracked
git -C "$SECOND_REPOSITORY" \
  -c user.name="Local Config Test" \
  -c user.email="local-config@example.invalid" \
  commit -qm "tracked rejection fixture"
expect_failure \
  "Local live configuration must not be tracked by Git: $TRACKED_CONFIG" \
  env UAC_LIVE_ENV_FILE=.env.live.tracked \
  "$SECOND_REPOSITORY/scripts/local-config.sh" validate-live-env

# External paths, traversal, unsupported names, and symlink escapes fail before parsing.
mkdir -p "$OUTSIDE_DIRECTORY"
OUTSIDE_CONFIG="$OUTSIDE_DIRECTORY/.env.live"
write_valid_config "$OUTSIDE_CONFIG"
expect_failure \
  "UAC_LIVE_ENV_FILE must be within the primary checkout configuration directory." \
  env UAC_LIVE_ENV_FILE="$OUTSIDE_CONFIG" \
  "$PRIMARY_REPOSITORY/scripts/local-config.sh" validate-live-env
expect_failure \
  "UAC_LIVE_ENV_FILE must name a direct child of the primary checkout without traversal." \
  env UAC_LIVE_ENV_FILE=../outside/.env.live \
  "$PRIMARY_REPOSITORY/scripts/local-config.sh" validate-live-env
expect_failure \
  "UAC_LIVE_ENV_FILE must use the documented .env.live or .env.live.<name> format." \
  env UAC_LIVE_ENV_FILE=local.env \
  "$PRIMARY_REPOSITORY/scripts/local-config.sh" validate-live-env

SYMLINK_CONFIG="$PRIMARY_PHYSICAL/.env.live.escape"
ln -s "$OUTSIDE_CONFIG" "$SYMLINK_CONFIG"
expect_failure \
  "Local live configuration must not be a symbolic link: $SYMLINK_CONFIG" \
  env UAC_LIVE_ENV_FILE=.env.live.escape \
  "$PRIMARY_REPOSITORY/scripts/local-config.sh" validate-live-env

# Restrictive permissions and the documented variable allowlist fail closed without values.
PERMISSIVE_CONFIG="$PRIMARY_PHYSICAL/.env.live.permissive"
write_valid_config "$PERMISSIVE_CONFIG"
chmod 644 "$PERMISSIVE_CONFIG"
expect_failure \
  "Local live configuration permissions must deny group and other access: $PERMISSIVE_CONFIG" \
  env UAC_LIVE_ENV_FILE=.env.live.permissive \
  "$PRIMARY_REPOSITORY/scripts/local-config.sh" validate-live-env

INVALID_CONFIG="$PRIMARY_PHYSICAL/.env.live.invalid"
printf '%s\n' \
  "OPENAI_API_KEY='$SYNTHETIC_SECRET'" \
  'UNSUPPORTED_SECRET_NAME=must-not-load' > "$INVALID_CONFIG"
chmod 600 "$INVALID_CONFIG"
expect_failure \
  "Local live configuration contains unsupported variable UNSUPPORTED_SECRET_NAME" \
  env UAC_LIVE_ENV_FILE=.env.live.invalid \
  "$PRIMARY_REPOSITORY/scripts/local-config.sh" validate-live-env

QUOTING_CONFIG="$PRIMARY_PHYSICAL/.env.live.quoting"
printf 'OPENAI_API_KEY="%s"suffix\n' "$SYNTHETIC_SECRET" > "$QUOTING_CONFIG"
chmod 600 "$QUOTING_CONFIG"
expect_failure \
  "Local live configuration has unmatched quotes at line 1." \
  env UAC_LIVE_ENV_FILE=.env.live.quoting \
  "$PRIMARY_REPOSITORY/scripts/local-config.sh" validate-live-env

# Removing a disposable worktree cannot remove the canonical file; a replacement can reuse it.
git -C "$PRIMARY_REPOSITORY" worktree remove "$LINKED_WORKTREE"
if [[ ! -f "$PRIMARY_CONFIG" ]]; then
  echo "Removing a linked worktree removed canonical local configuration." >&2
  exit 1
fi
git -C "$PRIMARY_REPOSITORY" worktree add -qb replacement-test "$REPLACEMENT_WORKTREE"
"$REPLACEMENT_WORKTREE/scripts/local-config.sh" validate-live-env > "$OUTPUT" 2>&1
assert_secret_absent
if [[ "$("$REPLACEMENT_WORKTREE/scripts/local-config.sh" live-env-path)" != \
      "$PRIMARY_CONFIG" ]]; then
  echo "Replacement worktree could not reuse canonical local configuration." >&2
  exit 1
fi

echo "Worktree-safe local configuration regression tests passed."
