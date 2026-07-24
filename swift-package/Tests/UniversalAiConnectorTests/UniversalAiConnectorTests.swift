import Dispatch
import Foundation
import XCTest
@testable import UniversalAiConnector

final class UniversalAiConnectorTests: XCTestCase {
    func testProductFrameworkImportsAndReportsVersion() {
        let connector = UniversalAiConnector()

        XCTAssertEqual(connector.version, "0.1.0-alpha.1")
    }

    func testAsyncResponseReturnsCanonicalValue() async throws {
        let connector = UniversalAiConnector()
        let request = request(" hello ")

        let response = try await connector.respond(to: request)

        XCTAssertEqual(response.contractVersion, "1")
        XCTAssertEqual(response.id.rawValue, "deterministic-response")
        XCTAssertNil(response.requestId)
        XCTAssertEqual(response.target, request.target)
        XCTAssertEqual(response.completionReason, .stop)
        XCTAssertNil(response.usage)
        XCTAssertEqual(response.extensions, .empty)
        XCTAssertEqual(response.outputs.count, 1)
        XCTAssertEqual(response.outputs[0].id.rawValue, "deterministic-output-0")
        XCTAssertEqual(response.outputs[0].index, 0)
        XCTAssertEqual(response.outputs[0].kind, .text)
        XCTAssertEqual(response.outputs[0].text, "Kotlin echo:  hello ")
        XCTAssertNil(response.outputs[0].structuredJson)
        XCTAssertEqual(response.outputs[0].extensions, .empty)
    }

