# Security review context

This is the provider-neutral Adidi Universal AI Connector, with one Kotlin client and a Swift
façade. Hosts supply credentials, endpoints, and model selections. The library does not own
accounts, credential persistence, UI, Gateway administration, or tool execution.

## Assets and trust boundaries

- Credentials, request content, responses, model identifiers, and endpoint configuration are
  sensitive. Host input crosses into the public Kotlin/Swift API; provider responses and stream
  events cross an untrusted network boundary. Provider DTOs remain internal.
- Request configuration and credentials must remain isolated across clients and concurrent calls.
  Inspect endpoint validation, redirects, authorization forwarding, retries, error construction,
  diagnostics, and redaction for leakage or unintended destinations. Do not assume a configured
  Gateway is a trusted public service or that every host endpoint is Internet-accessible.
- Cancellation, streaming terminal delivery, close races, and resource ownership must preserve
  exactly-once completion and cleanup without cross-request data or callback leakage.
- Sample hosts own their secure stores. Review Keychain, Android Keystore, desktop native stores,
  session-only fallback, clearing, and process input without introducing storage into the library.
- Contract schemas, generated mirrors, Kotlin/Swift public boundaries, packaging, pinned
  dependencies, scripts, signing, and CI are supply-chain boundaries. Ordinary CI stays read-only
  and secretless. Never use candidate policy to authorize weaker verification of itself.

## Review criteria and safe validation

Prioritize reachable credential disclosure, request isolation failures, unsafe endpoint handling,
unbounded parsing/resource use, command execution, insecure persistence, dependency compromise,
and CI permission escalation. Trace attacker-controlled input to the affected control or sink;
record validation and uncertainty. A suspicious pattern alone is not a validated vulnerability.
Use deterministic fakes and synthetic inputs first. Use existing repository commands from
`AGENTS.md` and `docs/DEVELOPMENT_WORKFLOW.md`; provider-live proof is a distinct selected gate.
Do not read or source local credential files, probe real external targets, or place private scan
artifacts in the repository. Keep reports, reproducers, host paths, endpoint values, and credentials
out of public PRs. Obtain explicit authorization before publishing vulnerability details.

Gateway server internals, OpenKeyboard, deployment, account settings, and unactivated product
milestones are outside this repository's scope. Library builds, clean scans, and review comments
do not establish runtime acceptance, distribution proof, or absence of vulnerabilities.

See [review workflow](docs/SECURITY_REVIEW.md) for the advisory rollout and baseline procedure.
