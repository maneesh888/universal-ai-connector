#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROPERTIES="$ROOT/gradle/wrapper/gradle-wrapper.properties"
EXPECTED_DISTRIBUTION_URL='https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip'
EXPECTED_DISTRIBUTION_SHA256='9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14'

property_count() {
  local property_name="$1"
  awk -F= -v property_name="$property_name" '
    $1 == property_name { count += 1 }
    END { print count + 0 }
  ' "$PROPERTIES"
}

property_value() {
  local property_name="$1"
  awk -F= -v property_name="$property_name" '
    $1 == property_name {
      sub(/^[^=]*=/, "")
      print
    }
  ' "$PROPERTIES"
}

if [[ "$(property_count distributionUrl)" -ne 1 ||
      "$(property_value distributionUrl)" != "$EXPECTED_DISTRIBUTION_URL" ]]; then
  echo "Gradle wrapper distribution URL must retain the reviewed 9.6.1 binary distribution." >&2
  exit 1
fi

if [[ "$(property_count distributionSha256Sum)" -ne 1 ||
      "$(property_value distributionSha256Sum)" != "$EXPECTED_DISTRIBUTION_SHA256" ]]; then
  echo "Gradle wrapper distribution must retain the official reviewed SHA-256 checksum." >&2
  exit 1
fi

echo "Gradle wrapper distribution and checksum policy passed."
