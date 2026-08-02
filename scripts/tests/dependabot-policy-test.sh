#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONFIG="$ROOT/.github/dependabot.yml"

if [[ ! -f "$CONFIG" ]]; then
  echo "Dependabot policy is missing: $CONFIG" >&2
  exit 1
fi

if awk '
  $0 == "  - package-ecosystem: gradle" {
    in_gradle = 1
    next
  }
  in_gradle && /^  - package-ecosystem:/ {
    in_gradle = 0
    in_groups = 0
  }
  in_gradle && $0 == "    groups:" {
    in_groups = 1
    next
  }
  in_groups && /^    [^ ]/ {
    in_groups = 0
  }
  in_groups && $0 ~ /^[[:space:]]*-[[:space:]]*"\*"[[:space:]]*$/ {
    catch_all = 1
  }
  END {
    exit !catch_all
  }
' "$CONFIG"; then
  echo "Dependabot groups must not combine every Gradle dependency into one pull request." >&2
  exit 1
fi

if ! awk '
  $0 == "  - package-ecosystem: gradle" {
    in_gradle = 1
    next
  }
  in_gradle && /^  - package-ecosystem:/ {
    in_gradle = 0
  }
  in_gradle && $0 == "    allow:" {
    in_allow = 1
    next
  }
  in_allow && /^    [^ ]/ {
    in_allow = 0
  }
  in_allow && $0 == "      - dependency-name: \"*\"" {
    allows_all = 1
  }
  in_allow && $0 == "          - \"version-update:semver-patch\"" {
    allows_patch = 1
  }
  END {
    exit !(allows_all && allows_patch)
  }
' "$CONFIG"; then
  echo "Routine Gradle version updates must be restricted to SemVer patches." >&2
  exit 1
fi

for group in \
  android-build-tooling-patches \
  compose-patches \
  lifecycle-patches
do
  if ! grep -Fq "      $group:" "$CONFIG"; then
    echo "Dependabot policy is missing the isolated $group group." >&2
    exit 1
  fi
done

if awk '
  $0 == "  - package-ecosystem: gradle" {
    in_gradle = 1
    next
  }
  in_gradle && /^  - package-ecosystem:/ {
    in_gradle = 0
  }
  in_gradle && /version-update:semver-(minor|major)/ {
    forbidden = 1
  }
  END {
    exit !forbidden
  }
' "$CONFIG"; then
  echo "Routine Gradle policy must not enable minor or major version updates." >&2
  exit 1
fi

echo "Dependabot patch-only grouping policy passed."
