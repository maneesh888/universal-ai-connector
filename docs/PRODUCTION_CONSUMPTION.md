# Production consumption contract

This document defines the supported source-revision consumption boundary for applications that
need Universal AI Connector before remote P8 distribution exists. It does not activate P8, publish
artifacts, tag a release, or deploy a consumer. The pull-request brief is the authoritative record
of the exact verified commit SHA; downstream repositories must pin that immutable SHA rather than
a branch name.

## Supported host entry points

- Kotlin/JVM and Android: `com.maneesh.universalai.connector.UniversalAiConnector` from the
  `:bridge` Gradle module.
- Apple: the `UniversalAiConnector` Swift Package product in `swift-package/`. Application and
  app-extension code must not import `UniversalAiConnectorBridge`.
- Kotlin model listing: `suspend fun listModels(providerId: ProviderId)`.
- Swift model listing:
  `func listModels(providerId: UniversalAiProviderId) async throws -> UniversalAiModelListResult`.
- Unary generation: Kotlin `respond(request)` and Swift `respond(to:)`.
- Streaming generation: Kotlin `stream(request)` and Swift `stream(request:)`.
- Resource release: synchronous, idempotent `close()` on both host surfaces.

The local Apple bootstrap is:

```bash
git checkout <exact-verified-connector-sha>
./scripts/build-xcframework.sh
```

Add `swift-package/` as a local Swift Package and link only its `UniversalAiConnector` product. The
minimal app-extension target under `samples/ios/UniversalAiConnectorExtensionConsumer` is the
compiling reference. It uses iOS 17, has `APPLICATION_EXTENSION_API_ONLY=YES`, owns its tasks,
cancels them at teardown, and closes its connector. Verify the same boundary with:

```bash
./scripts/build-app-extension-consumer.sh
```

This is compilation and linking proof for an iOS Simulator extension. It is not physical-device
execution, signing, archive, App Store, or deployment proof.

## Provider configuration

Configure only the providers one connector instance will use. Credentials remain host-owned: the
synchronous supplier is retained and invoked once for each outbound HTTP request. Construction,
deterministic operations, and an unused provider do not resolve credentials.

| Provider ID | Supported base URL | Generation endpoint | Model-list behavior |
|---|---|---|---|
| `openai` | `https://api.openai.com/v1` | `responses` | GET `models`; one bounded list |
| `anthropic` | `https://api.anthropic.com/v1` | `messages` | GET `models`; connector-owned cursor pagination |
| `openrouter` | `https://openrouter.ai/api/v1` | `chat/completions` | GET `models`; one bounded list |
| `openai-compatible` | Deployment base URL ending in `/v1` | `chat/completions` | GET `models`; `.unsupported` only for 404, 405, or 501 |

Base URLs are normalized to end in one slash, and the connector appends the relative endpoint.
They must use HTTPS. Plaintext HTTP is accepted only for exact loopback hosts used by local test
servers. Do not include credentials, a query, or a fragment in a base URL.

