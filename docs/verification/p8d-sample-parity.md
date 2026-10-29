# P8-D sample behavior parity

## Scope and case catalog

This candidate proposes P8-D completion, conditional on its complete exact-head Release gates
and guarded merge. It does not publish a package or establish remote consumption.
`samples/parity/cases.json` is the authoritative catalog of twelve observable cases:
supported selection, supported empty, unsupported manual entry, transition back to supported,
safe errors and retry, discovery cancellation, connection cancellation/deactivation, no model
substitution, credential clearing/redaction, failed clearing, busy-action concurrency, and
configuration isolation.

The catalog binds each case to existing host-native assertions in the iOS view model, Android
controller, and shared JVM/desktop controller. The case definitions describe shared outcomes;
they do not require shared UI code or identical display strings. Supplemental bindings verify
the real console orchestration and desktop secure-store adapter. P8-D adds explicit busy-action
tests on all three controllers and an iOS failed-clear assertion. Review follow-up exercises iOS clear during suspended discovery, including cancellation, close and rejection of its late result, and checks valid endpoint/provider isolation with an existing selection and credential on every controller. The busy-action check exposed
Android closing a cancelled client both during discard and again in the retired operation's
`finally` block. Android now matches the JVM controller: discard owns cancelled-client cleanup,
the current operation owns normal completion cleanup, and cleanup exceptions cannot escape into
host state management.

`./scripts/check-sample-parity.sh` executes the mapped Gradle and iOS suites and rejects missing,
failed, skipped, duplicate, or renamed required test results. It reads Gradle JUnit XML and
Xcode's structured test results, not success-looking log text. `--kotlin` and `--ios` run the
corresponding subset; `--catalog` only validates coverage bindings and is not execution proof.
The Python report subcommands are internal post-test steps, not independent execution evidence.

The quick gate checks the catalog and Kotlin evidence after its existing Gradle tasks. The full
gate additionally executes the iOS parity route using its already-built XCFramework. Ordinary CI
retains the same read-only and credential-free boundary. Result bundles stay outside Git.

## Host differences and ownership

- iOS deactivation resets its visible interaction state to idle; Kotlin hosts retain an explicit
  cancellation state. Both invalidate the operation, prevent late publication, preserve credentials
  until explicit clearing, and permit the next host-owned action. This presentation difference
  does not change the shared connection sequence.
- The iOS view model reuses a client for one configuration. Kotlin controllers close each operation's
  client and close their credential store at controller shutdown. Client lifetime remains host-owned;
  cancellation and configuration replacement cannot let an old operation overwrite current state.
- iOS Keychain, Android Keystore, and desktop secure-store persistence remain separate host services.
  Deterministic parity does not establish emulator hardware protection or native desktop service
  availability; those evidence boundaries remain with their original acceptance records.

## Historical live provenance

P8-A's actual sample proof is recorded in PR #69 at
`38e9f735603ce12f18a7fefa3f52bc06e98cd66c`: OpenAI `gpt-5.6-luna` and Anthropic
`claude-sonnet-5`. P8-B's Android acceptance is recorded in PR #75 at
`64ab3ef84c05a01dc9dfeaf2b8de8ef59bdd69d1`, with actual sample selections
`gpt-5.6-luna` and `claude-sonnet-5`; its adapter gates also identify OpenRouter
`google/gemini-2.5-flash-lite` and Gateway `gemma2:2b`.
The existing [P8-C API acceptance](p8c-api-acceptance.md) retains Windows
`3e1a71a3e26984750b234b3548ccd3238795c2c9` and macOS/Ubuntu
`4c9277a4afc1fe90a61b17971f95f75ee1816cfd`, including exact direct-provider models,
Gateway deployment limits, and initial failures. Its API-only, cross-revision acceptance remains
valid for P8-C; these historical runs are not relabeled as execution on the P8-D head. The Windows
Gateway model was not recorded, so no exact Gateway-model identity is inferred for that run.

## Current verification state

Catalog validation, three fail-closed checker regressions, repository hygiene, and all twelve
Android/shared-JVM-controller cases pass locally. Console and desktop-storage bindings also pass.
The first Android run exposed the cancellation cleanup difference described above; the corrected
run passed. The initial exact-head full gate and all four local live routes passed at `dd1ca480aca334287ac32388a3c77788a9e8f14c`. Independent review identified active-clear and configuration-isolation assertion gaps; the strengthened candidate requires fresh exact-head Release evidence. P8-D completion is proposed only; it becomes authoritative after the complete deterministic
matrix, applicable local-live routing, required CI, independent review, guarded merge, and
resulting-main checks pass. The closing PR owns the exact final head and run identities.
