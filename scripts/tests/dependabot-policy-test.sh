#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONFIG="$ROOT/.github/dependabot.yml"

validate_policy() {
  local policy_file="$1"

  [[ -f "$policy_file" ]] || return 1
  ruby - "$policy_file" <<'RUBY'
require "yaml"

policy_file = ARGV.fetch(0)
expected = {
  "package-ecosystem" => "gradle",
  "directory" => "/",
  "schedule" => {
    "interval" => "monthly",
  },
  "allow" => [
    {
      "dependency-name" => "*",
      "update-types" => [
        "version-update:semver-patch",
        "version-update:semver-minor",
      ],
    },
  ],
  "groups" => {
    "android-build-tooling-patches" => {
      "patterns" => [
        "com.android.application",
        "com.android.kotlin.multiplatform.library",
      ],
      "update-types" => [
        "patch",
      ],
    },
    "compose-patches" => {
      "patterns" => [
        "androidx.compose:*",
      ],
      "update-types" => [
        "patch",
      ],
    },
    "lifecycle-patches" => {
      "patterns" => [
        "androidx.lifecycle:*",
      ],
      "update-types" => [
        "patch",
      ],
    },
  },
}

begin
  document = YAML.safe_load(
    File.read(policy_file),
    permitted_classes: [],
    permitted_symbols: [],
    aliases: false,
    filename: policy_file,
  )
  updates = document.fetch("updates")
  gradle_updates = updates.select do |entry|
    entry.is_a?(Hash) && entry["package-ecosystem"] == "gradle"
  end
  exit(gradle_updates.length == 1 && gradle_updates.first == expected ? 0 : 1)
rescue KeyError, Psych::Exception, TypeError
  exit(1)
end
RUBY
}

if ! validate_policy "$CONFIG"; then
  echo "Dependabot Gradle policy must retain minor-and-patch updates with isolated patch groups." >&2
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
  echo "An unrestricted wildcard allow rule must not borrow update limits from another entry." >&2
  exit 1
fi

DUPLICATE_GRADLE_POLICY="$TEST_DIRECTORY/duplicate-gradle-policy.yml"
cp "$CONFIG" "$DUPLICATE_GRADLE_POLICY"
awk '
  $0 == "  - package-ecosystem: gradle" {
    copy = 1
  }
  copy {
    print
  }
' "$CONFIG" >> "$DUPLICATE_GRADLE_POLICY"

if validate_policy "$DUPLICATE_GRADLE_POLICY"; then
  echo "Multiple Gradle update policies must fail validation." >&2
  exit 1
fi

BARE_WILDCARD_ALLOW="$TEST_DIRECTORY/bare-wildcard-allow.yml"
awk '
  !changed && $0 == "      - dependency-name: \"*\"" {
    print "      - dependency-name: *"
    changed = 1
    next
  }
  {
    print
  }
' "$CONFIG" > "$BARE_WILDCARD_ALLOW"

if validate_policy "$BARE_WILDCARD_ALLOW"; then
  echo "Invalid YAML must fail policy validation." >&2
  exit 1
fi

echo "Dependabot minor-and-patch update policy passed."
