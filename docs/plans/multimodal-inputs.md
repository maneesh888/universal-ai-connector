# P10 Multimodal Inputs

## Status and activation gate

Status: `Planned`; P10 is not active.

P0-P7 are completed, P8 is in progress, P9 is not started, and P10 is a post-alpha roadmap item. This plan
records the future priority without activating a milestone or changing the shipped text-only
contract. P10 may become the sole roadmap milestone marked `In progress` only in a separate P10-A
change after the preceding milestones have completed.

Research date: **2026-11-05**. Status: **Researched; delivery planned**. Current official-source findings
and both independent research inputs are reconciled below. This activates no implementation;
milestone effect: **none**.

The separate [on-device research track](on-device-model-integration.md) covers P11 native OS models
and P12 downloaded runtimes. Those adapters are not prerequisites for researching this contract,
and media support does not follow automatically from local text inference. Roadmap activation
order remains unchanged.

## Objective

Extend the canonical request model from ordered text-only input to ordered typed content parts,
starting with images and audio. A host application can submit media to a provider that supports it,
while a provider/model that does not support that media fails locally with a stable capability or
validation error before any network request.

This is a connector feature, not a media-processing framework. Capture, playback, editing,
transcoding, waveform/DSP analysis, storage, and user-interface behavior remain host-owned.

## Initial acceptance scope

| Medium | P10 acceptance | Explicit boundary |
|---|---|---|
| Text | Ordered text parts remain supported and compatible with the existing text-only API. | No regression in existing provider or host behavior. |
| Image | Typed image input with an allowlisted MIME type, bounded bytes/dimensions where available, and a byte source or provider-supported reference. | A provider adapter only translates formats and sources it has deterministic evidence for. |
| Audio | Typed audio input with an allowlisted MIME type, bounded bytes/duration where available, and a byte source or provider-supported reference. | Transcription and provider-native audio understanding are provider capabilities, not assumed universal behavior. |

The canonical model must retain original part ordering and associate each part with an allowed
input role. Media source ownership, retry eligibility, and cleanup must be explicit. Streaming and
cancellation must not retain a media buffer after the request reaches a terminal state or the
client closes.

## Provider and host rules

- Capabilities identify support per provider/model for image input, audio input, transcription,
  and provider-native audio analysis; unknown capability values remain distinguishable.
- An adapter rejects a media type, source form, MIME type, or size it does not support before
  dispatch. It never silently drops a part, converts it to text, or substitutes a provider default.
- Provider DTOs, upload identifiers, local file paths, byte content, and authorization data stay
  internal and are excluded from public errors, diagnostics, fixtures, logs, and live evidence.
- Host applications own capture, permissions, media selection, buffering, playback, storage, and
  any required transcoding. The connector accepts already-prepared media and exposes no platform
  media framework in its public Kotlin or Swift API.
- Byte-backed and reference-backed input must have documented ownership and one-shot/retry
  semantics. A request cannot replay a consumed non-rewindable source without an explicit safe
  source contract.

### Capability discovery and selected-model state

The connector must expose the selected model's effective capabilities through the existing
provider-neutral Kotlin and Swift boundaries so a host can enable or disable relevant features.
This is a semantic contract, not a prescribed visual design, and UI gating never replaces connector
validation when a request begins.

Keep the evidence layers distinct:

1. **Model and endpoint evidence** says whether the exact selected provider, endpoint, model and API
   version support the task and source form.
2. **Connector evidence** says whether the installed adapter implements and has verified the exact
   translation, validation, cancellation and cleanup path.
3. **Host readiness** says whether the current host can supply the accepted source form and satisfy
   applicable preparation and resource requirements now.

Derive `supported` only when every required layer is affirmative. Report `unsupported` when an
authoritative layer establishes that the exact operation cannot be used. Preserve `unknown` when
evidence is absent, stale or conflicting; do not infer support from a model name, family name,
provider-wide marketing statement or generated answer in which a model describes its own features.
Before network dispatch, requests requiring `unsupported` fail with a stable unsupported-capability
error, while `unknown` or not-ready requests fail closed with a distinct stable state. Deterministic
tests must prove zero dispatch in both cases.