    func testInvalidResponseMapsToRawCanonicalSwiftError() async {
        let connector = UniversalAiConnector()

        do {
            _ = try await connector.respond(to: request("   "))
            XCTFail("Expected invalid input error.")
        } catch let error as UniversalAiConnectorError {
            XCTAssertEqual(error.category, .validation)
            XCTAssertEqual(error.code, .invalidRequest)
            XCTAssertEqual(error.message, "Request validation failed.")
            XCTAssertEqual(
                error.metadata?["validationCode"],
                UniversalAiJsonValue.string("blank_input_content")
            )
            XCTAssertEqual(
                error.metadata?["path"],
                UniversalAiJsonValue.string("/input/0/content")
            )
            XCTAssertEqual(error.extensions, .empty)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testForcedResponseFailurePreservesStableCodeAndMessage()
        async throws
    {
        let connector = UniversalAiConnector()

        do {
            _ = try await connector.respond(to: request("__force_error__"))
            XCTFail("Expected simulated failure.")
        } catch let error as UniversalAiConnectorError {
            XCTAssertEqual(
                error,
                try UniversalAiConnectorError(
                    category: .provider,
                    code: .simulatedFailure,
                    message:
                        "The Universal AI Connector produced the requested simulated failure."
                )
            )
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testStreamEmitsCompleteCanonicalSequenceThenExhaustsNormally() async throws {
        let connector = UniversalAiConnector()
        var events: [UniversalAiStreamEvent] = []
        var iterator = connector.stream(request: request("chunk")).makeAsyncIterator()

        while let event = try await iterator.next() {
            events.append(event)
        }

        let eventAfterCompletion = try await iterator.next()
        XCTAssertNil(eventAfterCompletion)
        XCTAssertEqual(events.map(\.sequence), [1, 2, 3, 4, 5, 6])
        XCTAssertEqual(
            events.map(\.type),
            [
                .responseStarted,
                .outputStarted,
                .outputDelta,
                .outputDelta,
                .outputCompleted,
                .responseCompleted,
            ]
        )
        XCTAssertEqual(
            events.map(\.terminal),
            [false, false, false, false, false, true]
        )
        XCTAssertEqual(
            events.map(\.responseId.rawValue),
            Array(repeating: "deterministic-response", count: 6)
        )
        XCTAssertEqual(events.compactMap(\.delta), ["Kotlin echo: ", "chunk"])
        XCTAssertEqual(events[1].outputId?.rawValue, "deterministic-output-0")
        XCTAssertEqual(events[1].outputIndex, 0)
        XCTAssertEqual(events[4].output?.text, "Kotlin echo: chunk")
        XCTAssertEqual(events[5].response?.outputs.first?.text, "Kotlin echo: chunk")
        XCTAssertEqual(events[5].response?.completionReason, .stop)
        XCTAssertTrue(events[5].terminal)
    }

    func testStreamFailureMapsToStableSwiftError() async throws {
        let connector = UniversalAiConnector()

        do {
            for try await _ in connector.stream(
                request: request("__force_error__")
            ) {
                XCTFail("A failing stream must not emit events.")
            }
            XCTFail("Expected simulated stream failure.")
        } catch let error as UniversalAiConnectorError {
            XCTAssertEqual(
                error,
                try UniversalAiConnectorError(
                    category: .provider,
                    code: .simulatedFailure,
                    message:
                        "The Universal AI Connector produced the requested simulated failure."
                )
            )
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testFutureRawValuesRemainDistinctWithoutWireDecoding() throws {
        let futureRole = UniversalAiInputRole(rawValue: "future_role")
        let futureOutputKind = UniversalAiOutputKind(rawValue: "future_output")
        let futureCompletion =
            UniversalAiCompletionReason(rawValue: "future_completion")
        let futureEventType =
            UniversalAiStreamEventType(rawValue: "future.event")
        let futureCategory =
            try UniversalAiErrorCategory(rawValue: "future_category")
        let futureCode =
            try UniversalAiErrorCode(rawValue: "future_error")

        XCTAssertEqual(futureRole.rawValue, "future_role")
        XCTAssertNotEqual(futureRole, .user)
        XCTAssertEqual(futureOutputKind.rawValue, "future_output")
        XCTAssertNotEqual(futureOutputKind, .text)
        XCTAssertEqual(futureCompletion.rawValue, "future_completion")
        XCTAssertNotEqual(futureCompletion, .stop)

        let futureEvent = UniversalAiStreamEvent(
            contractVersion: "1",
            type: futureEventType,
            terminal: false,
            sequence: 1,
            responseId: UniversalAiResponseId(rawValue: "future-response")
        )
        XCTAssertEqual(futureEvent.type.rawValue, "future.event")
        XCTAssertFalse(futureEvent.terminal)
        XCTAssertNotEqual(futureEvent.type, .responseCompleted)

        let futureError = try UniversalAiConnectorError(
            category: futureCategory,
            code: futureCode,
            message: "Future failure."
        )
        XCTAssertEqual(futureError.category.rawValue, "future_category")
        XCTAssertEqual(futureError.code.rawValue, "future_error")
        XCTAssertNotEqual(futureError.category, .internal)
        XCTAssertNotEqual(futureError.code, .connectorFailure)
    }

    func testCanonicalSwiftErrorsRejectUnsafeMessages() throws {
        let valid = try UniversalAiConnectorError(
            category: .provider,
            code: .connectorFailure,
            message: "Provider failure — retry later."
        )
        XCTAssertEqual(valid.message, "Provider failure — retry later.")

        assertContractValidation(
            code: "invalid_error_message",
            path: "/message"
        ) {
            _ = try UniversalAiConnectorError(
                category: .internal,
                code: .connectorFailure,
                message: " \u{00A0} "
            )
        }
        assertContractValidation(
            code: "invalid_error_message",
            path: "/message"
        ) {
            _ = try UniversalAiConnectorError(
                category: .internal,
                code: .connectorFailure,
                message: String(repeating: "x", count: 4_097)
            )
        }

        let unsafeScalars =
            Array(0x00...0x1F) +
            Array(0x7F...0x9F) +
            [0x061C] +
            Array(0x200E...0x200F) +
            Array(0x2028...0x202E) +
            Array(0x2066...0x2069)
        for rawScalar in unsafeScalars {
            let scalar = UnicodeScalar(rawScalar)!
            assertContractValidation(
                code: "invalid_error_message",
                path: "/message"
            ) {
                _ = try UniversalAiConnectorError(
                    category: .internal,
                    code: .connectorFailure,
                    message: "Unsafe\(String(scalar))message"
                )
            }
        }
    }

    func testCanonicalSwiftErrorsValidateIdentifiersAndMetadata() throws {
        assertContractValidation(
            code: "invalid_error_category",
            path: "/category"
        ) {
            _ = try UniversalAiErrorCategory(rawValue: "Invalid")
        }
        assertContractValidation(
            code: "invalid_error_code",
            path: "/code"
        ) {
            _ = try UniversalAiErrorCode(rawValue: "")
        }

        let oversizedNumber =
            try UniversalAiJsonNumber(
                rawValue: String(repeating: "1", count: 129)
            )
        assertContractValidation(
            code: "extension_number_token_too_long",
            path: "/metadata/value"
        ) {
            _ = try UniversalAiConnectorError(
                category: .provider,
                code: .connectorFailure,
                message: "Failure.",
                metadata: UniversalAiJsonObject([
                    "value": .number(oversizedNumber),
                ])
            )
        }
        assertContractValidation(
            code: "extension_string_too_long",
            path: "/metadata/value"
        ) {
            _ = try UniversalAiConnectorError(
                category: .provider,
                code: .connectorFailure,
                message: "Failure.",
                metadata: UniversalAiJsonObject([
                    "value": .string(
                        String(repeating: "x", count: 16_385)
                    ),
                ])
            )
        }
        assertContractValidation(
            code: "invalid_extension_member_name",
            path: "/metadata/line\nbreak"
        ) {
            _ = try UniversalAiConnectorError(
                category: .provider,
                code: .connectorFailure,
                message: "Failure.",
                metadata: UniversalAiJsonObject([
                    "line\nbreak": .null,
                ])
            )
        }
        assertContractValidation(
            code: "extension_member_name_too_long"
        ) {
            _ = try UniversalAiConnectorError(
                category: .provider,
                code: .connectorFailure,
                message: "Failure.",
                metadata: UniversalAiJsonObject([
                    String(repeating: "n", count: 257): .null,
                ])
            )
        }

        var tooManyMembers: [String: UniversalAiJsonValue] = [:]
        for index in 0...256 {
            tooManyMembers["member_\(index)"] = .null
        }
        assertContractValidation(
            code: "extension_object_member_limit_exceeded",
            path: "/metadata"
        ) {
            _ = try UniversalAiConnectorError(
                category: .provider,
                code: .connectorFailure,
                message: "Failure.",
                metadata: UniversalAiJsonObject(tooManyMembers)
            )
        }
        assertContractValidation(
            code: "extension_array_element_limit_exceeded",
            path: "/metadata/items"
        ) {
            _ = try UniversalAiConnectorError(
                category: .provider,
                code: .connectorFailure,
                message: "Failure.",
                metadata: UniversalAiJsonObject([
                    "items": .array(
                        Array(repeating: .null, count: 257)
                    ),
                ])
            )
        }

        var tooDeep: UniversalAiJsonValue = .array([])
        for _ in 0..<16 {
            tooDeep = .array([tooDeep])
        }
        assertContractValidation(
            code: "extension_depth_limit_exceeded"
        ) {
            _ = try UniversalAiConnectorError(
                category: .provider,
                code: .connectorFailure,
                message: "Failure.",
                metadata: UniversalAiJsonObject(["nested": tooDeep])
            )
        }

        let fullArray = UniversalAiJsonValue.array(
            Array(repeating: .null, count: 256)
        )
        assertContractValidation(
            code: "extension_node_limit_exceeded"
        ) {
            _ = try UniversalAiConnectorError(
                category: .provider,
                code: .connectorFailure,
                message: "Failure.",
                metadata: UniversalAiJsonObject([
                    "a": fullArray,
                    "b": fullArray,
                    "c": fullArray,
                    "d": fullArray,
                    "e": fullArray,
                ])
            )
        }
    }

    func testCapabilityModelsPreserveKnownUnknownAbsentAndFutureValues() throws {
        let explicitUnknownName =
            try UniversalAiCapabilityName(rawValue: "embeddings")
        let futureName =
            try UniversalAiCapabilityName(rawValue: "future_audio")
        let futureLimitName =
            try UniversalAiCapabilityLimitName(rawValue: "max_frame_bytes")
        let futureSupport =
            try UniversalAiCapabilitySupport(
                rawValue: "conditionally_supported"
            )
        let namespace =
            try UniversalAiExtensionNamespace(
                rawValue: "com.example.future"
            )
        let extensions = try UniversalAiExtensions([
            namespace: UniversalAiJsonObject([
                "nested": .object(
                    UniversalAiJsonObject([
                        "exact": .number(
                            try UniversalAiJsonNumber(
                                rawValue: "1.2300e+40"
                            )
                        ),
                    ])
                ),
            ]),
        ])
        let capabilities = try UniversalAiCapabilitySet([
            .streaming: try UniversalAiCapabilityDeclaration(
                support: .supported
            ),
            .structuredOutput: try UniversalAiCapabilityDeclaration(
                support: .unsupported
            ),
            explicitUnknownName: try UniversalAiCapabilityDeclaration(
                support: .unknown
            ),
            futureName: try UniversalAiCapabilityDeclaration(
                support: futureSupport,
                limits: [futureLimitName: 4_096],
                extensions: extensions
            ),
        ])

        requireSendable(capabilities)
        XCTAssertTrue(UniversalAiCapabilityName.streaming.isKnown)
        XCTAssertTrue(
            UniversalAiCapabilityName.structuredOutput.isKnown
        )
        XCTAssertEqual(futureName.rawValue, "future_audio")
        XCTAssertFalse(futureName.isKnown)
        XCTAssertTrue(
            UniversalAiCapabilityLimitName.maxSchemaBytes.isKnown
        )
        XCTAssertTrue(
            UniversalAiCapabilityLimitName.maxSchemaDepth.isKnown
        )
        XCTAssertEqual(futureLimitName.rawValue, "max_frame_bytes")
        XCTAssertFalse(futureLimitName.isKnown)
        XCTAssertEqual(
            capabilities.supportState(for: .streaming),
            .supported
        )
        XCTAssertEqual(
            capabilities.supportState(for: .structuredOutput),
            .unsupported
        )
        XCTAssertEqual(
            capabilities[explicitUnknownName]?.support,
            .unknown
        )
        XCTAssertEqual(
            capabilities.supportState(for: explicitUnknownName),
            .unknown
        )
        let absentName =
            try UniversalAiCapabilityName(rawValue: "absent_capability")
        XCTAssertNil(capabilities[absentName])
        XCTAssertEqual(
            capabilities.supportState(for: absentName),
            .unknown
        )
        XCTAssertEqual(futureSupport.rawValue, "conditionally_supported")
        XCTAssertFalse(futureSupport.isKnown)
        XCTAssertEqual(futureSupport.semanticState, .unknown)
        XCTAssertNotEqual(futureSupport, .unknown)
        XCTAssertEqual(
            capabilities[futureName]?.limits[futureLimitName],
            4_096
        )
        XCTAssertEqual(
            capabilities[futureName]?.extensions,
            extensions
        )

        guard
            let payload =
                capabilities[futureName]?.extensions.entries[namespace],
            case let .object(nested)? = payload["nested"],
            case let .number(exact)? = nested["exact"]
        else {
            return XCTFail("Expected the exact nested extension number.")
        }
        XCTAssertEqual(exact.rawValue, "1.2300e+40")
    }

    func testProviderProfilePreservesFullNativeCapabilityFields() throws {
        let namespace =
            try UniversalAiExtensionNamespace(
                rawValue: "com.example.profile"
            )
        let extensions = try UniversalAiExtensions([
            namespace: UniversalAiJsonObject([
                "tier": .string("deterministic"),
            ]),
        ])
        let futureName =
            try UniversalAiCapabilityName(rawValue: "future_reasoning")
        let capabilities = try UniversalAiCapabilitySet([
            .streaming: try UniversalAiCapabilityDeclaration(
                support: .supported,
                limits: [
                    try UniversalAiCapabilityLimitName(
                        rawValue: "delivery_window"
                    ): 8,
                ]
            ),
            futureName: try UniversalAiCapabilityDeclaration(
                support: try UniversalAiCapabilitySupport(
                    rawValue: "preview"
                )
            ),
        ])
        let profile = try UniversalAiProviderCapabilityProfile(
            providerId: UniversalAiProviderId(rawValue: "example"),
            capabilities: capabilities,
            extensions: extensions
        )

        requireSendable(profile)
        XCTAssertEqual(profile.contractVersion, "1")
        XCTAssertEqual(profile.providerId.rawValue, "example")
        XCTAssertEqual(profile.capabilities, capabilities)
        XCTAssertEqual(profile.extensions, extensions)
        XCTAssertEqual(
            profile.capabilities[futureName]?.support.rawValue,
            "preview"
        )
        XCTAssertEqual(
            profile,
            try UniversalAiProviderCapabilityProfile(
                contractVersion: "1",
                providerId: UniversalAiProviderId(rawValue: "example"),
                capabilities: capabilities,
                extensions: extensions
            )
        )

        let minimalProfile = try UniversalAiProviderCapabilityProfile(
            providerId: UniversalAiProviderId(rawValue: "minimal")
        )
        XCTAssertEqual(minimalProfile.contractVersion, "1")
        XCTAssertTrue(minimalProfile.capabilities.isEmpty)
        XCTAssertEqual(minimalProfile.extensions, .empty)
    }

    func testModelDescriptorPreservesLimitsCapabilitiesAndExtensions() throws {
        let namespace =
            try UniversalAiExtensionNamespace(
                rawValue: "com.example.descriptor"
            )
        let extensions = try UniversalAiExtensions([
            namespace: UniversalAiJsonObject([
                "flags": .array([.boolean(true), .null]),
            ]),
        ])
        let limits = try UniversalAiModelTokenLimits(
            contextWindowTokens: 131_072,
            maxInputTokens: 114_688,
            maxOutputTokens: 16_384
        )
        let capabilities = try UniversalAiCapabilitySet([
            .streaming: try UniversalAiCapabilityDeclaration(
                support: .supported
            ),
            .structuredOutput: try UniversalAiCapabilityDeclaration(
                support: .supported,
                limits: [
                    .maxSchemaBytes: 65_536,
                    .maxSchemaDepth: 16,
                ]
            ),
        ])
        let descriptor = try UniversalAiModelDescriptor(
            target: UniversalAiTarget(
                providerId: UniversalAiProviderId(rawValue: "example"),
                modelId: UniversalAiModelId(rawValue: "model-v1")
            ),
            displayName: "Example Model V1",
            limits: limits,
            capabilities: capabilities,
            extensions: extensions
        )

        requireSendable(limits)
        requireSendable(descriptor)
        XCTAssertEqual(descriptor.contractVersion, "1")
        XCTAssertEqual(descriptor.target.providerId.rawValue, "example")
        XCTAssertEqual(descriptor.target.modelId.rawValue, "model-v1")
        XCTAssertEqual(descriptor.displayName, "Example Model V1")
        XCTAssertEqual(descriptor.limits, limits)
        XCTAssertEqual(descriptor.capabilities, capabilities)
        XCTAssertEqual(descriptor.extensions, extensions)
        XCTAssertEqual(descriptor.limits?.contextWindowTokens, 131_072)
        XCTAssertEqual(descriptor.limits?.maxInputTokens, 114_688)
        XCTAssertEqual(descriptor.limits?.maxOutputTokens, 16_384)
        XCTAssertNil(
            try UniversalAiModelDescriptor(
                target: descriptor.target,
                limits: try UniversalAiModelTokenLimits()
            ).limits
        )
    }

    func testNestedExtensionsPreserveExactJsonNumberModelParity() async throws {
        let namespace =
            try UniversalAiExtensionNamespace(rawValue: "example.parity")
        let exactNumber =
            try UniversalAiJsonNumber(rawValue: "1.2300e+40")
        let nestedObject = UniversalAiJsonObject([
            "exact": .number(exactNumber),
            "enabled": .boolean(true),
        ])
        let extensions = try UniversalAiExtensions([
            namespace: UniversalAiJsonObject([
                "nested": .array([
                    .object(nestedObject),
                    .null,
                    .string("preserved"),
                ]),
            ]),
        ])
        let canonicalRequest = request(
            "extension parity",
            extensions: extensions
        )

        XCTAssertEqual(canonicalRequest.extensions, extensions)
        guard
            let payload = canonicalRequest.extensions.entries[namespace],
            case let .array(values)? = payload["nested"],
            case let .object(inner)? = values.first,
            case let .number(mappedNumber)? = inner["exact"]
        else {
            return XCTFail("Expected the nested exact JSON number.")
        }
        XCTAssertEqual(mappedNumber.rawValue, "1.2300e+40")

        let response = try await UniversalAiConnector().respond(
            to: canonicalRequest
        )
        XCTAssertEqual(response.outputs.first?.text, "Kotlin echo: extension parity")
    }

    func testStructuredJsonEqualityIsSemanticWhileExtensionsRemainRaw() throws {
        let one = try UniversalAiJsonNumber(rawValue: "1")
        let onePointZero = try UniversalAiJsonNumber(rawValue: "1.0")
        let oneExponentZero = try UniversalAiJsonNumber(rawValue: "1e0")
        let negativeZero = try UniversalAiJsonNumber(rawValue: "-0")
        let zero = try UniversalAiJsonNumber(rawValue: "0.000e+9000")

        XCTAssertEqual(
            UniversalAiStructuredOutputSchema(value: .number(one)),
            UniversalAiStructuredOutputSchema(value: .number(onePointZero))
        )
        XCTAssertEqual(
            UniversalAiStructuredOutputValue(value: .number(onePointZero)),
            UniversalAiStructuredOutputValue(value: .number(oneExponentZero))
        )
        XCTAssertEqual(
            UniversalAiStructuredOutputValue(value: .number(negativeZero)),
            UniversalAiStructuredOutputValue(value: .number(zero))
        )

        let namespace =
            try UniversalAiExtensionNamespace(rawValue: "com.example.raw")
        let rawOne = try UniversalAiExtensions([
            namespace: UniversalAiJsonObject(["value": .number(one)]),
        ])
        let rawOnePointZero = try UniversalAiExtensions([
            namespace: UniversalAiJsonObject([
                "value": .number(onePointZero),
            ]),
        ])
        XCTAssertNotEqual(rawOne, rawOnePointZero)
    }

    func testStandaloneCapabilityValidationAndWholeEntryResolution() throws {
        assertContractValidation(
            code: "invalid_capability_name",
            path: ""
        ) {
            _ = try UniversalAiCapabilityName(rawValue: "Bad Capability")
        }
        assertContractValidation(
            code: "invalid_capability_support",
            path: "/support"
        ) {
            _ = try UniversalAiCapabilitySupport(rawValue: "")
        }
        assertContractValidation(
            code: "unsupported_capability_limits",
            path: "/limits"
        ) {
            _ = try UniversalAiCapabilityDeclaration(
                support: .unsupported,
                limits: [
                    try UniversalAiCapabilityLimitName(
                        rawValue: "future_limit"
                    ): 1,
                ]
            )
        }
        assertContractValidation(
            code: "capability_limit_not_applicable",
            path: "/streaming/limits/max_schema_depth"
        ) {
            _ = try UniversalAiCapabilitySet([
                .streaming: try UniversalAiCapabilityDeclaration(
                    support: .supported,
                    limits: [.maxSchemaDepth: 16]
                ),
            ])
        }

        let providerExtensions = try UniversalAiExtensions([
            try UniversalAiExtensionNamespace(
                rawValue: "com.example.capability"
            ): UniversalAiJsonObject(["source": .string("provider")]),
        ])
        let providerCapabilities = try UniversalAiCapabilitySet([
            .streaming: try UniversalAiCapabilityDeclaration(
                support: .supported
            ),
            .structuredOutput: try UniversalAiCapabilityDeclaration(
                support: .supported,
                limits: [.maxSchemaDepth: 16],
                extensions: providerExtensions
            ),
        ])
        let profile = try UniversalAiProviderCapabilityProfile(
            providerId: UniversalAiProviderId(rawValue: "example"),
            capabilities: providerCapabilities
        )
        let modelOverrides = try UniversalAiCapabilitySet([
            .structuredOutput: try UniversalAiCapabilityDeclaration(
                support: .unknown
            ),
        ])
        let target = UniversalAiTarget(
            providerId: UniversalAiProviderId(rawValue: "example"),
            modelId: UniversalAiModelId(rawValue: "model/日本語")
        )
        let resolved = try UniversalAiCapabilitySet.resolve(
            providerProfile: profile,
            modelTarget: target,
            modelOverrides: modelOverrides
        )

        XCTAssertEqual(resolved[.streaming]?.support, .supported)
        XCTAssertEqual(resolved[.structuredOutput]?.support, .unknown)
        XCTAssertTrue(resolved[.structuredOutput]?.limits.isEmpty == true)
        XCTAssertEqual(resolved[.structuredOutput]?.extensions, .empty)
        assertContractValidation(
            code: "capability_provider_mismatch",
            path: "/target/providerId"
        ) {
            _ = try UniversalAiCapabilitySet.resolve(
                providerProfile: profile,
                modelTarget: UniversalAiTarget(
                    providerId: UniversalAiProviderId(rawValue: "other"),
                    modelId: target.modelId
                )
            )
        }

        let declaration = try UniversalAiCapabilityDeclaration(
            support: .supported
        )
        var tooMany:
            [UniversalAiCapabilityName: UniversalAiCapabilityDeclaration] = [:]
        for index in 0...64 {
            tooMany[
                try UniversalAiCapabilityName(rawValue: "capability_\(index)")
            ] = declaration
        }
        assertContractValidation(
            code: "capability_count_exceeded",
            path: ""
        ) {
            _ = try UniversalAiCapabilitySet(tooMany)
        }
    }

    func testStandaloneExtensionsValidateAndReplaceWholeNamespaces() throws {
        assertContractValidation(
            code: "invalid_extension_namespace",
            path: ""
        ) {
            _ = try UniversalAiExtensionNamespace(rawValue: "Example")
        }
        assertContractValidation(
            code: "invalid_json_number",
            path: ""
        ) {
            _ = try UniversalAiJsonNumber(rawValue: "01")
        }
        let oversizedExtensionNumber =
            try UniversalAiJsonNumber(
                rawValue: String(repeating: "1", count: 129)
            )
        let first =
            try UniversalAiExtensionNamespace(rawValue: "com.example.first")
        assertContractValidation(
            code: "extension_number_token_too_long",
            path: "/com.example.first/value"
        ) {
            _ = try UniversalAiExtensions([
                first: UniversalAiJsonObject([
                    "value": .number(oversizedExtensionNumber),
                ]),
            ])
        }

        let second =
            try UniversalAiExtensionNamespace(rawValue: "com.example.second")
        let original = try UniversalAiExtensions([
            first: UniversalAiJsonObject([
                "known": .string("old"),
                "sibling": .boolean(true),
            ]),
            second: UniversalAiJsonObject(["keep": .boolean(true)]),
        ])
        let replacement = UniversalAiJsonObject([
            "known": .string("new"),
        ])
        let replaced = try original.replacing(
            namespace: first,
            payload: replacement
        )

        XCTAssertEqual(replaced.entries[first], replacement)
        XCTAssertEqual(replaced.entries[second], original.entries[second])
        XCTAssertNil(replaced.entries[first]?["sibling"])
        let removed = replaced.removing(namespace: first)
        XCTAssertNil(removed.entries[first])
        XCTAssertEqual(removed.entries[second], original.entries[second])

        assertContractValidation(
            code: "extension_string_too_long",
            path: "/com.example.first/value"
        ) {
            _ = try UniversalAiExtensions([
                first: UniversalAiJsonObject([
                    "value": .string(
                        String(repeating: "x", count: 16_385)
                    ),
                ]),
            ])
        }

        var nested: UniversalAiJsonValue = .array([])
        for _ in 0..<16 {
            nested = .array([nested])
        }
        assertContractValidation(
            code: "extension_depth_limit_exceeded"
        ) {
            _ = try UniversalAiExtensions([
                first: UniversalAiJsonObject(["nested": nested]),
            ])
        }
    }

    func testStandaloneExtensionsUseCanonicalCodesForEveryAggregateLimit() throws {
        let namespace =
            try UniversalAiExtensionNamespace(rawValue: "com.example.first")

        var tooManyNamespaces:
            [UniversalAiExtensionNamespace: UniversalAiJsonObject] = [:]
        for index in 0...16 {
            tooManyNamespaces[
                try UniversalAiExtensionNamespace(
                    rawValue: "com.example.namespace-\(index)"
                )
            ] = UniversalAiJsonObject()
        }
        assertContractValidation(
            code: "extension_namespace_limit_exceeded",
            path: ""
        ) {
            _ = try UniversalAiExtensions(tooManyNamespaces)
        }

        assertContractValidation(
            code: "extension_array_element_limit_exceeded",
            path: "/com.example.first/items"
        ) {
            _ = try UniversalAiExtensions([
                namespace: UniversalAiJsonObject([
                    "items": .array(
                        Array(repeating: .null, count: 257)
                    ),
                ]),
            ])
        }

        var tooManyMembers: [String: UniversalAiJsonValue] = [:]
        for index in 0...256 {
            tooManyMembers["member_\(index)"] = .null
        }
        assertContractValidation(
            code: "extension_object_member_limit_exceeded",
            path: "/com.example.first"
        ) {
            _ = try UniversalAiExtensions([
                namespace: UniversalAiJsonObject(tooManyMembers),
            ])
        }

        assertContractValidation(
            code: "invalid_extension_member_name",
            path: "/com.example.first/line\nbreak"
        ) {
            _ = try UniversalAiExtensions([
                namespace: UniversalAiJsonObject(["line\nbreak": .null]),
            ])
        }
        assertContractValidation(
            code: "extension_member_name_too_long"
        ) {
            _ = try UniversalAiExtensions([
                namespace: UniversalAiJsonObject([
                    String(repeating: "n", count: 257): .null,
                ]),
            ])
        }

        let fullArray = UniversalAiJsonValue.array(
            Array(repeating: .null, count: 256)
        )
        assertContractValidation(
            code: "extension_node_limit_exceeded"
        ) {
            _ = try UniversalAiExtensions([
                namespace: UniversalAiJsonObject([
                    "a": fullArray,
                    "b": fullArray,
                    "c": fullArray,
                    "d": fullArray,
                    "e": fullArray,
                ]),
            ])
        }

        let maximumString = UniversalAiJsonValue.string(
            String(repeating: "x", count: 16_384)
        )
        assertContractValidation(
            code: "extension_size_limit_exceeded",
            path: ""
        ) {
            _ = try UniversalAiExtensions([
                namespace: UniversalAiJsonObject([
                    "a": maximumString,
                    "b": maximumString,
                    "c": maximumString,
                    "d": maximumString,
                    "e": maximumString,
                ]),
            ])
        }
    }

    func testProfilesAndDescriptorsRejectEveryInvalidNativeState() throws {
        assertContractValidation(
            code: "unsupported_contract_version",
            path: "/contractVersion"
        ) {
            _ = try UniversalAiProviderCapabilityProfile(
                contractVersion: "2",
                providerId: UniversalAiProviderId(rawValue: "example")
            )
        }
        assertContractValidation(
            code: "invalid_provider_id",
            path: "/providerId"
        ) {
            _ = try UniversalAiProviderCapabilityProfile(
                providerId: UniversalAiProviderId(rawValue: "INVALID")
            )
        }
        assertContractValidation(
            code: "unsupported_contract_version",
            path: "/contractVersion"
        ) {
            _ = try UniversalAiModelDescriptor(
                contractVersion: "2",
                target: UniversalAiTarget(
                    providerId: UniversalAiProviderId(rawValue: "example"),
                    modelId: UniversalAiModelId(rawValue: "model")
                )
            )
        }
        assertContractValidation(
            code: "model_output_limit_exceeds_context",
            path: "/maxOutputTokens"
        ) {
            _ = try UniversalAiModelTokenLimits(
                contextWindowTokens: 1_000,
                maxOutputTokens: 1_001
            )
        }
        assertContractValidation(
            code: "invalid_model_display_name",
            path: "/displayName"
        ) {
            _ = try UniversalAiModelDescriptor(
                target: UniversalAiTarget(
                    providerId: UniversalAiProviderId(rawValue: "example"),
                    modelId: UniversalAiModelId(rawValue: "model")
                ),
                displayName: " \t "
            )
        }
        assertContractValidation(
            code: "invalid_model_id",
            path: "/target/modelId"
        ) {
            _ = try UniversalAiModelDescriptor(
                target: UniversalAiTarget(
                    providerId: UniversalAiProviderId(rawValue: "example"),
                    modelId: UniversalAiModelId(rawValue: "bad model")
                )
            )
        }

        let unicodeDescriptor = try UniversalAiModelDescriptor(
            target: UniversalAiTarget(
                providerId: UniversalAiProviderId(rawValue: "example"),
                modelId: UniversalAiModelId(rawValue: "モデル/α")
            ),
            displayName: "モデル α"
        )
        XCTAssertEqual(unicodeDescriptor.target.modelId.rawValue, "モデル/α")
    }

    func testRequestOperationPreflightUsesStableVersionAndSchemaPaths() async {
        let stringSchema = UniversalAiStructuredOutputSchema(
            value: .object(
                UniversalAiJsonObject(["type": .string("string")])
            )
        )
        await assertRequestValidation(
            UniversalAiRequest(
                contractVersion: "2",
                target: request("x").target,
                input: request("x").input
            ),
            code: "unsupported_contract_version",
            path: "/contractVersion"
        )
        await assertRequestValidation(
            UniversalAiRequest(
                target: request("x").target,
                input: request("x").input,
                responseFormat: UniversalAiResponseFormat(
                    kind: .jsonSchema
                )
            ),
            code: "missing_response_schema",
            path: "/responseFormat/schema"
        )
        await assertRequestValidation(
            UniversalAiRequest(
                target: request("x").target,
                input: request("x").input,
                responseFormat: UniversalAiResponseFormat(
                    kind: .plainText,
                    schema: stringSchema
                )
            ),
            code: "unexpected_response_schema",
            path: "/responseFormat/schema"
        )
        await assertRequestValidation(
            UniversalAiRequest(
                target: request("x").target,
                input: request("x").input,
                responseFormat: UniversalAiResponseFormat(
                    kind: UniversalAiResponseFormatKind(
                        rawValue: "future_format"
                    ),
                    schema: stringSchema
                )
            ),
            code: "unexpected_response_schema",
            path: "/responseFormat/schema"
        )

        let unsupportedKeyword = UniversalAiStructuredOutputSchema(
            value: .object(
                UniversalAiJsonObject(["pattern": .string("x")])
            )
        )
        await assertRequestValidation(
            UniversalAiRequest(
                target: request("x").target,
                input: request("x").input,
                responseFormat: .jsonSchema(unsupportedKeyword)
            ),
            code: "unsupported_schema_keyword",
            path: "/responseFormat/schema/pattern"
        )

        var tooDeep: UniversalAiJsonValue = .boolean(true)
        for _ in 0..<32 {
            tooDeep = .object(UniversalAiJsonObject(["not": tooDeep]))
        }
        await assertRequestValidation(
            UniversalAiRequest(
                target: request("x").target,
                input: request("x").input,
                responseFormat: .jsonSchema(
                    UniversalAiStructuredOutputSchema(value: tooDeep)
                )
            ),
            code: "schema_depth_limit_exceeded"
        )

        var tooManyNodes: UniversalAiJsonValue = .boolean(true)
        for _ in 0..<9 {
            tooManyNodes = .object(
                UniversalAiJsonObject([
                    "allOf": .array([tooManyNodes, tooManyNodes]),
                ])
            )
        }
        await assertRequestValidation(
            UniversalAiRequest(
                target: request("x").target,
                input: request("x").input,
                responseFormat: .jsonSchema(
                    UniversalAiStructuredOutputSchema(value: tooManyNodes)
                )
            ),
            code: "schema_node_limit_exceeded"
        )

        let tooLarge = UniversalAiStructuredOutputSchema(
            value: .object(
                UniversalAiJsonObject([
                    "description": .string(
                        String(repeating: "x", count: 65_536)
                    ),
                ])
            )
        )
        await assertRequestValidation(
            UniversalAiRequest(
                target: request("x").target,
                input: request("x").input,
                responseFormat: .jsonSchema(tooLarge)
            ),
            code: "schema_size_limit_exceeded",
            path: "/responseFormat/schema"
        )
    }

    func testValidJsonSchemaCrossesAppleConversionBeforeSupportCheck()
        async throws
    {
        let oversizedExtensionNumber =
            try UniversalAiJsonNumber(
                rawValue: String(repeating: "1", count: 129)
            )
        let schemas = [
            UniversalAiStructuredOutputSchema(
                value: .object(
                    UniversalAiJsonObject([
                        "type": .string("string"),
                    ])
                )
            ),
            UniversalAiStructuredOutputSchema(
                value: .object(
                    UniversalAiJsonObject([
                        "type": .string("number"),
                        "minimum": .number(oversizedExtensionNumber),
                    ])
                )
            ),
        ]

        for schema in schemas {
            do {
                _ = try await UniversalAiConnector().respond(
                    to: UniversalAiRequest(
                        target: request("schema conversion").target,
                        input: request("schema conversion").input,
                        responseFormat: .jsonSchema(schema)
                    )
                )
                XCTFail("The deterministic connector must reject JSON output.")
            } catch let error as UniversalAiConnectorError {
                XCTAssertEqual(error.category, .validation)
                XCTAssertEqual(error.code, .invalidRequest)
                XCTAssertEqual(
                    error.message,
                    "The deterministic connector supports only plain-text responses."
                )
                XCTAssertNil(error.metadata)
            } catch {
                XCTFail("Unexpected error: \(error)")
            }
        }
    }

    func testCancellingConsumingTaskCancelsRetainedStreamExactlyOnce() async throws {
        let connector = UniversalAiConnector()
        connector.resetDiagnosticsForTesting()
        let retainedStream = connector.stream(request: request("retained"))
        let eventCount = LockedCounter()
        let firstEventReceived = AsyncSignal()
        let consumingTask = Task {
            for try await event in retainedStream {
                XCTAssertEqual(event.sequence, 1)
                XCTAssertEqual(event.type, .responseStarted)
                eventCount.increment()
                firstEventReceived.signal()
            }
        }

        await firstEventReceived.wait()
        consumingTask.cancel()

        do {
            try await consumingTask.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        try await waitForCancellationCount(connector, streams: 1)
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(
            connector.diagnosticsForTesting(),
            UniversalAiConnectorDiagnostics(
                responseCancellations: 0,
                streamCancellations: 1
            )
        )
        XCTAssertEqual(eventCount.value, 1)

        // Prove task cancellation is sufficient even while the sequence stays
        // alive; releasing it is not the trigger exercised by this test.
        withExtendedLifetime(retainedStream) {}
    }

    func testParentTaskCancellationCancelsStreamAndThrowsCancellationError() async throws {
        let connector = UniversalAiConnector()
        connector.resetDiagnosticsForTesting()
        let task = Task {
            for try await _ in connector.stream(request: request("parent")) {
                // Consume until the parent task is cancelled.
            }
        }

        try await Task.sleep(for: .milliseconds(150))
        task.cancel()

        do {
            try await task.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        try await waitForCancellationCount(connector, streams: 1)
        XCTAssertEqual(connector.diagnosticsForTesting().streamCancellations, 1)
    }

    func testUnaryTaskCancellationCancelsResponseAndResumesOnce() async throws {
        let connector = UniversalAiConnector()
        connector.resetDiagnosticsForTesting()
        let task = Task {
            try await connector.respond(to: request("cancel"))
        }

        try await Task.sleep(for: .milliseconds(30))
        task.cancel()

        do {
            _ = try await task.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        try await waitForCancellationCount(connector, responses: 1)
        XCTAssertEqual(connector.diagnosticsForTesting().responseCancellations, 1)
    }

    func testCancellationBeforeHandleInstallationPropagatesExactlyOnce() async throws {
        let enteredHook = AsyncSignal()
        let releaseHook = DispatchSemaphore(value: 0)
        let connector = UniversalAiConnector(
            testingHooks: UniversalAiConnectorTestingHooks(
                beforeResponseCancellationInstallation: {
                    enteredHook.signal()
                    releaseHook.wait()
                }
            )
        )
        connector.resetDiagnosticsForTesting()

        let task = Task {
            try await connector.respond(
                to: request("installation-race")
            )
        }

        await enteredHook.wait()
        task.cancel()
        releaseHook.signal()

        do {
            _ = try await task.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        try await waitForCancellationCount(connector, responses: 1)
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(connector.diagnosticsForTesting().responseCancellations, 1)
    }

    func testStreamCancellationBeforeHandleInstallationPropagatesExactlyOnce() async throws {
        let enteredHook = AsyncSignal()
        let releaseHook = DispatchSemaphore(value: 0)
        let connector = UniversalAiConnector(
            testingHooks: UniversalAiConnectorTestingHooks(
                beforeStreamCancellationInstallation: {
                    enteredHook.signal()
                    releaseHook.wait()
                }
            )
        )
        connector.resetDiagnosticsForTesting()

        let task = Task {
            for try await _ in connector.stream(
                request: request("stream-installation-race")
            ) {
                XCTFail("Cancellation before handle installation must emit no events.")
            }
        }

        await enteredHook.wait()
        task.cancel()
        releaseHook.signal()

        do {
            try await task.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        try await waitForCancellationCount(connector, streams: 1)
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(connector.diagnosticsForTesting().streamCancellations, 1)
    }

    func testLockedStateIgnoresLateTerminalsAfterPreinstallationCancellation() async {
        let state = LockedOperationState<String>()
        let continuationReady = AsyncSignal()
        let cancellationCount = LockedCounter()
        let task = Task {
            try await withCheckedThrowingContinuation { continuation in
                state.installContinuation(continuation)
                continuationReady.signal()
            }
        }

        await continuationReady.wait()
        state.cancel()
        state.installCancellation {
            cancellationCount.increment()
        }
        state.installCancellation {
            cancellationCount.increment()
        }
        state.succeed("late success")
        state.fail(lateFailure())
        state.cancel()

        do {
            _ = try await task.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        XCTAssertEqual(cancellationCount.value, 1)
    }

    func testLockedStreamStateIgnoresLateTerminalsAfterPreinstallationCancellation() async {
        let state = LockedStreamState<String>()
        let cancellationCount = LockedCounter()
        let stream = AsyncThrowingStream<String, Error> { continuation in
            state.installContinuation(continuation)
        }
        let task = Task {
            var iterator = stream.makeAsyncIterator()
            return try await iterator.next()
        }

        state.cancel()
        state.installCancellation {
            cancellationCount.increment()
        }
        state.installCancellation {
            cancellationCount.increment()
        }
        state.yield("late event")
        state.finish()
        state.fail(lateFailure())
        state.cancel()

        do {
            _ = try await task.value
            XCTFail("Expected cancellation.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        XCTAssertEqual(cancellationCount.value, 1)
    }

    func testLockedResponseSuccessAndFailureWinLaterCancellation() async {
        let successState = LockedOperationState<String>()
        let successReady = AsyncSignal()
        let successCancellations = LockedCounter()
        let successTask = Task {
            try await withCheckedThrowingContinuation { continuation in
                successState.installContinuation(continuation)
                successReady.signal()
            }
        }
        await successReady.wait()
        successState.installCancellation {
            successCancellations.increment()
        }
        successState.succeed("success")
        successState.cancel()
        let successValue = try? await successTask.value
        XCTAssertEqual(successValue, "success")
        XCTAssertEqual(successCancellations.value, 0)

        let failureState = LockedOperationState<String>()
        let failureReady = AsyncSignal()
        let failureCancellations = LockedCounter()
        let expectedFailure = lateFailure()
        let failureTask = Task {
            try await withCheckedThrowingContinuation { continuation in
                failureState.installContinuation(continuation)
                failureReady.signal()
            }
        }
        await failureReady.wait()
        failureState.installCancellation {
            failureCancellations.increment()
        }
        failureState.fail(expectedFailure)
        failureState.cancel()
        do {
            _ = try await failureTask.value
            XCTFail("Expected canonical failure.")
        } catch let error as UniversalAiConnectorError {
            XCTAssertEqual(error, expectedFailure)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
        XCTAssertEqual(failureCancellations.value, 0)
    }

    func testLockedStreamCompletionWinsLaterCancellation() async throws {
        let state = LockedStreamState<String>()
        let cancellationCount = LockedCounter()
        let stream = AsyncThrowingStream<String, Error> { continuation in
            state.installContinuation(continuation)
        }
        state.installCancellation {
            cancellationCount.increment()
        }
        var iterator = stream.makeAsyncIterator()

        state.finish()
        state.cancel()

        let value = try await iterator.next()
        XCTAssertNil(value)
        XCTAssertEqual(cancellationCount.value, 0)
    }

    func testLockedStreamStateDeliversPartialDeltaThenCanonicalFailureOutOfBand() async {
        let state = LockedStreamState<String>()
        let expectedFailure = lateFailure()
        let stream = AsyncThrowingStream<String, Error> { continuation in
            state.installContinuation(continuation)
        }

        state.yield("output.delta")
        state.fail(expectedFailure)
        state.yieldTerminal("response.completed")
        state.finish()

        var iterator = stream.makeAsyncIterator()
        do {
            let partialDelta = try await iterator.next()
            XCTAssertEqual(partialDelta, "output.delta")
            _ = try await iterator.next()
            XCTFail("Expected the canonical failure after the partial delta.")
        } catch let error as UniversalAiConnectorError {
            XCTAssertEqual(error, expectedFailure)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testLockedStreamStateDeliversPartialDeltaThenCancellationOutOfBand() async {
        let state = LockedStreamState<String>()
        let cancellationCount = LockedCounter()
        let stream = AsyncThrowingStream<String, Error> { continuation in
            state.installContinuation(continuation)
        }
        state.installCancellation {
            cancellationCount.increment()
        }

        state.yield("output.delta")
        state.cancel()
        state.yieldTerminal("response.completed")
        state.fail(lateFailure())
        state.finish()

        var iterator = stream.makeAsyncIterator()
        do {
            let partialDelta = try await iterator.next()
            XCTAssertEqual(partialDelta, "output.delta")
            _ = try await iterator.next()
            XCTFail("Expected cancellation after the partial delta.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }

        XCTAssertEqual(cancellationCount.value, 1)
    }

    func testLockedStreamStateDeliversTerminalThenIgnoresEveryLateSignal() async throws {
        let state = LockedStreamState<String>()
        let cancellationCount = LockedCounter()
        let stream = AsyncThrowingStream<String, Error> { continuation in
            state.installContinuation(continuation)
        }
        state.installCancellation {
            cancellationCount.increment()
        }

        state.yield("event")
        state.yieldTerminal("terminal")
        state.yield("late event")
        state.cancel()
        state.fail(lateFailure())
        state.finish()
        state.installCancellation {
            cancellationCount.increment()
        }

        var iterator = stream.makeAsyncIterator()
        let first = try await iterator.next()
        let terminal = try await iterator.next()
        let afterTerminal = try await iterator.next()
        XCTAssertEqual(first, "event")
        XCTAssertEqual(terminal, "terminal")
        XCTAssertNil(afterTerminal)
        XCTAssertEqual(cancellationCount.value, 0)
    }

    func testConcurrentResponsesRemainIndependent() async throws {
        let connector = UniversalAiConnector()
        let responses = try await withThrowingTaskGroup(
            of: (Int, String).self,
            returning: [String].self
        ) { group in
            for index in 0..<8 {
                group.addTask {
                    let response = try await connector.respond(
                        to: request("request-\(index)")
                    )
                    return (index, response.outputs.first?.text ?? "")
                }
            }

            var ordered = Array(repeating: "", count: 8)
            for try await (index, response) in group {
                ordered[index] = response
            }
            return ordered
        }

        XCTAssertEqual(
            responses,
            (0..<8).map { "Kotlin echo: request-\($0)" }
        )
    }

    func testConcurrentStreamsRemainIndependent() async throws {
        let connector = UniversalAiConnector()
        let streams = try await withThrowingTaskGroup(
            of: (Int, [UniversalAiStreamEvent]).self,
            returning: [[UniversalAiStreamEvent]].self
        ) { group in
            for index in 0..<4 {
                group.addTask {
                    var events: [UniversalAiStreamEvent] = []
                    for try await event in connector.stream(
                        request: request("stream-\(index)")
                    ) {
                        events.append(event)
                    }
                    return (index, events)
                }
            }

            var ordered = Array(repeating: [UniversalAiStreamEvent](), count: 4)
            for try await (index, events) in group {
                ordered[index] = events
            }
            return ordered
        }

        for index in 0..<4 {
            XCTAssertEqual(streams[index].map(\.sequence), [1, 2, 3, 4, 5, 6])
            XCTAssertEqual(
                streams[index].compactMap(\.delta),
                ["Kotlin echo: ", "stream-\(index)"]
            )
            XCTAssertEqual(streams[index].last?.type, .responseCompleted)
            XCTAssertEqual(streams[index].last?.terminal, true)
            XCTAssertEqual(
                streams[index].last?.response?.outputs.first?.text,
                "Kotlin echo: stream-\(index)"
            )
        }
    }

    func testCancellingOneConcurrentStreamDoesNotAffectSiblingOperations()
        async throws
    {
        let connector = UniversalAiConnector()
        connector.resetDiagnosticsForTesting()
        let firstCancelledEvent = AsyncSignal()
        let cancelledEventCount = LockedCounter()
        let cancelledStream = Task {
            for try await _ in connector.stream(
                request: request("cancel only this stream")
            ) {
                cancelledEventCount.increment()
                firstCancelledEvent.signal()
            }
        }
        let siblingResponse = Task {
            try await connector.respond(
                to: request("independent response")
            )
        }
        let siblingStream = Task {
            var events: [UniversalAiStreamEvent] = []
            for try await event in connector.stream(
                request: request("independent stream")
            ) {
                events.append(event)
            }
            return events
        }

        await firstCancelledEvent.wait()
        cancelledStream.cancel()
        do {
            try await cancelledStream.value
            XCTFail("Expected only the selected stream to be cancelled.")
        } catch is CancellationError {
            // Expected.
        } catch {
            XCTFail("Unexpected cancellation error: \(error)")
        }

        let response = try await siblingResponse.value
        let events = try await siblingStream.value
        try await waitForCancellationCount(connector, streams: 1)

        XCTAssertEqual(cancelledEventCount.value, 1)
        XCTAssertEqual(
            response.outputs.first?.text,
            "Kotlin echo: independent response"
        )
        XCTAssertEqual(events.map(\.sequence), [1, 2, 3, 4, 5, 6])
        XCTAssertEqual(events.filter(\.terminal).count, 1)
        XCTAssertEqual(events.last?.type, .responseCompleted)
        XCTAssertEqual(
            events.last?.response?.outputs.first?.text,
            "Kotlin echo: independent stream"
        )
        XCTAssertEqual(
            connector.diagnosticsForTesting(),
            UniversalAiConnectorDiagnostics(
                responseCancellations: 0,
                streamCancellations: 1
            )
        )
    }

    func testRepeatedCreationAndReleaseCompletesWithoutLateCallbacks() async throws {
        for index in 0..<12 {
            weak var releasedConnector: UniversalAiConnector?

            do {
                let connector = UniversalAiConnector()
                releasedConnector = connector
                let response = try await connector.respond(
                    to: request("release-\(index)")
                )
                XCTAssertEqual(
                    response.outputs.first?.text,
                    "Kotlin echo: release-\(index)"
                )
            }

            XCTAssertNil(releasedConnector)
        }

        // Keep the test process alive beyond both deterministic callback delays.
        // A late second terminal would violate the checked-continuation contract.
        try await Task.sleep(for: .milliseconds(250))
    }

    private func assertContractValidation(
        code: String,
        path: String? = nil,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ operation: () throws -> Void
    ) {
        do {
            try operation()
            XCTFail(
                "Expected UniversalAiContractValidationError.",
                file: file,
                line: line
            )
        } catch let error as UniversalAiContractValidationError {
            XCTAssertEqual(error.code, code, file: file, line: line)
            if let path {
                XCTAssertEqual(error.path, path, file: file, line: line)
            }
        } catch {
            XCTFail("Unexpected error: \(error)", file: file, line: line)
        }
    }

    private func assertRequestValidation(
        _ request: UniversalAiRequest,
        code: String,
        path: String? = nil,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        do {
            _ = try await UniversalAiConnector().respond(to: request)
            XCTFail(
                "Expected UniversalAiConnectorError.",
                file: file,
                line: line
            )
        } catch let error as UniversalAiConnectorError {
            XCTAssertEqual(error.category, .validation, file: file, line: line)
            XCTAssertEqual(error.code, .invalidRequest, file: file, line: line)
            XCTAssertEqual(
                error.metadata?["validationCode"],
                .string(code),
                file: file,
                line: line
            )
            if let path {
                XCTAssertEqual(
                    error.metadata?["path"],
                    .string(path),
                    file: file,
                    line: line
                )
            }
        } catch {
            XCTFail("Unexpected error: \(error)", file: file, line: line)
        }
    }

    private func waitForCancellationCount(
        _ connector: UniversalAiConnector,
        responses: Int? = nil,
        streams: Int? = nil
    ) async throws {
        for _ in 0..<40 {
            let diagnostics = connector.diagnosticsForTesting()
            let responseMatches = responses.map {
                diagnostics.responseCancellations == $0
            } ?? true
            let streamMatches = streams.map {
                diagnostics.streamCancellations == $0
            } ?? true
            if responseMatches && streamMatches {
                return
            }
            try await Task.sleep(for: .milliseconds(25))
        }

        XCTFail("Timed out waiting for cancellation diagnostics.")
    }
}

private func request(
    _ content: String,
    role: UniversalAiInputRole = .user,
    extensions: UniversalAiExtensions = .empty
) -> UniversalAiRequest {
    UniversalAiRequest(
        target: UniversalAiTarget(
            providerId: UniversalAiProviderId(rawValue: "deterministic"),
            modelId: UniversalAiModelId(rawValue: "echo-v1")
        ),
        input: [
            UniversalAiTextInput(role: role, content: content),
        ],
        extensions: extensions
    )
}

private func lateFailure() -> UniversalAiConnectorError {
    UniversalAiConnectorError(
        trustedCategory: .internal,
        code: .connectorFailure,
        message: "late failure"
    )
}

private func requireSendable<Value: Sendable>(_: Value) {}

private final class LockedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int {
        lock.withLock { count }
    }

    func increment() {
        lock.withLock {
            count += 1
        }
    }
}

private final class AsyncSignal: @unchecked Sendable {
    private let lock = NSLock()
    private var signalled = false
    private var continuation: CheckedContinuation<Void, Never>?

    func signal() {
        let continuation: CheckedContinuation<Void, Never>?

        lock.lock()
        if let waitingContinuation = self.continuation {
            continuation = waitingContinuation
            self.continuation = nil
        } else {
            signalled = true
            continuation = nil
        }
        lock.unlock()

        continuation?.resume()
    }

    func wait() async {
        await withCheckedContinuation { continuation in
            let shouldResume: Bool

            lock.lock()
            if signalled {
                signalled = false
                shouldResume = true
            } else {
                self.continuation = continuation
                shouldResume = false
            }
            lock.unlock()

            if shouldResume {
                continuation.resume()
            }
        }
    }
}
