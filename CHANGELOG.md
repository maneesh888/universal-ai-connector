# Changelog

All notable changes to Universal AI Connector will be documented in this file.

The project follows Semantic Versioning once published artifacts begin.

## Unreleased

### Added

- Kotlin/Native iOS Simulator bridge proof of concept.
- XCFramework generation and local Swift Package distribution.
- Swift async response, streaming, error-mapping, and cancellation façade.
- Kotlin and Swift interoperability tests.
- Standalone SwiftUI demonstration application.
- Deterministic verification and secret-scanning scripts.
- Package roadmap and cross-platform sample plan.
- Pull-request jobs for JVM tests on Linux, Windows, and macOS plus Android host tests and AAR packaging on Linux.
- Product-facing `UniversalAiConnector` Kotlin API with a version property, suspending response, ordered `Flow` streaming, stable typed errors, and caller-owned cancellation.
- Non-interactive JVM console consumer with exact-output smoke coverage through the public Gradle project boundary.
- Jetpack Compose Android consumer demonstrating the public client response, ordered stream, stable error, request cancellation, and stream stopping paths.
- Repeatable Android install-and-launch script for a booted emulator or connected device.

### Changed

- Defined the initial host-platform strategy: Android, iOS, and Kotlin/JVM on Linux, Windows, and macOS.
- Added host-integration requirements for one primary client entry point, idiomatic Kotlin and Swift APIs, thin external-consumer samples, and consumer smoke checks.
- Expanded the production distribution milestone to cover Maven artifacts as well as remote Swift Package delivery.
- Made `kotlinx-coroutines-core` part of the bridge module's public Gradle API because the supported Kotlin surface exposes `Flow`.
- Extended quick/full local checks and the existing Linux, Windows, and macOS JVM jobs with the console consumer verification task.
- Hid the Kotlin product API from Objective-C export and added an XCFramework header check so Apple consumers remain on the callback bridge and Swift façade.
- Extended local checks and Linux/macOS CI definitions with Android application controller tests and debug APK assembly.
- Recorded the P8 requirement for installable macOS, Windows, and Linux desktop demonstrations with deterministic and opt-in live Gateway modes.
