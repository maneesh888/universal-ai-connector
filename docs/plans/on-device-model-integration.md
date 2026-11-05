# On-device model integration: research and delivery goals

Research date: **2026-11-05**. Status: **Researched; delivery planned; no implementation activated**.
Milestone effect: **none**.

This track covers the native OS-model work reserved by P11 and the downloaded-model work
reserved by P12 in the [roadmap](universal-ai-connector-v2.md#p11-native-on-device-ai).
The [image/audio track](multimodal-inputs.md) owns media contracts and provider input support.
Separate tracking leaves the roadmap order and one-active-milestone rule intact. P8 remains
in progress; neither this document nor upstream SDK availability closes P8/P9 or activates P11/P12.

## Repository baseline

At inspected base `11a0756232b08058fc429435760180c9f2675712`:

- Kotlin requests accept `List<UniversalAiTextInput>`; Swift accepts `[UniversalAiTextInput]`.
- Delivered adapters cover remote OpenAI, Anthropic, OpenRouter and the generic OpenAI-compatible
  path used for the tested Gateway intersection. A Gateway URL is not proof of in-process inference.
- No native Foundation Models, AICore, llama.cpp or LiteRT-LM adapter/runtime is implemented.
- iOS uses the Swift façade over an iOS XCFramework; macOS desktop currently consumes Kotlin/JVM.
  Existing macOS build tooling and JVM execution do not prove native Apple model access.
- The Kotlin registry, client lifecycle and Swift façade are reusable integration boundaries.
  Provider DTOs, SDK sessions, native handles and platform callbacks must remain internal.

These findings come from the [Kotlin request](../../bridge/src/commonMain/kotlin/com/myadidi/universalai/connector/contract/UniversalAiRequest.kt),
[Swift models](../../swift-package/Sources/UniversalAiConnector/UniversalAiConnectorModels.swift),
[provider registrations](../../bridge/src/commonMain/kotlin/com/myadidi/universalai/connector/internal/provider/BuiltInProviderRegistrations.kt)
and target configuration,
not from upstream product marketing. The milestone definitions already landed in
[PR #89](https://github.com/maneesh888/universal-ai-connector/pull/89).

## Two independently tracked approaches

| Approach | Model ownership | Connector responsibility | Host responsibility | Current project status |
|---|---|---|---|---|
| P11 native OS models | Operating system/service manages model assets and eligibility | Optional platform adapters, conservative capabilities, readiness, response/stream mapping, cancellation and cleanup | OS feature enablement, permissions, foreground lifecycle and supported hardware | Planned; no adapter/device proof |
| P12 downloaded models | Host selects and provisions a licensed compatible model artifact | Optional runtime backend, validation, bounded load/generation/unload and stable failures | Consent, acquisition, storage, deletion, application resource budget | Planned; engine/model not selected |

Both approaches require explicit local execution and no silent cloud fallback or model substitution.
Model provisioning can need network access; offline inference must be tested after provisioning.
Neither approach gives every model image/audio support. Record that support separately per
runtime, model, platform and task using the [media goals](multimodal-inputs.md#goal-register).

## Current-source research

The facts below were checked against current official sources, including the separately supplied
on-device source review. Engineering recommendations and untested integrations are identified as
such; upstream availability is not connector acceptance.

### Native OS model candidates

**Apple Foundation Models.** The framework now includes access to server models as well as the
on-device model. A local-inference adapter must explicitly use `SystemLanguageModel` and expose
its actual availability; framework use alone is insufficient to establish execution location.
Apple documents model availability and runtime inspection such as `contextSize`; do not assume
a fixed context size across OS/model updates. Recheck device, OS, language/region, enablement and
readiness for each target. The current iOS package minimum does not imply every supported host
can execute this optional native path.
[Framework overview](https://developer.apple.com/foundation-models-framework/),
[official updates](https://developer.apple.com/documentation/Updates/FoundationModels),
[SystemLanguageModel](https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel),
[context size](https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel/contextsize).

Engineering recommendation: start the native evaluation with the OS-managed text path, preserving
plain-text defaults and explicitly requested structured output. Verify accumulated streaming,
refusal/context errors, cancellation, host restrictions and native macOS packaging before claiming
support. Server executors and custom provider wrappers are outside the initial local path.

**Android ML Kit GenAI Prompt API / AICore.** The setup page currently shows
`genai-prompt:1.0.0-beta4` and API level 26+, but direct-model eligibility is restricted to listed
devices. It documents text/image input, streaming and optional structured-output configuration.
The GenAI overview restricts inference to the top foreground app, including blocking use from a
foreground service, and documents quotas. Query readiness and model identity, and use the current
device list rather than inferring support from Android API level or a generic Gemini name.
[Prompt setup](https://developers.google.com/ml-kit/genai/prompt/android/get-started),
[GenAI overview and device/foreground/quota rules](https://developers.google.com/ml-kit/genai).

**Android terms are a selection prerequisite.** The published additional terms include age/client
audience restrictions, restrictions on clinical/medical use and competing products, and production
restrictions for services designated preview/experimental or similarly. Evaluate their application
to the intended connector and consuming applications before choosing this integration or claiming
production eligibility; beta documentation is not an eligibility decision. No agreement is accepted
and no conclusion that this project is permitted or prohibited is made by this research.
[ML Kit GenAI additional terms](https://developers.google.com/ml-kit/genai-terms).

### Downloaded-model candidates

| Candidate | Current upstream evidence | Selection work still needed |
|---|---|---|
| llama.cpp | Official Android bindings/examples and Apple XCFramework build tooling exist. The engine repository uses MIT; model weights require their own license review. | Pin a revision and model artifact; verify compatible GGUF/projector formats, native bridge and packaging, acceleration, cancellation, resource use and retained notices. Upstream examples are not this project's device proof. |
| LiteRT-LM | Official language matrix marks Kotlin stable for Android/JVM and Swift early preview for native iOS/macOS. Swift documentation describes native integration and Metal acceleration. | Assess preview/API-change risk and per-platform packaging with pinned releases and compatible model assets. The Kotlin API is not a commonMain/Kotlin-Native API; keep SDK/engine objects inside platform backends. |

Sources: [llama.cpp Android integration](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md),
[Apple build tooling](https://github.com/ggml-org/llama.cpp/blob/master/build-xcframework.sh),
[engine license](https://github.com/ggml-org/llama.cpp/blob/master/LICENSE),
[LiteRT-LM supported language APIs](https://github.com/google-ai-edge/LiteRT-LM),
[Swift integration](https://developers.google.com/edge/litert-lm/swift).

Engineering recommendation: evaluate the same small quantized text workload and bounded context
on both engines before selection. Runtime support for images/audio needs a compatible model and
platform implementation plus the media track's contract and task proof. Do not infer it from an
engine-wide feature list. No dependency or bundled model is selected; do not download weights
as part of documenting this research. Initial P12 remains text-first under the existing roadmap.

## Goal register

Every row remains **Not started** unless evidence is linked to a reviewed implementation.
Research completion is recorded separately from runtime delivery.

| ID | Goal / milestone | Prerequisites | Observable acceptance |
|---|---|---|---|
| OD-01 | P11 activation and native contract | Preceding roadmap gates; explicit target platforms | Recorded eligibility/readiness/error/capability mapping and applicable terms review; plain-text defaults; opt-in structured output; provider-neutral Kotlin/Swift boundary; no runtime behavior claimed by planning |
| OD-02 | Apple native adapter | OD-01; supported real hardware; resolved macOS package boundary if claimed | Fake-backed mapping and accumulated-stream tests; real-device text/structured/cancellation proof; local-only inference after provisioning; no SDK types in public API |
| OD-03 | Android native adapter | OD-01; eligible AICore device | Fake-backed readiness/download/error mapping; supported-device text/stream/cancellation proof; foreground/quota limits documented; no model substitution |
| OD-04 | Native lifecycle and host acceptance | Each delivered native adapter | Concurrent request isolation, close races, exactly-once completion and cleanup; thin host consumers; offline proof; explicit unsupported platforms |
| OD-05 | P12 runtime/model selection | P11 and separate P12 activation; model/runtime licenses; real-device access | Reproducible comparison of both candidate engines and exact model artifacts; justified selection and rejected alternatives; no automatic heavy core dependency |
| OD-06 | Optional backend and bounded resource ownership | OD-05; measured budget and compatible model boundary | Remote-only consumers acquire no runtime/weights; validate before load; one resident model and one generation initially; stable resource rejection; host-owned acquisition/storage |
| OD-07 | Load/generate/unload lifecycle | OD-06 | Coalesced loads; cancellation during loading/generation; safe teardown of native work; repeated unload/reload; idle/background/memory-pressure policy; no leaks/use-after-free |
| OD-08 | Downloaded-model acceptance | OD-06/07 and each claimed physical platform/model configuration | Offline generation; peak and retained whole-process memory; latency/throughput and thermal/battery measurements; long bounded context, rejection and recovery; published limitations |

OD-02 and OD-03 can be tracked separately; activation still selects one bounded implementation
package at a time. OD-05 must select a runtime/model intersection before OD-06 promises support.
The existing P11/P12 roadmap acceptance criteria remain authoritative if this research checklist
leaves a detail implicit.

## P11 pre-activation delivery blueprint

This section converts OD-01 through OD-04 into bounded future packages. It is planning only:
P11 remains `Not started`, no package below is active, and no SDK dependency or native behavior is
authorized by naming the packages. Under the current roadmap, execute them one at a time only after
P8, P9 and P10 have completed and a separate P11-A change activates P11.

### P11-A: Native contract, platform selection and activation

Before adding an SDK, revalidate the current Apple and Android sources, device eligibility, terms,
OS/SDK requirements and host restrictions. Record the first adapter and exact physical proof
devices; freeze the provider-neutral readiness, capability, error, output-format and session-
ownership semantics; and resolve the native macOS consumption/packaging path if macOS is claimed.
The activation change updates the roadmap so P11 is the sole milestone marked `In progress` and
identifies P11-A as current. It may add deterministic contract/fake coverage, but it does not claim
native inference from types, fixtures, compilation, a simulator or an emulator.

P11-A accepts when OD-01 decisions are recorded, plain-text and explicitly structured behavior are
distinguished without prompt rewriting, unavailable states and stable failures are exhaustive for
the selected first platform, and the verification plan names real hardware. The unselected adapter,
P12 engines, native media and automatic cloud routing remain unsupported.

### P11-B: Apple OS-managed model adapter

Add the optional Apple adapter behind the shared contract without exposing Foundation Models types.
Cover availability and capability mapping, plain-text and opt-in structured requests, accumulated-
snapshot streaming conversion, refusal/context failures, cancellation, exactly-once termination and
cleanup with deterministic fakes. Acceptance additionally requires the selected physical Apple host
to prove local text/structured/streaming/cancellation behavior after provisioning, with no silent
server executor, cloud fallback or model substitution. Native macOS is claimed only if P11-A resolved
its packaging boundary and this package verifies the supported consumption path.

### P11-C: Android OS-managed model adapter

Add the optional ML Kit GenAI Prompt/AICore adapter behind the same shared contract without exposing
SDK task/session types. Cover eligibility, readiness/download states, capability and error mapping,
plain-text and supported structured behavior, streaming, cancellation, quotas, foreground rules,
exactly-once termination and cleanup with deterministic fakes. Acceptance additionally requires an
eligible physical Android device and the recorded terms decision to prove the claimed local path;
API level, compilation or emulator success alone is insufficient.

P11-B and P11-C are independently reviewable. P11-A records which one executes first; the other does
not run concurrently if it would change the same shared contract or lifecycle surface.

### P11-D: Native lifecycle, hosts and closeout

Reconcile both delivered adapters through concurrent-request isolation, close races, repeated
session creation/teardown, cancellation and exactly-once terminal behavior. Verify thin supported
host consumers, offline inference after provisioning, limitation documentation, optional dependency
isolation and explicit unsupported-platform behavior. Close P11 only after every claimed platform
has exact physical-device/native-host evidence and the roadmap's Release gates pass; do not infer
one platform's proof from the other.

## P10 dependency and sequential activation boundary

The current roadmap makes completed P10 a sequencing prerequisite for P11. The technical dependency
is narrower but material:

- P10-A owns the canonical transition from `List<UniversalAiTextInput>` to ordered typed content
  parts, the Kotlin and Swift public request shapes, the JSON version/migration decision, capability
  names and limits, and shared validation. A native adapter built against the earlier shape would
  need coordinated rework across common Kotlin, the Swift facade, schemas, fixtures and adapter
  tests when P10-A lands.
- P10-B through P10-D own remote-provider media translation and media-source lifecycle. They are not
  prerequisites for P11's text-only readiness, local execution, native streaming conversion,
  cancellation or terminal arbitration. P11 must nevertheless reject native image/audio claims
  until the P10 contract and a runtime-specific media package are both accepted.
- P11 readiness/session ownership, accumulated-snapshot conversion, platform eligibility mapping,
  native failures and physical-device proof do not belong in P10 and can be designed while P10 is
  being planned. Product code for them still requires P11 activation.

Under the existing one-active-milestone rule, exact activation is: complete P8, P9 and P10; refresh
the dated SDK/terms/device findings; resolve every P11-A decision; then land a separate roadmap
change that marks P11 alone `In progress`, names P11-A as current, and records milestone effect
`advances`. Runtime packages begin only after that transition is authoritative.

This preserves the requested sequential lifecycle: land the researched requirements first; execute,
test and merge the easier P10 image/audio track through its accepted packages; then refresh the P11
research and activate P11-A. No P11 implementation or SDK prototype runs in parallel with P10.

## Evaluation and evidence record

For each evaluated combination record:

| Area | Required record |
|---|---|
| Identity | Connector commit; engine/SDK version; model artifact hash/version, format/quantization and license; prompt/template settings |
| Host | Physical hardware, OS, relevant accelerator, available capacity estimate and host-app headroom |
| Resource envelope | Weight, context/KV-cache and temporary/native/GPU allocations; peak whole-process footprint; post-unload retained memory; measured safe input/output/concurrency bounds |
| Behavior | First output latency, generation rate, task quality, stream semantics, structured-output guarantees, cancellation and exactly-once termination |
| Lifecycle | Cold/warm load, failed/partial load, repeated cycles, unload, background/foreground and memory-pressure recovery |
| Privacy/packaging | Offline-after-provisioning proof; no prompt/response egress or cloud fallback; optional dependency isolation; distribution and license/notice obligations |

No universal RAM threshold, throughput target or supported model size is selected yet. Model file
size and advertised RAM are not allocation budgets. Use measured workload-specific bounds and
stable refusal when admission cannot be established; OS memory warnings are not a safe allocator.

## Decisions to resolve at activation

1. **Native delivery order and devices:** first Apple or Android path, exact eligible physical
   devices and supported OS/SDK. Native macOS requires a separate consumption-path decision.
2. **API shape:** capability/readiness exposure and session ownership while retaining one client
   and one Swift façade; distinguish native constrained output from prompt-and-validate strategies.
3. **Runtime/model intersection:** compare llama.cpp and LiteRT-LM using the same workload before
   selecting a backend, formats, quantization, accelerator and model license.
4. **Resource policy:** measured budgets, host control over idle/background unload, safe cancellation
   and unsupported workloads. No background/keyboard execution promise is implied.
5. **Media intersection:** if an on-device image/audio task is wanted, activate that explicit
   intersection and satisfy the media contract plus runtime-specific proof. P11/P12 text proof
   does not complete image/audio acceptance.

## Exclusions and follow-up boundary

No code, SDK/runtime dependency, model asset, new release or device inference is delivered here.
Windows native AI, desktop downloaded-model support, automatic cloud routing, realtime sessions,
media generation, training, model catalog/download UI and OpenKeyboard integration require their
own scoped decisions. Host capture, playback, permissions, storage and transcoding stay outside
this package. Preserve the plain-text default; do not inject JSON instructions or silently strip
JSON generated by a wrapper or model.

## Sources and refresh policy

Official sources are checked as of the research date. Pin exact SDK/runtime revisions and recheck
eligibility, licenses, limits and API maturity when a delivery package activates. Research
references do not select dependencies. These are source-backed research findings, not measured
performance or completed platform acceptance.

- [Apple Foundation Models overview](https://developer.apple.com/foundation-models-framework/)
- [Apple Foundation Models documentation](https://developer.apple.com/documentation/foundationmodels)
- [Android ML Kit Prompt API](https://developers.google.com/ml-kit/genai/prompt/android)
- [Android Prompt API setup](https://developers.google.com/ml-kit/genai/prompt/android/get-started)
- [ML Kit GenAI additional terms](https://developers.google.com/ml-kit/genai-terms)
- [llama.cpp official repository](https://github.com/ggml-org/llama.cpp)
- [llama.cpp multimodal tooling](https://github.com/ggml-org/llama.cpp/tree/master/tools/mtmd)
- [LiteRT-LM overview](https://developers.google.com/edge/litert-lm/overview)