Capability snapshots are bound to the selected provider/endpoint/model/API tuple and current host
readiness. A model selection, endpoint or API change, adapter replacement, or relevant host-readiness
change invalidates or refreshes the snapshot before it is used again. Front ends may react to the
refreshed `supported`, `unsupported` or `unknown` state, but the connector rechecks the same effective
state at request time to prevent stale UI state from authorizing an invalid dispatch.

## Deferred extensions

The following are not P10 acceptance and require separately activated packages:

- documents, including PDF, DOCX, PPTX, XLSX, and provider file-search or retrieval behavior;
- video and video-derived frames/audio;
- generated image, audio, or video output and playback;
- realtime voice/video sessions, turn detection, or bidirectional media streams;
- local transcription, codec conversion, waveform processing, and other DSP; and
- connector-managed upload storage, retention, or media caching.

## Proposed work packages

Execute one package at a time only after P10 activation.

### P10-A: Multimodal contract and safety decisions

Freeze the canonical content-part model, Kotlin and Swift public API shape, serialized contract,
MIME allowlists, bounded source forms, ownership, error mapping, capability representation, and
compatibility/migration policy. Define the evidence layers, selected-model effective-capability
snapshot, invalidation rules and stable fail-closed states. Add fixture and host-boundary coverage,
including zero-dispatch tests, without a provider media request.

### P10-B: Image input

Deliver one bounded provider path for image input. Add deterministic request translation,
capability, unsupported-media, cancellation, cleanup, and existing-host-consumer coverage, then
targeted live proof for exactly the supported provider/model intersection.

### P10-C: Audio input

Deliver one bounded provider path for audio input. The package must explicitly state whether it
provides transcription, provider-native audio analysis, or both, and expose only the behavior
proved by the selected provider/model. Add the same deterministic and targeted live proof classes
as P10-B.

### P10-D: Cross-media lifecycle and acceptance

Reconcile mixed text/image/audio ordering, concurrent requests, close races, cancellation, source
cleanup, redaction, platform-neutral public API boundaries, and all affected host consumers. This
package does not add documents, video, media output, realtime sessions, or local processing.

## Completion evidence

For each activated package, record the exact candidate head, changed public and provider surfaces,
deterministic fixtures/tests, host-consumer proof, targeted live command and result where a
provider path changed, and all unexercised media/provider behavior. P10 cannot claim broad
multimodal support from a single provider/model proof; its published capabilities and limitations
must describe the delivered intersections exactly.

## Repository baseline and research boundary

At inspected base `11a0756232b08058fc429435760180c9f2675712`, both Kotlin and Swift expose
`UniversalAiTextInput`; requests contain ordered text inputs. No typed image/audio input, media
capability guarantee, transcription operation or media buffer/source lifecycle is implemented.
The remote adapters' checks for unexpected image/audio response fields reject unsupported output;
they are not input support. No existing host media picker, recorder or on-device runtime is a
connector capability. The issue search found no open implementation issue for these tasks;
this plan supplies stable goal IDs without inventing GitHub issue or delivery status.

Repository evidence: [Kotlin request](../../bridge/src/commonMain/kotlin/com/myadidi/universalai/connector/contract/UniversalAiRequest.kt),
[text input](../../bridge/src/commonMain/kotlin/com/myadidi/universalai/connector/contract/UniversalAiTextInput.kt),
[Swift models](../../swift-package/Sources/UniversalAiConnector/UniversalAiConnectorModels.swift),
[V1 schema](../../contracts/schemas/v1/request.schema.json), and
[declared capabilities](../../bridge/src/commonMain/kotlin/com/myadidi/universalai/connector/internal/provider/BuiltInProviderRegistrations.kt).

P10-A is the first planned implementation package. [ADR 0007](../adr/0007-contract-versioning-and-compatibility.md)
requires a compatibility decision before replacing the published text request shape. Unknown
ordinary V1 JSON fields are intentionally ignored: putting media in such a field can lose it,
so an unrecognized ordinary field is not an acceptable media transport. The current strict codec
also bounds the complete encoded document to 1 MiB; encoded media overhead and any side-channel
source contract must be accounted for before selecting media limits.

## Current-source research

