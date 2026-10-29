# Universal AI Connector

**One AI client for Kotlin and Swift, across OpenAI, Anthropic, OpenRouter, and OpenAI-compatible gateways.**

[![CI](https://github.com/maneesh888/universal-ai-connector/actions/workflows/ci.yml/badge.svg)](https://github.com/maneesh888/universal-ai-connector/actions/workflows/ci.yml)
![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Stage](https://img.shields.io/badge/stage-source%20preview-F59E0B)
[![License: MIT](https://img.shields.io/badge/license-MIT-22C55E)](LICENSE)

Universal AI Connector gives Android, iOS, JVM, and desktop applications one provider-neutral API for model discovery, text generation, structured output, and streaming—without leaking provider DTOs into application code.

[Why it exists](#why-universal-ai-connector) · [Try it](#try-it-in-60-seconds) · [Providers](#providers) · [Host APIs](#native-on-every-host) · [Status](#project-status) · [Documentation](#documentation)

> [!IMPORTANT]
> The library is a source preview. It is tested from this repository, but Maven publication, remote Swift Package installation, packaged desktop applications, and the first immutable alpha release are still in progress.

## Why Universal AI Connector?

Most multi-provider integrations spread provider-specific requests, responses, errors, and streaming rules throughout the app. Universal AI Connector keeps that variation behind one canonical contract:

- **One client, multiple providers.** Change the target provider and model without rewriting application-level AI types.
- **Native concurrency on both sides.** Kotlin uses `suspend` and `Flow`; Swift uses `async` and `AsyncThrowingStream`.
- **Predictable behavior.** Typed errors, exact-model identity, active cancellation, concurrent operations, and idempotent cleanup are part of the contract.
- **No-key development mode.** Every sample starts with a deterministic local provider, so contributors can explore responses, streams, failures, and cancellation without credentials or network access.
- **Host-owned secrets.** Applications supply credentials at request time; the connector does not read app storage or environment files on its own.

```text
Android · iOS · JVM · Desktop
              │
              ▼
     Universal AI Connector
     request · stream · models
              │
      ┌───────┼──────────┬───────────────┐
      ▼       ▼          ▼               ▼
   OpenAI  Anthropic  OpenRouter  OpenAI-compatible
              │
              ▼
 canonical responses · errors · stream events
```

## Try it in 60 seconds

The fastest path needs only JDK 21. It runs a deterministic request, ordered stream, typed failure, response cancellation, and stream cancellation:

```bash
git clone https://github.com/maneesh888/universal-ai-connector.git
cd universal-ai-connector
./gradlew :samples:jvm-console:run
```

Prefer a UI? Launch the Compose desktop sample on macOS, Windows, or Linux:

```bash
./gradlew :samples:desktop:run
```

Both commands start without API keys or network access. The Android, iOS, JVM, and desktop samples also include an explicit live workflow for delivered providers.

## The API at a glance

The provider is configuration. The request and response model stay the same:

```kotlin
val connector = UniversalAiConnector(
    UniversalAiConnectorConfiguration(
        providers = listOf(
            UniversalAiProviderConfiguration(
                providerId = ProviderId.of("openai"),
                baseUrl = "https://api.openai.com/v1",
                credentialSupplier = loadCredential,
            ),
        ),
    ),
)

val request = UniversalAiRequest(
    target = UniversalAiTarget(
        providerId = ProviderId.of("openai"),
        modelId = ModelId.of(selectedModel),
    ),
    input = listOf(
        UniversalAiTextInput(
            role = UniversalAiInputRole.User,
            content = "Explain Kotlin Multiplatform in three sentences.",
        ),
    ),
)

try {
    val response = connector.respond(request)
    println(response.outputs.single().text)

    connector.stream(request).collect { event ->
        print(event.delta.orEmpty())
    }
} finally {
    connector.close()
}
```

The same boundary is Swift-native:

```swift
let connector = UniversalAiConnector()
defer { connector.close() }

let response = try await connector.respond(to: request)

for try await event in connector.stream(request: request) {
    print(event.delta ?? "", terminator: "")
}
```

The snippets focus on the public API shape. Until remote artifacts are published, use the tested source-revision setup in the [production consumption contract](docs/PRODUCTION_CONSUMPTION.md).

## Providers

| Provider ID | Protocol | Model discovery | Generation |
|---|---|---:|---:|
| `openai` | Responses API | Yes | Response, structured output, stream |
| `anthropic` | Messages API | Yes, including bounded pagination | Response, structured output, stream |
| `openrouter` | Chat Completions | Yes | Response, structured output, stream |
| `openai-compatible` | Conservative Chat Completions intersection | When the endpoint supports it | Response, structured output, stream |
| `deterministic` | Local test implementation | Not required | Response, typed error, stream, cancellation |

Provider adapters stay internal. Public callers work with canonical requests, responses, output parts, usage, metadata, errors, capabilities, and stream events.

## Native on every host

| Host | Public experience | Reference app |
|---|---|---|
| Android 24+ | Kotlin client with coroutines and `Flow` | [Jetpack Compose sample](samples/android/) |
| iOS 17+ | Swift façade with `async`, `AsyncThrowingStream`, Swift errors, and Swift cancellation | [SwiftUI sample](samples/ios/) |
| JVM on Linux, Windows, and macOS | Kotlin client with coroutines and `Flow` | [Console sample](samples/jvm-console/) |
| Desktop on Linux, Windows, and macOS | Kotlin/JVM client in Compose Multiplatform | [Desktop sample](samples/desktop/) |

All four sample paths offer:

- a zero-configuration deterministic mode;
- explicit live-provider configuration;
- model discovery and exact-model selection;
- a connection test that re-discovers before generation;
- cancellation and lifecycle cleanup; and
- credential handling owned by the host application.

## Built for real lifecycle behavior

Universal AI Connector is designed around the details that become expensive when each provider is integrated separately:

- immutable provider registration and configurable timeouts;
- concurrent discovery, response, and streaming operations;
- caller-owned coroutine and task lifetimes;
- cancellation propagation into active transport work;
- exactly one terminal stream outcome;
- synchronous, idempotent `close()`;
- typed authentication, authorization, rate-limit, transport, protocol, and truncation failures;
- strict structured-output validation; and
- bounded bodies, model lists, metadata, and event streams.

There is no implicit retry, model substitution, or silent fallback. A request either uses the exact provider and model selected by the host or fails explicitly.

## Security model

- Credentials are supplied by a synchronous host-owned loader and resolved only for an outbound request.
- Provider payloads, credentials, and authorization headers are excluded from retained verification output.
- Provider endpoints require HTTPS; plaintext HTTP is limited to exact loopback development hosts.
- Android and iOS samples use platform credential storage for retained live configuration.
- The desktop sample uses macOS Keychain, Windows Credential Manager, or Linux Secret Service when available, with session-only fallback.
- Ordinary CI is deterministic and credential-free. Live verification runs locally and fails closed when required inputs or provider behavior are unavailable.

See [Security Policy](SECURITY.md) and [Live Provider Testing](docs/LIVE_PROVIDER_TESTING.md) for the full boundaries.

## Project status

Universal AI Connector has completed its cross-platform foundation, canonical contracts, HTTP transport, delivered provider adapters, gateway compatibility, and source-based live samples for iOS, Android, JVM, and desktop.

### Available from source today

- Provider-neutral Kotlin and Swift public APIs
- OpenAI, Anthropic, OpenRouter, and OpenAI-compatible adapters
- Model discovery with exact-model/no-substitution behavior
- Unary responses, structured output, incremental streaming, and cancellation
- Deterministic JVM, Android, iOS Simulator, Swift Package, and host-consumer verification
- Credential-free demos plus explicit live workflows on every current sample host

### Still in progress

- Published Maven coordinates for Android and JVM
- Remote Swift Package distribution
- Packaged and signed desktop applications
- Released-artifact consumer tests, checksums, and compatibility policy
- Physical iOS-device execution
- The immutable `0.1.0-alpha.1` release

The [V2 roadmap](docs/plans/universal-ai-connector-v2.md) is the source of truth for milestone status, scope, and proof limits.

## Run the samples

### JVM console

```bash
./gradlew :samples:jvm-console:run
```

### Compose desktop

```bash
./gradlew :samples:desktop:run
```

### Android

```bash
./gradlew :samples:android:consumerCheck
./scripts/run-android-sample.sh
```

### iOS

```bash
./scripts/build-xcframework.sh
open samples/ios/UniversalAiConnectorSample.xcodeproj
```

Live-provider setup is intentionally separate from the zero-key first run. Follow [Live Provider Testing](docs/LIVE_PROVIDER_TESTING.md) when you are ready to connect a real account or gateway.

## Build and verify

Run the documentation and repository hygiene gate:

```bash
./scripts/check.sh --hygiene
```

Run the normal deterministic development suite:

```bash
./scripts/check.sh --quick
```

Run the complete local matrix on a fully configured macOS development host:

```bash
./scripts/check.sh --full
```

The full matrix adds the combined iOS device/simulator XCFramework, Swift Package tests, app-extension consumer, iOS Simulator sample, and generic iOS-device link verification. It does not claim physical-device execution or published-artifact resolution.

See [Development Workflow](docs/DEVELOPMENT_WORKFLOW.md) for prerequisites, targeted checks, hooks, CI behavior, and proof levels.

## Repository map

```text
bridge/                 Kotlin Multiplatform client, adapters, and tests
contracts/              Versioned JSON Schemas and compatibility fixtures
swift-package/          Supported Swift façade and Swift tests
samples/android/        Jetpack Compose Android consumer
samples/ios/            SwiftUI and app-extension consumers
samples/jvm-console/    Headless Kotlin/JVM consumer
samples/desktop/        Compose Multiplatform desktop consumer
scripts/                Deterministic and live verification commands
docs/                   Consumption, security, verification, and roadmap docs
```

## Documentation

- [Production Consumption](docs/PRODUCTION_CONSUMPTION.md) — supported source-revision integration boundary
- [Live Provider Testing](docs/LIVE_PROVIDER_TESTING.md) — local credentials, provider gates, and proof limits
- [Development Workflow](docs/DEVELOPMENT_WORKFLOW.md) — toolchains, checks, CI, and contribution workflow
- [V2 Roadmap](docs/plans/universal-ai-connector-v2.md) — milestone order, current status, and acceptance criteria
- [Security Policy](SECURITY.md) — reporting and security expectations

## Contributing

Issues and focused pull requests are welcome. Start with the [development workflow](docs/DEVELOPMENT_WORKFLOW.md), keep provider-specific behavior behind internal adapters, and include the smallest verification that proves your change.

For security-sensitive reports, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.

## License

Universal AI Connector is available under the [MIT License](LICENSE).
