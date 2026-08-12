#!/usr/bin/env bash
set -euo pipefail

UAC_LOCAL_CONFIG_MAX_BYTES=65536

uac_local_config_error() {
  printf '%s\n' "$1" >&2
}

uac_primary_checkout() {
  local repository_root="$1"
  local common_directory
  local common_physical
  local worktree_listing
  local line
  local primary_checkout=""
  local primary_physical
  local primary_common
  local primary_common_physical

  common_directory="$(
    git -C "$repository_root" rev-parse --path-format=absolute --git-common-dir 2>/dev/null
  )" || {
    uac_local_config_error "Could not resolve the Git common directory for local configuration."
    return 2
  }
  if [[ ! -d "$common_directory" ]]; then
    uac_local_config_error "The resolved Git common directory is not a directory."
    return 2
  fi
  common_physical="$(cd "$common_directory" && pwd -P)" || {
    uac_local_config_error "Could not resolve the physical Git common directory."
    return 2
  }

  worktree_listing="$(
    git --git-dir="$common_physical" worktree list --porcelain 2>/dev/null
  )" || {
    uac_local_config_error "Could not enumerate Git worktrees for local configuration."
    return 2
  }
  while IFS= read -r line; do
    if [[ "$line" == "worktree "* ]]; then
      primary_checkout="${line#worktree }"
      break
    fi
  done <<< "$worktree_listing"

  if [[ -z "$primary_checkout" || "$primary_checkout" == *$'\n'* || ! -d "$primary_checkout" ]]; then
    uac_local_config_error "Could not resolve an existing primary Git checkout."
    return 2
  fi
  primary_physical="$(cd "$primary_checkout" && pwd -P)" || {
    uac_local_config_error "Could not resolve the physical primary Git checkout."
    return 2
  }
  primary_common="$(
    git -C "$primary_physical" rev-parse --path-format=absolute --git-common-dir 2>/dev/null
  )" || {
    uac_local_config_error "Could not validate the primary Git checkout."
    return 2
  }
  primary_common_physical="$(cd "$primary_common" && pwd -P)" || {
    uac_local_config_error "Could not validate the primary Git common directory."
    return 2
  }
  if [[ "$primary_common_physical" != "$common_physical" ]]; then
    uac_local_config_error "The primary checkout does not share the active Git common directory."
    return 2
  fi

  printf '%s\n' "$primary_physical"
}

