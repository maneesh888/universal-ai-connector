# P8-C API acceptance record

## Decision and completion boundary

On September 29, 2026, the user explicitly required API functionality rather than further UI
testing and requested P8-C closeout. This scoped decision replaces P8-C's former single-head
interactive desktop acceptance: deterministic consumer/controller coverage and successful live
library API suites on Windows, macOS, and Ubuntu are accepted. Interactive UI and native
credential-store exercises are optional. P8-C's delivered console/desktop implementation and
credential boundaries remain unchanged. P8-D through P8-L remain `Not started`; P8 overall remains
`In progress`, with no next package activated.

This documentation-only closeout accepts the revision-bound runs below plus PR #77's successful
resulting-main CI. It does not claim that all hosts ran live tests on a common revision or on the
closing documentation head. That head still requires the full local gate, independent review,
required CI, guarded merge, and resulting-main verification. The closing PR brief owns those
self-referential identifiers. Later distribution and release gates retain their own acceptance.

## Source identities and deterministic evidence

All live runs below executed on September 28, 2026.

| Host | Tested source | Deterministic evidence |
|---|---|---|
| Windows 10 x64, JDK 21 | `3e1a71a3e26984750b234b3548ccd3238795c2c9` | Final PR #77 mandatory quick gate passed, including script/security regressions and 81 Gradle tasks |
| macOS ARM64, JDK 21 | `4c9277a4afc1fe90a61b17971f95f75ee1816cfd` | 380 JVM tests: bridge 346, host controller 18, desktop 10, console 6; zero failures/errors/skips |
| Ubuntu 24.04 ARM64 VM, JDK 21 | `4c9277a4afc1fe90a61b17971f95f75ee1816cfd` | Same 380-test matrix; zero failures/errors/skips |

Windows evidence is contributor-attested in [PR #77](https://github.com/maneesh888/universal-ai-connector/pull/77).
The macOS/Ubuntu execution was observed in the closeout task and summarized locally in
`/Volumes/1TB/CodexTaskCaches/tmp/uac-p8c-proof-20260928/`: `VERIFICATION.md`, `macos-tests.json`,
`macos-live-test-counts.json`, `ubuntu-final-live-status.json`, `ubuntu-extra-evidence.json`, and
`progress.json`. These local files are supporting provenance, not a required consumer dependency.
The final paragraph of that earlier `VERIFICATION.md` predates the final Windows evidence and
API-only decision; the decision in this committed record supersedes its old pending-status claim.
No raw logs, credentials, or provider response bodies are part of this committed record.

The macOS/Ubuntu deterministic command was the Gradle wrapper with
`:bridge:jvmTest :samples:host-controller:test :samples:desktop:test :samples:jvm-console:test`.
Live execution used the canonical `./scripts/check-live.sh openai`, `anthropic`, `openrouter`, and
`gateway` routes, preserving exact-head and clean-checkout checks. macOS/Ubuntu also ran the
console flow with exact model discovery/selection and minimal connection response on all four
routes; response bodies were discarded.

## Live API results and limits

| Route | Exact model | Windows | macOS | Ubuntu |
|---|---|---|---|---|
| OpenAI | `gpt-5.6-luna` | 7/7 passed | 7/7 passed | 7/7 passed |
| Anthropic | `claude-sonnet-5` | 7/7 passed on retry | 7/7 passed | 7/7 passed on retry |
| OpenRouter | `google/gemini-2.5-flash-lite` | 8/8 passed | 8/8 passed | 8/8 passed |
| Generic Gateway route | See deployment scope below | 6/6 passed | 6/6 passed | 6/6 passed |

Each host completed 28 distinct live cases, excluding repeats. Suites cover discovery, responses,
streaming, cancellation, canonical errors, and supported structured output. All final reported
suites passed; first failures are retained below rather than counted as passes.

- Windows used a hosted OpenRouter-compatible endpoint for its six Gateway adapter cases, with
  structured output disabled. This proves that configured generic adapter route, not the separate
  loopback Gateway deployment. PR #77 does not identify the exact Gateway-route model in its final
  brief; no model identity or deployment equivalence is inferred for that route.
- macOS and Ubuntu used the actual configured Gateway with exact model `gemma2:2b` and structured
  output enabled. Ubuntu's first attempt could not reach the Mac's loopback-only service. A
  temporary relay restricted to the VM's private-network address forwarded bytes to that same
  deployment; no provider/model substitution was used. Both relays were stopped and their
  listeners verified closed after the successful six-case run.
- Windows and Ubuntu each had an initial Anthropic structured-output failure before an immediate
  unchanged-head retry passed all seven cases. Ubuntu reported
  `minimalStructuredOutputTranslatesToGovernedCanonicalJson` / `malformedStructuredResponse`.
  The intermittent failure's cause is unestablished; no fix is claimed by this closeout.
- Local live execution is contributor-attested. GitHub validates retained evidence and runs
  secretless deterministic CI; it does not independently repeat these provider calls.
- Credentials stayed in canonical host-local configuration or process environments. Ubuntu inputs
  were process-only and its live configuration file remained empty. No credential or provider
  response body retained. Optional GUI observations do not establish signed/package distribution.

## Merged baseline and closeout scope

PR #77 merged as `f963ebf71c5b3e66473972ceb7660e6ebf10095e`. Its resulting-main
[CI run 36490613221](https://github.com/maneesh888/universal-ai-connector/actions/runs/36490613221)
completed successfully: Repository hygiene, Apple + JVM (macOS), JVM (Windows), JVM + Android
(Linux), and Required checks all passed.

Between the macOS/Ubuntu test revision and this merged baseline, changes concern contributor
verification/configuration tooling, documentation, and the Windows desktop renderer default;
connector runtime sources and the shared host controller are unchanged. The accepted baseline CI
covers the merged tooling. This closeout changes documentation only and does not rerun live or UI
suites. It makes no new distribution, signing, native desktop library, or later-package claim.
