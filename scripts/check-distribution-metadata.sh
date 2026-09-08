#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_PROPERTIES="$ROOT/gradle.properties"
RELEASE_PROPERTIES="$ROOT/distribution/release.properties"
ROADMAP="$ROOT/docs/plans/universal-ai-connector-v2.md"
PLAN="$ROOT/docs/plans/production-distribution-host-integration.md"
GUIDE="$ROOT/docs/DISTRIBUTION.md"

property_count() {
  local file="$1"
  local property_name="$2"

  awk -F= -v property_name="$property_name" '
    $1 == property_name { count += 1 }
    END { print count + 0 }
  ' "$file"
}

property_value() {
  local file="$1"
  local property_name="$2"

  awk -F= -v property_name="$property_name" '
    $1 == property_name {
      sub(/^[^=]*=/, "")
      print
    }
  ' "$file"
}

require_property() {
  local file="$1"
  local property_name="$2"
  local count
  local value

  count="$(property_count "$file" "$property_name")"
  value="$(property_value "$file" "$property_name")"
  if [[ "$count" -ne 1 || -z "$value" ]]; then
    echo "Distribution metadata requires one non-empty $property_name in ${file#"$ROOT/"}." >&2
    exit 1
  fi
  printf '%s' "$value"
}

require_literal() {
  local file="$1"
  local literal="$2"

  if ! grep -Fq "$literal" "$file"; then
    echo "Distribution metadata drift: ${file#"$ROOT/"} is missing '$literal'." >&2
    exit 1
  fi
}

require_exact_release_property() {
  local property_name="$1"
  local expected_value="$2"
  local actual_value

  actual_value="$(require_property "$RELEASE_PROPERTIES" "$property_name")"
  if [[ "$actual_value" != "$expected_value" ]]; then
    echo "Distribution metadata drift: $property_name must be '$expected_value'." >&2
    exit 1
  fi
}

if [[ ! -f "$GRADLE_PROPERTIES" || ! -f "$RELEASE_PROPERTIES" || ! -f "$GUIDE" ]]; then
  echo "Distribution metadata requires gradle.properties, distribution/release.properties, and docs/DISTRIBUTION.md." >&2
  exit 1
fi

group="$(require_property "$GRADLE_PROPERTIES" GROUP)"
version="$(require_property "$GRADLE_PROPERTIES" VERSION_NAME)"

if [[ "$group" != "io.github.maneesh888" ]]; then
  echo "P8 Maven group must remain io.github.maneesh888." >&2
  exit 1
fi
if [[ ! "$version" =~ ^0\.1\.0-0\.p8\.[1-9][0-9]*$ ]]; then
  echo "P8 VERSION_NAME must be a SemVer candidate shaped 0.1.0-0.p8.N." >&2
  exit 1
fi

require_exact_release_property MAVEN_ARTIFACT_ID universal-ai-connector
require_exact_release_property POM_NAME "Universal AI Connector"
require_exact_release_property POM_DESCRIPTION "Provider-neutral Kotlin Multiplatform AI client with a Swift-native facade"
require_exact_release_property POM_URL https://github.com/maneesh888/universal-ai-connector
require_exact_release_property POM_LICENSE_NAME "MIT License"
require_exact_release_property POM_LICENSE_URL https://opensource.org/license/mit/
require_exact_release_property POM_SCM_URL https://github.com/maneesh888/universal-ai-connector
require_exact_release_property POM_SCM_CONNECTION scm:git:https://github.com/maneesh888/universal-ai-connector.git
require_exact_release_property POM_SCM_DEVELOPER_CONNECTION scm:git:ssh://git@github.com/maneesh888/universal-ai-connector.git
require_exact_release_property POM_DEVELOPER_ID maneesh888
require_exact_release_property POM_DEVELOPER_NAME maneesh888
require_exact_release_property POM_DEVELOPER_URL https://github.com/maneesh888
require_exact_release_property RELEASE_TAG_TEMPLATE 'v{version}'
require_exact_release_property APPLE_ASSET_NAME UniversalAiConnectorBridge.xcframework.zip
require_exact_release_property MACOS_ASSET_TEMPLATE 'universal-ai-connector-desktop-{version}-macos-aarch64.dmg'
require_exact_release_property WINDOWS_ASSET_TEMPLATE 'universal-ai-connector-desktop-{version}-windows-x86_64.msi'
require_exact_release_property LINUX_ASSET_TEMPLATE 'universal-ai-connector-desktop-{version}-linux-x86_64.deb'
require_exact_release_property CHECKSUM_SUFFIX .sha256
require_exact_release_property DESKTOP_APP_ID io.github.maneesh888.universalai.connector.desktop
require_exact_release_property DESKTOP_PACKAGE_NAME universal-ai-connector-demo
require_exact_release_property MACOS_SIGNING_IDENTITY_TYPE "Developer ID Application"
require_exact_release_property WINDOWS_SIGNING_POLICY optional-for-alpha
require_exact_release_property JDK_MIN_VERSION 21
require_exact_release_property GRADLE_VERSION 9.6.1
require_exact_release_property KOTLIN_VERSION 2.4.10
require_exact_release_property ANDROID_MIN_SDK 24
require_exact_release_property ANDROID_COMPILE_SDK 36
require_exact_release_property ANDROID_TARGET_SDK 36
require_exact_release_property ANDROID_BUILD_TOOLS_VERSION 36.1.0
require_exact_release_property SWIFT_TOOLS_VERSION 6.0
require_exact_release_property XCODE_MIN_VERSION 16.4
require_exact_release_property IOS_MIN_VERSION 17.0
require_exact_release_property MACOS_BUILD_MIN_VERSION 15
require_exact_release_property WINDOWS_BUILD_MIN_VERSION 2025
require_exact_release_property LINUX_BUILD_MIN_VERSION 24.04

