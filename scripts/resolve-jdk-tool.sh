#!/usr/bin/env bash
set -euo pipefail

TOOL="${1:-}"

usage() {
  echo "Usage: ./scripts/resolve-jdk-tool.sh <java|jar|javap>" >&2
}

resolve_gradle_java() {
  local java_path

  if [[ -n "${JAVA_HOME:-}" ]]; then
    if [[ -x "$JAVA_HOME/jre/sh/java" ]]; then
      java_path="$JAVA_HOME/jre/sh/java"
    else
      java_path="$JAVA_HOME/bin/java"
    fi

    if [[ ! -x "$java_path" ]]; then
      echo "Contributor environment has an invalid JAVA_HOME: $JAVA_HOME" >&2
      echo "Gradle could not execute its selected Java command: $java_path" >&2
      return 1
    fi
  else
    java_path="$(command -v java || true)"
    if [[ -z "$java_path" ]]; then
      echo "Contributor environment is missing 'java': Java 21 is required by the Gradle build." >&2
      return 1
    fi
  fi

  printf '%s' "$java_path"
}

resolve_selected_jdk_tool() {
  local requested_tool="$1"
  local java_home
  local java_path
  local java_settings
  local java_status
  local tool_path

  java_path="$(resolve_gradle_java)" || return 1

  if [[ -n "${JAVA_HOME:-}" ]]; then
    java_home="$JAVA_HOME"
  else
    java_status=0
    java_settings="$("$java_path" -XshowSettings:properties -version 2>&1)" || java_status=$?
    if [[ "$java_status" -ne 0 ]]; then
      echo "Contributor environment could not inspect Gradle's selected Java executable." >&2
      echo "Resolved Gradle java: $java_path" >&2
      return 1
    fi
    java_home="$(
      printf '%s\n' "$java_settings" |
        sed -n 's/^[[:space:]]*java\.home = //p' |
        head -n 1
    )"
    java_home="${java_home%$'\r'}"
    if [[ -z "$java_home" ]]; then
      echo "Contributor environment could not determine the selected JDK home." >&2
      echo "Resolved Gradle java: $java_path" >&2
      return 1
    fi

    case "$(uname -s)" in
      CYGWIN*|MINGW*|MSYS*)
        if command -v cygpath >/dev/null 2>&1; then
          java_home="$(cygpath -u "$java_home")"
        fi
        ;;
    esac
  fi

  tool_path="$java_home/bin/$requested_tool"
  if [[ ! -x "$tool_path" && -x "$tool_path.exe" ]]; then
    tool_path="$tool_path.exe"
  fi
  if [[ ! -x "$tool_path" ]]; then
    echo "Contributor environment requires a complete selected JDK." >&2
    echo "Could not execute the selected JDK '$requested_tool' tool: $tool_path" >&2
    return 1
  fi

  printf '%s' "$tool_path"
}

case "$TOOL" in
  java)
    resolve_gradle_java
    ;;
  jar|javap)
    resolve_selected_jdk_tool "$TOOL"
    ;;
  *)
    usage
    exit 2
    ;;
esac
