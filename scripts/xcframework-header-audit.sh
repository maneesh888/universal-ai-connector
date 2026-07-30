#!/usr/bin/env bash

uac_reject_xcframework_header_pattern() {
  local header="$1"
  local pattern="$2"
  local matched_message="$3"
  local scan_status

  if grep -Eq -- "$pattern" "$header"; then
    printf '%s\n' "$matched_message" >&2
    return 1
  else
    scan_status=$?
    if (( scan_status != 1 )); then
      printf 'XCFramework header scan could not complete for %s (grep exit %d).\n' \
        "$header" \
        "$scan_status" >&2
      return "$scan_status"
    fi
  fi
}