uac_live_env_path() {
  local repository_root="$1"
  local primary_checkout
  local configured_path="${UAC_LIVE_ENV_FILE:-.env.live}"
  local configured_directory
  local configured_directory_physical
  local configured_name

  primary_checkout="$(uac_primary_checkout "$repository_root")" || return $?
  if [[ -z "$configured_path" ||
        "$configured_path" == *$'\n'* ||
        "$configured_path" == *$'\r'* ||
        "/$configured_path/" == *"/../"* ||
        "/$configured_path/" == *"/./"* ]]; then
    uac_local_config_error \
      "UAC_LIVE_ENV_FILE must name a direct child of the primary checkout without traversal."
    return 2
  fi

  if [[ "$configured_path" == /* ]]; then
    configured_name="${configured_path##*/}"
    configured_directory="${configured_path%/*}"
    if [[ -z "$configured_directory" || ! -d "$configured_directory" ]]; then
      uac_local_config_error \
        "UAC_LIVE_ENV_FILE must be within the primary checkout configuration directory."
      return 2
    fi
    configured_directory_physical="$(cd "$configured_directory" && pwd -P)" || {
      uac_local_config_error \
        "UAC_LIVE_ENV_FILE must be within the primary checkout configuration directory."
      return 2
    }
    if [[ "$configured_directory_physical" != "$primary_checkout" ]]; then
      uac_local_config_error \
        "UAC_LIVE_ENV_FILE must be within the primary checkout configuration directory."
      return 2
    fi
  else
    configured_name="$configured_path"
    if [[ "$configured_name" == */* ]]; then
      uac_local_config_error \
        "UAC_LIVE_ENV_FILE must name a direct child of the primary checkout without traversal."
      return 2
    fi
  fi

  if [[ ! "$configured_name" =~ ^\.env\.live(\.[A-Za-z0-9][A-Za-z0-9._-]{0,63})?$ ||
        "$configured_name" == ".env.live.example" ]]; then
    uac_local_config_error \
      "UAC_LIVE_ENV_FILE must use the documented .env.live or .env.live.<name> format."
    return 2
  fi

  printf '%s/%s\n' "$primary_checkout" "$configured_name"
}

uac_live_env_mode() {
  local file_path="$1"
  local mode

  if mode="$(stat -f '%Lp' "$file_path" 2>/dev/null)"; then
    :
  elif mode="$(stat -c '%a' "$file_path" 2>/dev/null)"; then
    :
  else
    uac_local_config_error "Could not inspect local live configuration permissions."
    return 2
  fi
  printf '%s\n' "$mode"
}

uac_validate_live_env_file() {
  local repository_root="$1"
  local file_path="$2"
  local primary_checkout
  local file_name="${file_path##*/}"
  local mode
  local size

  primary_checkout="$(uac_primary_checkout "$repository_root")" || return $?
  if [[ "$file_path" != "$primary_checkout/$file_name" ]]; then
    uac_local_config_error \
      "Local live configuration must be a direct child of the primary checkout."
    return 2
  fi
  if [[ -L "$file_path" ]]; then
    uac_local_config_error "Local live configuration must not be a symbolic link: $file_path"
    return 2
  fi
  if [[ ! -e "$file_path" ]]; then
    uac_local_config_error "Local live configuration is missing: $file_path"
    return 3
  fi
  if [[ ! -f "$file_path" || ! -r "$file_path" ]]; then
    uac_local_config_error "Local live configuration must be a readable regular file: $file_path"
    return 2
  fi
  if git -C "$primary_checkout" ls-files --error-unmatch -- "$file_name" \
    >/dev/null 2>&1; then
    uac_local_config_error "Local live configuration must not be tracked by Git: $file_path"
    return 2
  fi
  if [[ "$(cd "$repository_root" && pwd -P)" != "$primary_checkout" ]] &&
    git -C "$repository_root" ls-files --error-unmatch -- "$file_name" \
      >/dev/null 2>&1; then
    uac_local_config_error \
      "Local live configuration name must not be tracked in the active worktree: $file_name"
    return 2
  fi
  if ! git -C "$primary_checkout" check-ignore -q -- "$file_name"; then
    uac_local_config_error "Local live configuration must be ignored by Git: $file_path"
    return 2
  fi

  mode="$(uac_live_env_mode "$file_path")" || return $?
  if [[ ! "$mode" =~ ^[4567]00$ ]]; then
    uac_local_config_error \
      "Local live configuration permissions must deny group and other access: $file_path"
    return 2
  fi
  size="$(wc -c < "$file_path")"
  size="${size//[[:space:]]/}"
  if [[ ! "$size" =~ ^[0-9]+$ || "$size" -gt "$UAC_LOCAL_CONFIG_MAX_BYTES" ]]; then
    uac_local_config_error \
      "Local live configuration must be at most $UAC_LOCAL_CONFIG_MAX_BYTES bytes: $file_path"
    return 2
  fi
}

uac_live_env_name_allowed() {
  case "$1" in
    OPENAI_API_KEY | OPENAI_LIVE_MODEL | \
      ANTHROPIC_API_KEY | ANTHROPIC_LIVE_MODEL | \
      OPENROUTER_API_KEY | OPENROUTER_LIVE_MODEL | \
      GATEWAY_LIVE_BASE_URL | GATEWAY_API_KEY | GATEWAY_LIVE_MODEL | \
      GATEWAY_LIVE_STRUCTURED_OUTPUT)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

uac_live_env_name_requested() {
  local candidate="$1"
  shift
  local requested_name

  for requested_name in "$@"; do
    if [[ "$candidate" == "$requested_name" ]]; then
      return 0
    fi
  done
  return 1
}

uac_parse_live_env_file() {
  local file_path="$1"
  shift
  local line=""
  local line_number=0
  local name
  local value
  local quote
  local seen_names=" "

  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    if [[ "${#line}" -gt 8192 || "$line" == *$'\r'* ]]; then
      uac_local_config_error \
        "Local live configuration has an invalid line at line $line_number."
      return 2
    fi
    if [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]]; then
      continue
    fi
    if [[ ! "$line" =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]]; then
      uac_local_config_error \
        "Local live configuration has an invalid assignment at line $line_number."
      return 2
    fi
    name="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    if ! uac_live_env_name_allowed "$name"; then
      uac_local_config_error \
        "Local live configuration contains unsupported variable $name at line $line_number."
      return 2
    fi
    if [[ "$seen_names" == *" $name "* ]]; then
      uac_local_config_error \
        "Local live configuration repeats variable $name at line $line_number."
      return 2
    fi
    seen_names="$seen_names$name "

    if [[ "$value" == \"* || "$value" == \'* ]]; then
      quote="${value:0:1}"
      if [[ "${#value}" -lt 2 || "${value: -1}" != "${value:0:1}" ]]; then
        uac_local_config_error \
          "Local live configuration has unmatched quotes at line $line_number."
        return 2
      fi
      value="${value:1:${#value}-2}"
      if [[ "$value" == *"$quote"* ]]; then
        uac_local_config_error \
          "Local live configuration has unsupported quoted content at line $line_number."
        return 2
      fi
    elif [[ "$value" == *\"* || "$value" == *\'* ]]; then
      uac_local_config_error \
        "Local live configuration has unsupported quoting at line $line_number."
      return 2
    fi

    if [[ "$#" -gt 0 ]] && uac_live_env_name_requested "$name" "$@"; then
      if [[ -z "${!name:-}" ]]; then
        printf -v "$name" '%s' "$value"
        export "$name"
      fi
    fi
  done < "$file_path"
}

uac_load_live_environment() {
  local repository_root="$1"
  shift
  local file_path

  file_path="$(uac_live_env_path "$repository_root")" || return $?
  uac_validate_live_env_file "$repository_root" "$file_path" || return $?
  uac_parse_live_env_file "$file_path" "$@"
}

uac_local_config_usage() {
  cat <<'EOF'
Usage: ./scripts/local-config.sh <command>

Commands:
  primary-checkout  Print the dynamically resolved primary Git checkout.
  live-env-path     Print the canonical local live configuration path.
  validate-live-env Validate the canonical file without displaying its values.
EOF
}

uac_local_config_main() {
  local repository_root
  local file_path

  repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
  case "${1:-}" in
    primary-checkout)
      [[ "$#" -eq 1 ]] || return 2
      uac_primary_checkout "$repository_root"
      ;;
    live-env-path)
      [[ "$#" -eq 1 ]] || return 2
      uac_live_env_path "$repository_root"
      ;;
    validate-live-env)
      [[ "$#" -eq 1 ]] || return 2
      file_path="$(uac_live_env_path "$repository_root")" || return $?
      uac_validate_live_env_file "$repository_root" "$file_path" || return $?
      uac_parse_live_env_file "$file_path"
      printf 'Local live configuration is valid: %s\n' "$file_path"
      ;;
    *)
      uac_local_config_usage >&2
      return 2
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  uac_local_config_main "$@"
fi
