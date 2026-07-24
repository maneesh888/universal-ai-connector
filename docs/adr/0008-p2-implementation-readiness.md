# ADR 0008: P2 implementation readiness

- Status: Accepted
- Date: 2026-07-24
- Milestone: P2-D

## Context

P2-D must reconcile the seven prerequisite decisions before implementation packages can be
accepted. A pre-activation local candidate existed in the worktree; it receives no retroactive
approval and must conform to the accepted decisions like any other implementation.

## Consistency review

| Concern | Reconciled rule |
|---|---|
| Failure and cancellation | Failures are thrown host-native errors carrying canonical data; caller cancellation is never canonical data or an event. |
| Success terminal | Exactly one `response.completed` event carries the final response, then the host stream ends normally. |
| Failed/cancelled stream | Partial events remain visible; failure/cancellation is thrown out of band with no terminal/error event. |
| Unknown values | Valid raw-backed values remain distinct; unsupported semantic terminal/schema behavior fails closed. |
| Null/default | Omission selects only documented defaults; explicit null otherwise fails. |
| Extensions | Bounded reverse-DNS object bags preserve opaque data; canonical fields always win. |
| Capabilities | Absent, unsupported, explicit unknown, and future raw values remain distinguishable; model refinement replaces whole entries. |
| Schema roles | Connector schemas govern wire shape; user schemas use a separate bounded Draft 2020-12 subset. |
| Authority | The versioned `contracts/` bundle is authoritative; Kotlin, Swift, and embedded fixtures are verified implementations/mirrors. |
| Version | Contract major `"1"` is independent of package release version. |
| Host boundary | Kotlin validates constructors/decoding; Swift requests validate at operation start, while standalone capability/descriptor/extension construction provides equivalent throwing validation. |

## Readiness decision

The seven prerequisite decisions are complete and mutually consistent. P2 implementation packages
may proceed in order. P2-E is the first implementation package and has these frozen observable
acceptance criteria:

- approved pinned serialization and schema-validation dependencies only;
- one versioned authoritative schema/fixture bundle;
- deterministic layout, manifest, meta-schema, reference, corpus-mirror, and production
  re-encoding drift checks;
- stable schema-versus-semantic failure layers, codes, and JSON Pointers;
- no public JSON DOM, validator, vendor DTO, networking, credential, or provider API;
- the same representative corpus on JVM, Android host, and iOS Simulator; and
- no migration of the primary client until the request contract package is accepted.

Later P2 packages must also close the mandatory acceptance gaps recorded in their ADRs, including
Swift validation/refinement parity, full extension failure codes/fixtures, schema-path parity,
partial-output lifecycle cases, and authoritative-schema validation of production re-encodings.

## Proof limits

This readiness decision approves implementation work; it does not accept the existing candidate,
complete P2, or prove any live provider, transport, publication, or physical-device behavior.

## Deferred questions

All deferred items have an owning later milestone in ADRs 0001-0007. No unresolved prerequisite
decision is delegated into P2 implementation.