The following snapshot separates upstream capability from delivered connector support. A capability
must be bound to **provider, endpoint, model and hosting platform**, with unknown support preserved.
No provider-wide inference from a model name or text discovery result is sufficient.

| Existing adapter / research candidate | Source-backed upstream finding | Connector gap / proof boundary |
|---|---|---|
| OpenAI Responses images | Responses accepts ordered typed text/image content; image references can be URLs, data URLs or file IDs. [Vision guide](https://developers.openai.com/api/docs/guides/images-vision). | Existing adapter is text only. P10 needs explicit source/role/format/limit mapping and one model intersection; Files API support is not implied. |
| OpenAI recorded audio | File transcription uses `/v1/audio/transcriptions`, separate from Responses image requests and audio chat. The current guide lists a 25 MB upload limit and MP3/MP4/MPEG/MPGA/M4A/WAV/WebM input formats. [Transcription guide](https://developers.openai.com/api/docs/guides/speech-to-text), [audio task overview](https://developers.openai.com/api/docs/guides/audio). | No transcription operation or multipart audio path is delivered. Provider upload limits are not canonical request or memory budgets. |
| Anthropic Messages vision | Image blocks support documented sources; available source forms and limits vary by hosting platform. Current model catalogue lists text/image input; no native audio-input guarantee is established here. [Vision](https://platform.claude.com/docs/en/build-with-claude/vision), [model catalogue](https://platform.claude.com/docs/en/models/overview). | Current adapter is text only. Pin direct Claude versus another hosting platform; a transcript from another service is text input, not native Claude audio understanding. |
| OpenRouter | Current image guide describes Responses `input_image` URL/data-URL input. Audio guide describes base64 `input_audio`; direct audio URLs are unsupported, and format support differs between Responses and Chat Completions/model routes. [Images](https://openrouter.ai/docs/guides/overview/multimodal/image-understanding), [audio](https://openrouter.ai/docs/guides/overview/multimodal/audio). | Current connector uses Chat Completions. Pin the selected endpoint/model/upstream route and prove its exact translation rather than copying the Responses contract into that adapter or silently migrating endpoints. |
| Generic OpenAI-compatible / Gateway | Endpoint compatibility and a model identifier do not prove a particular media task/source form. | Only the pinned text intersection is tested. Require explicit server/model contract and capability/translation proof; no Gateway server change is included. |
| Direct Gemini API (exploratory) | Image docs include inline, URL/file methods and additional MIME types such as HEIC/HEIF. Audio docs describe audio input with text answers; file/reference handling is endpoint-specific. [Images](https://ai.google.dev/gemini-api/docs/image-understanding), [audio](https://ai.google.dev/gemini-api/docs/audio). | No direct Gemini adapter exists. This is a comparison candidate requiring separate provider scope and proof, not an added delivery commitment. Cloud Gemini API is separate from on-device Gemini Nano/AICore. |
| Native/downloaded models | Each SDK/runtime/model has its own modality and prepared-input boundary. [On-device research](on-device-model-integration.md). | No local runtime is implemented. Native text generation does not imply transcription or audio/image analysis; local tasks need explicit activation and physical-device proof. |

**Model lifecycle matters.** The current OpenAI guide recommends `gpt-transcribe` for recorded
speech. Its [deprecation notice](https://developers.openai.com/api/docs/deprecations) schedules
`whisper-1` and the listed `gpt-4o` transcription models for shutdown on **2027-02-26**. This is
a dated research finding, not a selected connector default. Recheck model lifecycle, task features,
languages, source formats and account availability at activation; do not copy a historical model
name from an old example or replace the existing text live-test model in this documentation task.

### Research recommendation and delivery order

After the P10 activation decision, start with P10-A contract/safety work, then one still-image
understanding path in P10-B. OpenAI Responses is a useful first image candidate because the
repository already has that API adapter; selection still requires a current model and exact
contract/validation decision. For P10-C, evaluate recorded-audio transcription first, then a
separately accepted native audio-understanding path if required. This is a recommendation; the
transcription-versus-understanding product choice remains open. Realtime audio needs a separate
future scope and session contract; it is excluded from this P10 plan. No SDK/runtime or provider
is selected merely by being listed in this research.

### Privacy, references and uploads

Engineering recommendations: accept only explicitly prepared bounded media or supported references.
If connector-side URL fetching is ever selected, define HTTPS/origin/redirect/private-address and
credential-isolation policies first to prevent unrestricted fetching/SSRF. Provider-side fetching
also sends the URL to that provider; signed URLs can reveal both access and sensitive metadata.
Host applications own capture permissions, file selection, codecs, consent and preprocessing,
including an explicit EXIF/GPS-removal policy. Do not silently change media or acquire SDK-specific
capture/storage responsibilities in the shared contract.

A provider-upload path must define consent, ownership, expiry/deletion, retry and cancellation
behavior; a completed request is not evidence that an uploaded object was deleted. Keep media,
transcripts, local paths, upload IDs and signed URLs out of retained logs/errors/fixtures. Provider
retention and data-use policies differ by API, hosting arrangement, account controls and service
tier. Review the actual endpoint/account policy instead of claiming all cloud media is private,
training-free or immediately deleted.
[OpenAI endpoint data controls](https://developers.openai.com/api/docs/guides/your-data),
[Gemini API terms and service-tier data use](https://ai.google.dev/gemini-api/terms).
No terms are accepted or privacy guarantees newly asserted by this research.

## Goal register

All delivery goals below remain **Not started**. Keep image and audio acceptance independently
visible, even where they share the contract and lifecycle foundation.

| ID | Package / goal | Depends on | Observable acceptance |
|---|---|---|---|
| MEDIA-01 | P10-A content and compatibility contract | P10 activation; ADR 0007 decision | Ordered typed parts/roles; unchanged text-only behavior; explicit Kotlin/Swift/JSON migration; no media disguised as an ignored field |
| MEDIA-02 | P10-A source ownership, validation and capability contract | MEDIA-01 | MIME/size/dimension/duration/count/total-budget policies; separate model/endpoint, connector and host-readiness evidence; selected-model supported/unsupported/unknown state and invalidation; immutable prepared data or explicit bounded source semantics; zero dispatch for unsupported, unknown, not-ready or invalid input |
| IMG-01 | P10-B one provider/model image intersection | MEDIA-01/02; explicit provider/model choice | Deterministic exact request translation, ordering and rejection tests; target live image-understanding proof using synthetic non-sensitive input |
| IMG-02 | Image host and lifecycle acceptance | IMG-01 | Public Kotlin/Swift consumer paths; cancellation/close/concurrency/cleanup and redaction tests; no hidden preprocessing or provider substitution |
| AUD-01 | P10-C select audio operation | MEDIA-01/02; explicit transcription or understanding choice | Document whether output is transcript, audio-grounded answer, or both; define independent capabilities/errors; no synthesized speech or realtime claim |
| AUD-02 | One provider/model audio intersection | AUD-01; supported API/source/format | Deterministic translation/validation plus targeted synthetic audio proof; transcript/answer contract and unsupported cases tested separately |
| AUD-03 | Audio host and lifecycle acceptance | AUD-02 | Prepared audio through public Kotlin/Swift boundary; ownership, cancellation, close and buffer release tests; host-owned capture/transcoding remains explicit |
| MEDIA-03 | P10-D mixed-content and cross-host acceptance | IMG-02 and AUD-03 | Mixed ordering, repeated/concurrent requests, terminal races, total budgets, source release and redaction; documented supported intersections match actual evidence |

## Decisions needed before implementation

- **Activation:** keep the existing post-P8/P9 order, or explicitly reprioritize P10 in a separate
  activation change. Research does not silently pause/complete P8 or create two active milestones.
- **First image path:** select one supported provider/model and source form based on current
  documentation and available targeted proof. Existing text-adapter coverage alone is insufficient.
- **Audio task:** choose transcription, provider-native understanding, or separately bounded
  support for both. An audio-to-text result does not prove broader audio analysis.
- **Source contract:** start with bounded caller-prepared bytes or a precisely validated reference;
  decide copying/immutability, ownership and disposal. A reference must not become unrestricted
  connector-side URL/file fetching. Do not log signed URLs, paths, asset bytes or provider upload/file identifiers.
- **Budget and wire version:** choose conservative per-task limits after accounting for encoding
  overhead, aggregate memory, platform bridging and the existing codec limit. Host transcoding
  cannot silently alter the requested meaning or source.

## Verification and evidence checklist

For each delivered image/audio intersection record exact connector/provider/model/API versions,
input source/MIME/task and tested host. Use synthetic fixtures; retain checksums and bounded test
results rather than raw media, prompt content or provider payloads. Required proof includes:

1. Schemas and embedded contract mirror agree; retained V1 fixtures and text-only Kotlin/Swift
   consumers keep their documented behavior; new wire migration follows the accepted ADR.
2. Allowed/unsupported/unknown media and capability cases; malformed or spoofed MIME, empty,
   oversized, count/total-budget and invalid-reference cases fail before network dispatch. Tests
   assert zero dispatch for unsupported, unknown and not-ready effective capability states.
3. Provider request translation preserves original part order and allowed roles; no silent drop,
   text conversion, model fallback or automatic upload/fetch is introduced.
4. Cancellation before/during preparation/dispatch/streaming, concurrent requests and close races
   release owned resources and produce exactly one terminal outcome. Non-rewindable sources are
   not retried without an explicit replay contract.
5. Redaction covers error descriptions, diagnostic rendering and retained fixtures; metadata
   limits are not treated as a proof that untrusted media decodes safely.
6. Exact targeted live proof and thin public host consumption for the actual delivered path;
   simulator/compilation does not stand in for local model/device inference.
7. Capability snapshots refresh or invalidate after selected-model, endpoint/API, adapter or host-
   readiness changes; absent or conflicting evidence remains `unknown`, and generated model claims
   or name-based heuristics never upgrade support.

## Task quality and resource evaluation

Define the task and acceptance rubric before a provider/model is selected for delivery. For image
understanding, use synthetic or licensed fixtures with expected facts, ordering and task-specific
criteria; OCR, classification and visual question-answering are separate claims. For transcription,
record fixture language, duration and reference transcript, then evaluate transcript accuracy and
partial/final semantics. Audio-grounded question-answering needs its own expected facts; a
transcription score does not establish that capability. Choose task thresholds before live proof;
this research selects no accuracy, cost or latency promise.

Measure encoded request size, peak buffered memory, preparation time, provider latency and usage
where the provider reports it. Base64, Kotlin/Swift bridging copies and decoded host media can
exceed compressed asset size. Bound both individual sources and aggregate in-flight work. Decide
which metadata is verified, host-attested or unknown; a caller-supplied MIME label, dimension or
duration is not trusted decoder validation. Host-side orientation/metadata removal, decoding and
resampling need explicit host policy; the connector must not silently transform media or fetch
arbitrary references. Document any temporary buffer retention and the scope of cleanup guarantees
rather than promising secure erasure of every managed-runtime copy.

## Research sources and refresh policy

Official provider/runtime documentation was reviewed on the date above and the independent media
research findings were checked against those sources. Recheck model support, source forms,
limits, licenses and regional availability at activation; avoid hard-coding this research snapshot
as a permanent capability catalogue.

- [OpenAI image inputs](https://developers.openai.com/api/docs/guides/images-vision)
- [OpenAI audio APIs](https://developers.openai.com/api/docs/guides/audio)
- [OpenAI file transcription](https://developers.openai.com/api/docs/guides/speech-to-text)
- [OpenAI deprecations](https://developers.openai.com/api/docs/deprecations)
- [Anthropic vision](https://platform.claude.com/docs/en/build-with-claude/vision)
- [OpenRouter image inputs](https://openrouter.ai/docs/guides/overview/multimodal/image-understanding)
- [OpenRouter audio inputs](https://openrouter.ai/docs/guides/overview/multimodal/audio)
- [Gemini image understanding](https://ai.google.dev/gemini-api/docs/image-understanding)
- [Gemini audio understanding](https://ai.google.dev/gemini-api/docs/audio)
- [Android Prompt API input setup](https://developers.google.com/ml-kit/genai/prompt/android/get-started)
- Native/model-engine sources and platform constraints are maintained in the [on-device track](on-device-model-integration.md).