The provider model-list shapes and authentication follow the official
[OpenAI Models API](https://platform.openai.com/docs/api-reference/models/list),
[Anthropic Models API](https://docs.anthropic.com/en/api/models-list), and
[OpenRouter Models API](https://openrouter.ai/docs/api/api-reference/models/get-models).

### Kotlin construction

```kotlin
val connector =
    UniversalAiConnector(
        UniversalAiConnectorConfiguration(
            providers =
                listOf(
                    UniversalAiProviderConfiguration(
                        providerId = ProviderId.of("openai"),
                        baseUrl = "https://api.openai.com/v1",
                        credentialSupplier = loadCredential,
                    ),
                ),
            connectTimeoutMillis = 10_000,
            requestTimeoutMillis = 60_000,
        ),
    )

try {
    when (val result = connector.listModels(ProviderId.of("openai"))) {
        is UniversalAiModelListResult.Supported -> consume(result.models)
        is UniversalAiModelListResult.Unsupported -> showManualModelEntry()
    }
} finally {
    connector.close()
}
```

### Swift construction

```swift
let providerId = UniversalAiProviderId(rawValue: "openai")
let provider = UniversalAiProviderConfiguration(
    providerId: providerId,
    baseURL: "https://api.openai.com/v1",
    credentialSupplier: loadCredential
)
let connector = try UniversalAiConnector(
    configuration: UniversalAiConnectorConfiguration(
        providers: [provider],
        connectTimeoutMillis: 10_000,
        requestTimeoutMillis: 60_000
    )
)

defer { connector.close() }
switch try await connector.listModels(providerId: providerId) {
case let .supported(_, models):
    consume(models)
case .unsupported(_):
    showManualModelEntry()
}
```

## Model discovery contract

Discovery is an explicit, read-only network operation. A supported result is a defensive snapshot,
sorted by exact model identifier after first-occurrence de-duplication. The connector does not
cache it, choose a model, or rewrite an identifier. Every generation request uses the exact model
identifier supplied by the caller, and its canonical response and terminal stream response retain
that target identity. A provider response or stream that reports a different model identifier is
rejected as malformed instead of being accepted as a silent model substitution.

Direct OpenAI, OpenRouter, and conservative OpenAI-compatible listing accept at most 2,048 models.
Anthropic requests at most 1,000 models per page, accepts at most 2,048 total models over at most
four pages, and rejects cursor loops or inconsistent page metadata. A successful response body is
bounded to 8 MiB and 4,096 chunks. OpenRouter additionally accepts at most 128 advertised
`supported_parameters`, each at most 128 characters. Exceeding a bound, invalid UTF-8, invalid
identifiers or limits, malformed JSON, or an incompatible success shape is a typed protocol error.

`Unsupported` is not a network failure. The deterministic provider has no remote model catalog,
and the generic adapter reports unsupported only when the endpoint explicitly returns 404, 405, or
501. Missing registration is an invalid request. Authentication, authorization, not-found,
rate-limit, unavailable, transport, and protocol failures remain typed `UniversalAiException` or
`UniversalAiConnectorError` values; task/coroutine cancellation remains native cancellation.

Capability metadata is conservative:

- OpenAI and Anthropic report their provider streaming default and unknown per-model structured
  output support because their list shapes do not advertise it.
- OpenRouter derives structured-output support only from advertised `structured_outputs` or
  `response_format` parameters and maps advertised context/output limits.
- Generic OpenAI-compatible discovery leaves model-specific capability support unknown.

An empty supported list is distinct from unsupported discovery.

## Timeouts, concurrency, and lifecycle

Connect and request timeouts are immutable per connector. Defaults are 10,000 ms and 60,000 ms;
each value must be from 1 through 86,400,000 ms. The connect timeout applies to connection
establishment. The request timeout applies to the whole individual HTTP exchange, including body
or event-stream consumption; each Anthropic discovery page is a separate exchange. These are not
retry budgets, and the connector performs no implicit retry or model fallback.

One connector can serve concurrent unary, streaming, and discovery operations. Each Kotlin stream
is cold and runs in its collector context. Each Swift stream supports one consuming task; create a
separate stream per concurrent operation. Cancelling one owner cancels only its operation. Calling
`close()` cancels all active work, rejects new work with the stable closed error, releases owned
transport resources, and is safe to repeat. An injected Kotlin `HttpClientEngine` remains owned by
its caller.

Normal response and stream translators enforce bounded provider bodies, ordered stream sequences,
one terminal outcome, governed structured JSON, and typed authentication, authorization,
rate-limit, unavailable, transport, protocol, and truncation/incomplete-stream failures. Provider
bodies, credentials, authorization headers, and configured model identifiers are not retained in
verification output. Direct OpenRouter and generic OpenAI-compatible responses may include provider
reasoning metadata alongside an independently valid final assistant text result. The connector
discards that metadata and exposes only the final text; reasoning-only, blank, refused, tool-call,
or otherwise unsupported results still fail closed.

## Verification and proof limits

`./scripts/check.sh --full` builds and tests the JVM and Android consumers, iOS Simulator Kotlin
tests, two-slice XCFramework, Swift façade, app-extension consumer, simulator application, and
generic device-slice application link. Provider-impacting revisions additionally require all
selected exact-head `./scripts/check-live.sh <provider>` gates before push. Each live gate verifies
that its configured model is discoverable (or that a generic Gateway explicitly does not support
listing), then verifies exact canonical request/response model identity alongside the existing
unary, structured-output, streaming, error, and cancellation cases.

Live proof is fail-closed. Missing configuration, an unavailable provider/model, a quota or rate
limit, a discovery mismatch, or another provider failure is `LIVE_UNVERIFIED` and blocks release
readiness; it is never converted to a skip. Ordinary CI remains credential-free. No command in
this workflow publishes artifacts, tags a release, deploys a consumer, or claims physical-device
execution.
