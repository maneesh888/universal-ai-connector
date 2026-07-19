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

### Changed

- Defined the initial host-platform strategy: Android, iOS, and Kotlin/JVM on Linux, Windows, and macOS.
- Added host-integration requirements for one primary client entry point, idiomatic Kotlin and Swift APIs, thin external-consumer samples, and consumer smoke checks.
- Expanded the production distribution milestone to cover Maven artifacts as well as remote Swift Package delivery.
