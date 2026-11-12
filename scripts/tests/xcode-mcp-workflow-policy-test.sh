#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
AGENTS="$ROOT/AGENTS.md"
WORKFLOW="$ROOT/docs/DEVELOPMENT_WORKFLOW.md"
DEVELOP_SKILL="$ROOT/.agents/skills/develop-universal-ai-connector/SKILL.md"
REVIEW_SKILL="$ROOT/.agents/skills/review-verify-merge-pr/SKILL.md"
REVIEWER="$ROOT/.codex/agents/pr-reviewer.toml"
PR_TEMPLATE="$ROOT/.github/pull_request_template.md"

require_phrase() {
  local phrase="$1"
  local file="$2"
  local message="$3"

  if ! rg --fixed-strings --quiet -- "$phrase" "$file"; then
    echo "$message" >&2
    exit 1
  fi
}

require_phrase 'The November 12, 2026 Xcode 27.1 capability check exposed 54 tools' "$AGENTS" \
  'AGENTS.md must retain the dated Xcode MCP capability baseline.'
require_phrase 'Catalog discovery or a successful project read' "$AGENTS" \
  'AGENTS.md must not promote safe reads into unexecuted MCP proof.'
require_phrase 'MCP is assistive, never a replacement for repository gates.' "$AGENTS" \
  'AGENTS.md must preserve the repository-script authority boundary.'
require_phrase 'scripts and all affected Kotlin, JVM, Android, Swift, packaging, and consumer checks.' "$AGENTS" \
  'AGENTS.md must retain the cross-platform verification matrix.'
require_phrase 'Serialize MCP test or Simulator activity with every other Simulator-backed task' "$AGENTS" \
  'AGENTS.md must serialize MCP Simulator work across project worktrees.'
require_phrase 'Runtime or visual acceptance through MCP requires direct screenshots' "$AGENTS" \
  'AGENTS.md must require direct screenshot evidence for runtime acceptance.'
require_phrase 'must not merge until the independent' "$AGENTS" \
  'AGENTS.md must retain the exact-head review and human merge hold.'

require_phrase 'Xcode 27 MCP evidence boundary' "$WORKFLOW" \
  'The development workflow must define an Xcode MCP evidence boundary.'
require_phrase 'Keep the scripts intact; do not translate only their Apple steps' "$WORKFLOW" \
  'The workflow must reject partial MCP replacement of full scripts.'
require_phrase 'A project-read session alone does not require the Simulator hold.' "$WORKFLOW" \
  'The workflow must scope serialization to test and Simulator operations.'
require_phrase 'explicitly approves the current exact head' "$WORKFLOW" \
  'The workflow must require exact-head owner approval for MCP runtime candidates.'

require_phrase 'MCP never replaces a selected repository gate.' "$DEVELOP_SKILL" \
  'The development skill must preserve repository gates.'
require_phrase 'stop before merge until the repository owner explicitly approves that exact head.' "$DEVELOP_SKILL" \
  'The development skill must stop before unapproved MCP-runtime merges.'
require_phrase 'explicit repository-owner approval bound to the' "$REVIEW_SKILL" \
  'The review skill must gate readiness on exact-head owner approval.'
require_phrase 'a general continuation' "$REVIEW_SKILL" \
  'The review skill must not infer merge approval from continuation.'
require_phrase 'retained Kotlin/JVM/Android and repository-script coverage' "$REVIEWER" \
  'The independent reviewer must inspect retained cross-platform evidence.'
require_phrase '## Xcode MCP evidence' "$PR_TEMPLATE" \
  'The pull-request brief must record Xcode MCP evidence or non-use.'

if rg --fixed-strings --quiet 'MCP replaces the repository scripts' \
  "$AGENTS" "$WORKFLOW" "$DEVELOP_SKILL" "$REVIEW_SKILL"; then
  echo 'Workflow policy must never claim that MCP replaces repository scripts.' >&2
  exit 1
fi

echo 'Xcode MCP workflow policy checks passed.'
