#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT/docs/DEVELOPMENT_WORKFLOW.md"
DEVELOP_SKILL="$ROOT/.agents/skills/develop-universal-ai-connector/SKILL.md"

require_phrase() {
  local phrase="$1"
  local file="$2"
  local message="$3"

  if ! rg --fixed-strings --quiet -- "$phrase" "$file"; then
    echo "$message" >&2
    exit 1
  fi
}

require_phrase 'one compact execution checkpoint visible' "$WORKFLOW" \
  'The workflow must keep one compact user-visible execution checkpoint.'
require_phrase 'Run the smallest affected test after each coherent fix' "$WORKFLOW" \
  'The workflow must require incremental affected tests while fixing.'
require_phrase 'Retry a failed command only after a code' "$WORKFLOW" \
  'The workflow must reject speculative retries.'
require_phrase 'Before the Release full gate and independent review, freeze the candidate.' "$WORKFLOW" \
  'The workflow must freeze the final candidate before Release proof.'
require_phrase 'Do not manually repeat the same gate' "$WORKFLOW" \
  'The workflow must avoid immediate duplicate hook gates.'
require_phrase 'A changed UI surface still' "$WORKFLOW" \
  'The workflow must retain changed-UI proof without unrelated UI work.'

require_phrase 'one compact checkpoint visible' "$DEVELOP_SKILL" \
  'The development skill must keep a compact visible checkpoint.'
require_phrase 'smallest affected test after each coherent fix' "$DEVELOP_SKILL" \
  'The development skill must require incremental affected tests.'
require_phrase 'instead of rerunning speculatively' "$DEVELOP_SKILL" \
  'The development skill must reject speculative retries.'
require_phrase 'Freeze a Release candidate before the full gate and independent review' "$DEVELOP_SKILL" \
  'The development skill must freeze the Release candidate.'
require_phrase 'do not collect unrelated UI evidence' "$DEVELOP_SKILL" \
  'The development skill must scope UI evidence.'

echo 'Agent execution workflow policy checks passed.'
