#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONFIG="$ROOT/.github/dependabot.yml"

EXPECTED_GRADLE_POLICY='  - package-ecosystem: gradle
    directory: /
    schedule:
      interval: monthly
    allow:
      - dependency-name: *
        update-types:
          - version-update:semver-patch
    groups:
      android-build-tooling-patches:
        patterns:
          - com.android.application
          - com.android.kotlin.multiplatform.library
        update-types:
          - patch
      compose-patches:
        patterns:
          - androidx.compose:*
        update-types:
          - patch
      lifecycle-patches:
        patterns:
          - androidx.lifecycle:*
        update-types:
          - patch'

normalize_gradle_policy() {
  local policy_file="$1"

  awk '
    /^  - package-ecosystem:/ {
      if (in_gradle) {
        exit
      }
      in_gradle = ($0 == "  - package-ecosystem: gradle")
    }
    in_gradle {
      line = $0
      gsub(/"/, "", line)
      apostrophe = sprintf("%c", 39)
      gsub(apostrophe, "", line)
      sub(/[[:space:]]+$/, "", line)
      print line
    }
  ' "$policy_file"
}

validate_policy() {
  local policy_file="$1"
  local actual_policy

  [[ -f "$policy_file" ]] || return 1
  actual_policy="$(normalize_gradle_policy "$policy_file")"
  [[ "$actual_policy" == "$EXPECTED_GRADLE_POLICY" ]]
}

if ! validate_policy "$CONFIG"; then
  echo "Dependabot Gradle policy must retain the exact patch-only isolated groups." >&2
  diff -u \
    <(printf '%s\n' "$EXPECTED_GRADLE_POLICY") \
    <(normalize_gradle_policy "$CONFIG") >&2 || true
  exit 1
fi

TEST_DIRECTORY="$(mktemp -d)"
trap 'rm -rf "$TEST_DIRECTORY"' EXIT

SINGLE_QUOTED_CATCH_ALL="$TEST_DIRECTORY/single-quoted-catch-all.yml"
awk '
  BEGIN {
    apostrophe = sprintf("%c", 39)
  }
  !changed && $0 == "          - \"com.android.application\"" {
    print "          - " apostrophe "*" apostrophe
    changed = 1
    next
  }
  {
    print
  }
' "$CONFIG" > "$SINGLE_QUOTED_CATCH_ALL"

if validate_policy "$SINGLE_QUOTED_CATCH_ALL"; then
  echo "A single-quoted Gradle catch-all group must fail policy validation." >&2
  exit 1
fi

SPLIT_ALLOW_RULE="$TEST_DIRECTORY/split-allow-rule.yml"
awk '
  $0 == "      - dependency-name: \"*\"" {
    print
    print "      - dependency-name: \"androidx.lifecycle:*\""
    next
  }
  {
    print
  }
' "$CONFIG" > "$SPLIT_ALLOW_RULE"

if validate_policy "$SPLIT_ALLOW_RULE"; then
  echo "An unrestricted wildcard allow rule must not borrow a patch limit from another entry." >&2
  exit 1
fi

echo "Dependabot patch-only grouping policy passed."