if rg --quiet --no-config '^(.*(PASSWORD|TOKEN|SECRET|PRIVATE_KEY|KEY_CONTENT).*)=' "$RELEASE_PROPERTIES"; then
  echo "Public distribution metadata must not define credential-bearing properties." >&2
  exit 1
fi

require_literal "$ROOT/build.gradle.kts" 'group = providers.gradleProperty("GROUP").get()'
require_literal "$ROOT/build.gradle.kts" 'version = providers.gradleProperty("VERSION_NAME").get()'
require_literal "$ROOT/bridge/build.gradle.kts" 'providers.gradleProperty("VERSION_NAME")'
require_literal "$ROOT/bridge/src/commonMain/kotlin/com/maneesh/universalai/connector/UniversalAiConnector.kt" \
  'const val LIBRARY_VERSION: String = UNIVERSAL_AI_CONNECTOR_VERSION'
require_literal "$ROOT/samples/android/build.gradle.kts" 'versionName = providers.gradleProperty("VERSION_NAME").get()'
require_literal "$ROOT/scripts/check.sh" 'bridge-jvm-$library_version.jar'
require_literal "$ROOT/bridge/build.gradle.kts" 'minSdk = 24'
require_literal "$ROOT/bridge/build.gradle.kts" 'compileSdk = 36'
require_literal "$ROOT/bridge/build.gradle.kts" 'buildToolsVersion = "36.1.0"'
require_literal "$ROOT/samples/android/build.gradle.kts" 'targetSdk = 36'
require_literal "$ROOT/swift-package/Package.swift" '// swift-tools-version: 6.0'
require_literal "$ROOT/swift-package/Package.swift" '.iOS(.v17)'
require_literal "$ROOT/build.gradle.kts" 'kotlin("multiplatform") version "2.4.10"'
require_literal "$ROOT/gradle/wrapper/gradle-wrapper.properties" 'gradle-9.6.1-bin.zip'
require_literal "$ROOT/.github/workflows/ci.yml" 'runs-on: ubuntu-24.04'
require_literal "$ROOT/.github/workflows/ci.yml" 'runs-on: windows-2025'
require_literal "$ROOT/.github/workflows/ci.yml" 'runs-on: macos-15'

for expectation_file in \
  "$ROOT/bridge/src/commonTest/kotlin/com/maneesh/universalai/connector/UniversalAiConnectorTests.kt" \
  "$ROOT/bridge/src/iosTest/kotlin/com/maneesh/universalai/apple/AppleConnectorBridgeTests.kt" \
  "$ROOT/swift-package/Tests/UniversalAiConnectorTests/UniversalAiConnectorTests.swift" \
  "$ROOT/samples/jvm-console/src/test/kotlin/com/maneesh/universalai/samples/jvm/JvmConsoleSampleTests.kt" \
  "$ROOT/samples/android/src/test/kotlin/com/maneesh/universalai/samples/android/AndroidSampleControllerTest.kt"; do
  require_literal "$expectation_file" "$version"
done

coordinate="$group:$(property_value "$RELEASE_PROPERTIES" MAVEN_ARTIFACT_ID):$version"
require_literal "$GUIDE" "$coordinate"
require_literal "$GUIDE" "v$version"
require_literal "$PLAN" '`gradle.properties` owns one canonical SemVer value'
require_literal "$ROADMAP" '| P8 | Production distribution and host integration | In progress |'
require_literal "$ROADMAP" 'P8 is active at P8-A;'
require_literal "$ROOT/README.md" '**Current phase:** P8-A is active'

echo "Distribution metadata is internally consistent for $coordinate."
