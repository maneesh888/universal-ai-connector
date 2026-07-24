---
name: plan-universal-ai-work-package
description: Create a concise, source-bound work-order form from the Universal AI Connector roadmap and relevant work-package plan. Use when the user asks what to do next, requests a roadmap-derived task plan, or needs a bounded handoff before implementation; do not use for an already clear requested implementation.
---

# Plan a Universal AI Work Package

Produce one compact read-only work order without activating or implementing a milestone.

## Read minimal sources

1. Resolve the repository root and inspect `git status --short --branch`.
2. Read `AGENTS.md`.
3. Inspect only `Status`, `Milestones`, and the relevant milestone section in `docs/plans/universal-ai-connector-v2.md`.
4. Follow the roadmap link to the active or requested work-package plan.
5. Read only its status, objective, scope, current package, applicable verification, acceptance, and proof-limit sections.
6. If no milestone is active, identify the next planned package and state that implementation requires activation. Do not activate it.
7. Compute source digests with `git hash-object` for the roadmap, selected plan, and `AGENTS.md`.

Read complete source files only when targeted sections are materially ambiguous or inconsistent.

## Keep planning non-blocking

- Do not edit files, install hooks, run tests, access GitHub, or spawn another agent.
- Do not repeat roadmap prose; cite paths and headings.
- Do not ask for information already available in the repository.
- If implementation is already requested and clearly bounded, return control immediately rather than creating another planning gate.
- Surface only a decision that would materially change scope, architecture, proof, or lifecycle.

## Return this form

Keep the complete response under 500 words:

```text
Work package:
Roadmap state:
Objective:
Requirement sources:
Source digests:

In scope:
Out of scope:
Affected surfaces:
Likely files/modules:

Mode: Fast | Standard | Release
Targeted verification:
Release-only deferred gates:
Proof limits:

Lifecycle: planning only | autonomous implementation | narrowed by explicit opt-out
Blocking decision: none | concise decision
Next action:
```

Choose Release only for readiness, merge, milestone closeout, explicit release verification, or a surface whose active plan requires exact-head Release proof. Record explicit opt-outs rather than asking for routine commit, push, PR, readiness, or merge confirmation. A roadmap activation prerequisite is the first implementation step once implementation is requested; do not turn it into an extra confirmation unless its scope is ambiguous.

The implementation agent may rely on the work order while all listed digests match. A digest mismatch requires refreshing only the changed source and affected form fields.
