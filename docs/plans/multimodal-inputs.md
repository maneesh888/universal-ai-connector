# P10 Multimodal Inputs

## Status and activation gate

Status: `Planned`; P10 is not active.

P0-P7 are completed, P8-P9 remain planned, and P10 is a post-alpha roadmap item. This plan
records the future priority without activating a milestone or changing the shipped text-only
contract. P10 may become the sole roadmap milestone marked `In progress` only in a separate P10-A
change after the preceding milestones have completed.

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
compatibility/migration policy. Add fixture and host-boundary coverage without a provider media
request.

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
