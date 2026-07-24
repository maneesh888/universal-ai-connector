import Foundation

internal enum UniversalAiContractValidation {
    static let maxJsonSafeInteger: Int64 = 9_007_199_254_740_991

    static func validateContractVersion(_ value: String) throws {
        guard value == UniversalAiRequest.currentContractVersion else {
            throw issue(
                "unsupported_contract_version",
                "/contractVersion",
                "Unsupported contractVersion '\(value)'."
            )
        }
    }

    static func validateProviderId(
        _ value: String,
        path: String
    ) throws {
        let bytes = Array(value.utf8)
        guard
            !bytes.isEmpty,
            bytes.count <= 64,
            isLowercaseAsciiLetterOrDigit(bytes[0]),
            isLowercaseAsciiLetterOrDigit(bytes[bytes.count - 1]),
            bytes.allSatisfy({
                isLowercaseAsciiLetterOrDigit($0) ||
                    $0 == 0x2E || $0 == 0x5F || $0 == 0x2D
            })
        else {
            throw issue(
                "invalid_provider_id",
                path,
                "Provider IDs must use at most 64 lowercase ASCII letters, digits, '.', '_', or '-'."
            )
        }
    }

    static func validateModelId(
        _ value: String,
        path: String
    ) throws {
        guard
            !value.isEmpty,
            value.utf8.count <= 256,
            value.unicodeScalars.allSatisfy({
                !$0.properties.isWhitespace && !isControl($0)
            })
        else {
            throw issue(
                "invalid_model_id",
                path,
                "Model IDs must be nonempty, contain no whitespace or control characters, and use at most 256 UTF-8 bytes."
            )
        }
    }

    static func validateCapabilityToken(
        _ value: String,
        code: String,
        path: String
    ) throws {
        let bytes = Array(value.utf8)
        guard
            !bytes.isEmpty,
            bytes.count <= 64,
            isLowercaseAsciiLetter(bytes[0]),
            bytes.dropFirst().allSatisfy({
                isLowercaseAsciiLetterOrDigit($0) ||
                    $0 == 0x2E || $0 == 0x5F || $0 == 0x2D
            })
        else {
            throw issue(
                code,
                path,
                "The value must be a 1-64 character lowercase ASCII token."
            )
        }
    }

    static func validateCapabilityDeclaration(
        support: UniversalAiCapabilitySupport,
        limits: [UniversalAiCapabilityLimitName: Int64]
    ) throws {
        guard limits.count <= 64 else {
            throw issue(
                "capability_limit_count_exceeded",
                "/limits",
                "Capability declarations must not contain more than 64 limits."
            )
        }
        for (name, value) in limits.sorted(by: {
            $0.key.rawValue < $1.key.rawValue
        }) {
            let path = "/limits/\(pointerToken(name.rawValue))"
            guard value >= 0 && value <= maxJsonSafeInteger else {
                throw issue(
                    "capability_limit_out_of_range",
                    path,
                    "Capability limits must be non-negative JSON-safe integers."
                )
            }
            if name == .maxSchemaBytes || name == .maxSchemaDepth {
                guard value > 0 else {
                    throw issue(
                        "capability_limit_out_of_range",
                        path,
                        "\(name.rawValue) must be greater than zero."
                    )
                }
            }
        }
        guard support != .unsupported || limits.isEmpty else {
            throw issue(
                "unsupported_capability_limits",
                "/limits",
                "Unsupported capabilities must not declare limits."
            )
        }
    }

    static func validateCapabilitySet(
        _ declarations:
            [UniversalAiCapabilityName: UniversalAiCapabilityDeclaration]
    ) throws {
        guard declarations.count <= 64 else {
            throw issue(
                "capability_count_exceeded",
                "",
                "Capability sets must not contain more than 64 declarations."
            )
        }
        for (name, declaration) in declarations.sorted(by: {
            $0.key.rawValue < $1.key.rawValue
        }) where name != .structuredOutput {
            for limit in declaration.limits.keys.sorted(by: {
                $0.rawValue < $1.rawValue
            }) where limit == .maxSchemaBytes || limit == .maxSchemaDepth {
                throw issue(
                    "capability_limit_not_applicable",
                    "/\(pointerToken(name.rawValue))/limits/" +
                        pointerToken(limit.rawValue),
                    "\(limit.rawValue) applies only to structured_output."
                )
            }
        }
    }

    static func validateModelTokenLimits(
        contextWindowTokens: Int64?,
        maxInputTokens: Int64?,
        maxOutputTokens: Int64?
    ) throws {
        try validateModelTokenLimit(
            contextWindowTokens,
            path: "/contextWindowTokens",
            maximum: maxJsonSafeInteger
        )
        try validateModelTokenLimit(
            maxInputTokens,
            path: "/maxInputTokens",
            maximum: maxJsonSafeInteger
        )
        try validateModelTokenLimit(
            maxOutputTokens,
            path: "/maxOutputTokens",
            maximum: 1_048_576
        )
        if
            let contextWindowTokens,
            let maxInputTokens,
            maxInputTokens > contextWindowTokens
        {
            throw issue(
                "model_input_limit_exceeds_context",
                "/maxInputTokens",
                "maxInputTokens must not exceed contextWindowTokens."
            )
        }
        if
            let contextWindowTokens,
            let maxOutputTokens,
            maxOutputTokens > contextWindowTokens
        {
            throw issue(
                "model_output_limit_exceeds_context",
                "/maxOutputTokens",
                "maxOutputTokens must not exceed contextWindowTokens."
            )
        }
    }

    static func validateModelDisplayName(_ value: String?) throws {
        guard let value else {
            return
        }
        guard
            value.unicodeScalars.contains(where: {
                !$0.properties.isWhitespace
            }),
            value.unicodeScalars.allSatisfy({ !isControl($0) })
        else {
            throw issue(
                "invalid_model_display_name",
                "/displayName",
                "Model display names must be non-blank and contain no control characters."
            )
        }
        guard value.utf8.count <= 256 else {
            throw issue(
                "model_display_name_too_large",
                "/displayName",
                "Model display names must not exceed 256 UTF-8 bytes."
            )
        }
    }

    static func validateErrorCategory(_ value: String) throws {
        let bytes = Array(value.utf8)
        guard
            !bytes.isEmpty,
            bytes.count <= 64,
            isLowercaseAsciiLetter(bytes[0]),
            bytes.dropFirst().allSatisfy({
                isLowercaseAsciiLetterOrDigit($0) ||
                    $0 == 0x2E || $0 == 0x5F || $0 == 0x2D
            })
        else {
            throw issue(
                "invalid_error_category",
                "/category",
                "Error categories must be 1-64 lowercase ASCII characters."
            )
        }
    }

    static func validateErrorCode(_ value: String) throws {
        let bytes = Array(value.utf8)
        guard
            !bytes.isEmpty,
            bytes.count <= 128,
            isLowercaseAsciiLetter(bytes[0]),
            bytes.dropFirst().allSatisfy({
                isLowercaseAsciiLetterOrDigit($0) ||
                    $0 == 0x2E || $0 == 0x5F || $0 == 0x2D
            })
        else {
            throw issue(
                "invalid_error_code",
                "/code",
                "Error codes must be 1-128 lowercase ASCII characters."
            )
        }
    }

    static func validateErrorMessage(_ value: String) throws {
        guard
            value.unicodeScalars.contains(where: {
                !$0.properties.isWhitespace
            }),
            value.utf8.count <= 4_096,
            value.unicodeScalars.allSatisfy({
                !isUnsafeErrorMessageScalar($0)
            })
        else {
            throw issue(
                "invalid_error_message",
                "/message",
                "Error messages must be non-blank, use at most 4096 UTF-8 bytes, and contain no unsafe control characters."
            )
        }
    }

    static func validateExtensionNamespace(_ value: String) throws {
        let bytes = Array(value.utf8)
        let labels = value.split(
            separator: ".",
            omittingEmptySubsequences: false
        )
        guard
            bytes.count <= 253,
            labels.count >= 2,
            labels.allSatisfy({ label in
                let labelBytes = Array(label.utf8)
                return !labelBytes.isEmpty &&
                    labelBytes.count <= 63 &&
                    isLowercaseAsciiLetterOrDigit(labelBytes[0]) &&
                    isLowercaseAsciiLetterOrDigit(
                        labelBytes[labelBytes.count - 1]
                    ) &&
                    labelBytes.allSatisfy({
                        isLowercaseAsciiLetterOrDigit($0) || $0 == 0x2D
                    })
            })
        else {
            throw issue(
                "invalid_extension_namespace",
                "",
                "Extension namespaces must be lowercase reverse-DNS names with at least two labels."
            )
        }
    }

    static func validateJsonNumber(
        _ value: String,
        path: String = ""
    ) throws {
        let bytes = Array(value.utf8)
        guard isJsonNumber(bytes) else {
            throw issue(
                "invalid_json_number",
                path,
                "Numbers must use the JSON number grammar."
            )
        }
    }

    private static func validateExtensionJsonNumber(
        _ value: String,
        path: String
    ) throws {
        let bytes = Array(value.utf8)
        guard bytes.count <= 128 else {
            throw issue(
                "extension_number_token_too_long",
                path,
                "Extension number tokens must not exceed 128 ASCII bytes."
            )
        }
        guard isJsonNumber(bytes) else {
            throw issue(
                "invalid_extension_number",
                path,
                "Extension numbers must use the JSON number grammar."
            )
        }
    }

    static func validateExtensions(
        _ entries:
            [UniversalAiExtensionNamespace: UniversalAiJsonObject],
        pathPrefix: String = ""
    ) throws {
        guard entries.count <= 16 else {
            throw issue(
                "extension_namespace_limit_exceeded",
                pathPrefix,
                "Extension bags must not exceed 16 namespaces."
            )
        }

        struct WorkItem {
            let value: UniversalAiJsonValue
            let path: String
            let containerDepth: Int
        }

        var compactBytes = 2 + max(entries.count - 1, 0)
        var work: [WorkItem] = []
        for (namespace, payload) in entries.sorted(by: {
            $0.key.rawValue < $1.key.rawValue
        }) {
            let namespacePath =
                pathPrefix + "/" + pointerToken(namespace.rawValue)
            compactBytes += jsonStringBytes(namespace.rawValue) + 1
            work.append(
                WorkItem(
                    value: .object(payload),
                    path: namespacePath,
                    containerDepth: 1
                )
            )
        }

        var nodes = 0
        while let item = work.popLast() {
            nodes += 1
            guard nodes <= 1_024 else {
                throw issue(
                    "extension_node_limit_exceeded",
                    item.path,
                    "Extension payloads must not exceed 1,024 values."
                )
            }

            switch item.value {
            case .null:
                compactBytes += 4
            case let .boolean(value):
                compactBytes += value ? 4 : 5
            case let .string(value):
                guard value.utf8.count <= 16_384 else {
                    throw issue(
                        "extension_string_too_long",
                        item.path,
                        "Extension string values must not exceed 16,384 UTF-8 bytes."
                    )
                }
                compactBytes += jsonStringBytes(value)
            case let .number(value):
                try validateExtensionJsonNumber(
                    value.rawValue,
                    path: item.path
                )
                compactBytes += value.rawValue.utf8.count
            case let .array(values):
                guard item.containerDepth <= 16 else {
                    throw issue(
                        "extension_depth_limit_exceeded",
                        item.path,
                        "Extension payloads must not exceed container depth 16."
                    )
                }
                guard values.count <= 256 else {
                    throw issue(
                        "extension_array_element_limit_exceeded",
                        item.path,
                        "Extension arrays must not exceed 256 elements."
                    )
                }
                compactBytes += 2 + max(values.count - 1, 0)
                for (index, value) in values.enumerated().reversed() {
                    work.append(
                        WorkItem(
                            value: value,
                            path: "\(item.path)/\(index)",
                            containerDepth:
                                item.containerDepth +
                                (value.isContainer ? 1 : 0)
                        )
                    )
                }
            case let .object(object):
                guard item.containerDepth <= 16 else {
                    throw issue(
                        "extension_depth_limit_exceeded",
                        item.path,
                        "Extension payloads must not exceed container depth 16."
                    )
                }
                guard object.members.count <= 256 else {
                    throw issue(
                        "extension_object_member_limit_exceeded",
                        item.path,
                        "Extension objects must not exceed 256 members."
                    )
                }
                compactBytes += 2 + max(object.members.count - 1, 0)
                for (name, value) in object.members.sorted(by: {
                    $0.key < $1.key
                }).reversed() {
                    let memberPath =
                        item.path + "/" + pointerToken(name)
                    guard !name.isEmpty,
                        name.unicodeScalars.allSatisfy({ !isControl($0) })
                    else {
                        throw issue(
                            "invalid_extension_member_name",
                            memberPath,
                            "Extension member names must be nonempty and contain no control characters."
                        )
                    }
                    guard name.utf8.count <= 256 else {
                        throw issue(
                            "extension_member_name_too_long",
                            memberPath,
                            "Extension member names must not exceed 256 UTF-8 bytes."
                        )
                    }
                    compactBytes += jsonStringBytes(name) + 1
                    work.append(
                        WorkItem(
                            value: value,
                            path: memberPath,
                            containerDepth:
                                item.containerDepth +
                                (value.isContainer ? 1 : 0)
                        )
                    )
                }
            }

            guard compactBytes <= 65_536 else {
                throw issue(
                    "extension_size_limit_exceeded",
                    pathPrefix,
                    "Extension bags must not exceed 65,536 compact JSON bytes."
                )
            }
        }
    }

    static func validateErrorMetadata(
        _ metadata: UniversalAiJsonObject?
    ) throws {
        guard let metadata else {
            return
        }

        struct WorkItem {
            let value: UniversalAiJsonValue
            let path: String
            let containerDepth: Int
        }

        var work = [
            WorkItem(
                value: .object(metadata),
                path: "/metadata",
                containerDepth: 1
            ),
        ]
        var nodes = 0
        while let item = work.popLast() {
            nodes += 1
            guard nodes <= 1_024 else {
                throw issue(
                    "extension_node_limit_exceeded",
                    item.path,
                    "Extension payloads must not exceed 1,024 values."
                )
            }

            switch item.value {
            case .null, .boolean:
                break
            case let .string(value):
                guard value.utf8.count <= 16_384 else {
                    throw issue(
                        "extension_string_too_long",
                        item.path,
                        "Extension string values must not exceed 16,384 UTF-8 bytes."
                    )
                }
            case let .number(value):
                try validateExtensionJsonNumber(
                    value.rawValue,
                    path: item.path
                )
            case let .array(values):
                guard item.containerDepth <= 16 else {
                    throw issue(
                        "extension_depth_limit_exceeded",
                        item.path,
                        "Extension payloads must not exceed container depth 16."
                    )
                }
                guard values.count <= 256 else {
                    throw issue(
                        "extension_array_element_limit_exceeded",
                        item.path,
                        "Extension arrays must not exceed 256 elements."
                    )
                }
                for (index, value) in values.enumerated().reversed() {
                    work.append(
                        WorkItem(
                            value: value,
                            path: "\(item.path)/\(index)",
                            containerDepth:
                                item.containerDepth +
                                (value.isContainer ? 1 : 0)
                        )
                    )
                }
            case let .object(object):
                guard item.containerDepth <= 16 else {
                    throw issue(
                        "extension_depth_limit_exceeded",
                        item.path,
                        "Extension payloads must not exceed container depth 16."
                    )
                }
                guard object.members.count <= 256 else {
                    throw issue(
                        "extension_object_member_limit_exceeded",
                        item.path,
                        "Extension objects must not exceed 256 members."
                    )
                }
                for (name, value) in object.members.sorted(by: {
                    $0.key < $1.key
                }).reversed() {
                    let memberPath =
                        item.path + "/" + pointerToken(name)
                    guard !name.isEmpty,
                        name.unicodeScalars.allSatisfy({ !isControl($0) })
                    else {
                        throw issue(
                            "invalid_extension_member_name",
                            memberPath,
                            "Extension member names must be nonempty and contain no control characters."
                        )
                    }
                    guard name.utf8.count <= 256 else {
                        throw issue(
                            "extension_member_name_too_long",
                            memberPath,
                            "Extension member names must not exceed 256 UTF-8 bytes."
                        )
                    }
                    work.append(
                        WorkItem(
                            value: value,
                            path: memberPath,
                            containerDepth:
                                item.containerDepth +
                                (value.isContainer ? 1 : 0)
                        )
                    )
                }
            }
        }
    }

    static func jsonSemanticallyEqual(
        _ lhs: UniversalAiJsonValue,
        _ rhs: UniversalAiJsonValue
    ) -> Bool {
        var work = [(lhs, rhs)]
        while let (left, right) = work.popLast() {
            switch (left, right) {
            case (.null, .null):
                continue
            case let (.boolean(leftValue), .boolean(rightValue)):
                guard leftValue == rightValue else {
                    return false
                }
            case let (.string(leftValue), .string(rightValue)):
                guard leftValue == rightValue else {
                    return false
                }
            case let (.number(leftValue), .number(rightValue)):
                guard
                    let normalizedLeft =
                        normalizeJsonNumber(leftValue.rawValue),
                    let normalizedRight =
                        normalizeJsonNumber(rightValue.rawValue),
                    normalizedLeft == normalizedRight
                else {
                    return false
                }
            case let (.array(leftValues), .array(rightValues)):
                guard leftValues.count == rightValues.count else {
                    return false
                }
                work.append(
                    contentsOf: zip(leftValues, rightValues)
                )
            case let (.object(leftObject), .object(rightObject)):
                guard
                    leftObject.members.count == rightObject.members.count
                else {
                    return false
                }
                for (name, leftValue) in leftObject.members {
                    guard let rightValue = rightObject.members[name] else {
                        return false
                    }
                    work.append((leftValue, rightValue))
                }
            default:
                return false
            }
        }
        return true
    }

    static func validateRequest(
        _ request: UniversalAiRequest
    ) throws {
        try validateContractVersion(request.contractVersion)
        try validateProviderId(
            request.target.providerId.rawValue,
            path: "/target/providerId"
        )
        try validateModelId(
            request.target.modelId.rawValue,
            path: "/target/modelId"
        )
        guard !request.input.isEmpty else {
            throw issue(
                "empty_input",
                "/input",
                "A request must contain at least one input item."
            )
        }
        guard request.input.count <= 128 else {
            throw issue(
                "input_item_limit_exceeded",
                "/input",
                "A request must not contain more than 128 input items."
            )
        }
        var totalInputBytes = 0
        for (index, input) in request.input.enumerated() {
            try validateCapabilityToken(
                input.role.rawValue,
                code: "invalid_input_role",
                path: "/input/\(index)/role"
            )
            guard input.content.unicodeScalars.contains(where: {
                !$0.properties.isWhitespace
            }) else {
                throw issue(
                    "blank_input_content",
                    "/input/\(index)/content",
                    "Input content must contain at least one non-whitespace character."
                )
            }
            let contentBytes = input.content.utf8.count
            guard contentBytes <= 262_144 else {
                throw issue(
                    "input_content_too_large",
                    "/input/\(index)/content",
                    "Input content must not exceed 262,144 UTF-8 bytes."
                )
            }
            totalInputBytes += contentBytes
        }
        guard totalInputBytes <= 524_288 else {
            throw issue(
                "input_content_total_too_large",
                "/input",
                "Request input must not exceed 524,288 total UTF-8 bytes."
            )
        }

        try validateCapabilityToken(
            request.responseFormat.kind.rawValue,
            code: "invalid_response_format_kind",
            path: "/responseFormat/kind"
        )
        switch request.responseFormat.kind {
        case .plainText:
            guard request.responseFormat.schema == nil else {
                throw issue(
                    "unexpected_response_schema",
                    "/responseFormat/schema",
                    "Plain-text responseFormat must not contain a schema."
                )
            }
        case .jsonSchema:
            guard let schema = request.responseFormat.schema else {
                throw issue(
                    "missing_response_schema",
                    "/responseFormat/schema",
                    "Structured responseFormat requires a schema."
                )
            }
            try validateStructuredSchemaSafety(
                schema.value,
                path: "/responseFormat/schema"
            )
        default:
            guard request.responseFormat.schema == nil else {
                throw issue(
                    "unexpected_response_schema",
                    "/responseFormat/schema",
                    "Future responseFormat kinds must place extra data in extensions."
                )
            }
        }

        try validateGeneration(request.generation)
        try validateExtensions(
            request.extensions.entries,
            pathPrefix: "/extensions"
        )
    }

    private static func validateGeneration(
        _ generation: UniversalAiGenerationParameters
    ) throws {
        if let value = generation.maxOutputTokens {
            guard value >= 1 && value <= 1_048_576 else {
                throw issue(
                    "max_output_tokens_out_of_range",
                    "/generation/maxOutputTokens",
                    "maxOutputTokens must be between 1 and 1,048,576."
                )
            }
        }
        if let value = generation.temperature {
            guard value.isFinite && value >= 0 && value <= 1 else {
                throw issue(
                    "temperature_out_of_range",
                    "/generation/temperature",
                    "temperature must be finite and between 0 and 1."
                )
            }
        }
        if let value = generation.topP {
            guard value.isFinite && value > 0 && value <= 1 else {
                throw issue(
                    "top_p_out_of_range",
                    "/generation/topP",
                    "topP must be finite, greater than 0, and at most 1."
                )
            }
        }
        guard generation.stopSequences.count <= 16 else {
            throw issue(
                "stop_sequence_limit_exceeded",
                "/generation/stopSequences",
                "stopSequences must not contain more than 16 values."
            )
        }
        var seen = Set<String>()
        var totalBytes = 0
        for (index, value) in generation.stopSequences.enumerated() {
            guard seen.insert(value).inserted else {
                throw issue(
                    "duplicate_stop_sequence",
                    "/generation/stopSequences/\(index)",
                    "stopSequences must not contain duplicate values."
                )
            }
            guard !value.isEmpty else {
                throw issue(
                    "invalid_stop_sequence",
                    "/generation/stopSequences/\(index)",
                    "Stop sequences must not be empty."
                )
            }
            let bytes = value.utf8.count
            guard bytes <= 1_024 else {
                throw issue(
                    "stop_sequence_too_large",
                    "/generation/stopSequences/\(index)",
                    "Stop sequences must not exceed 1,024 UTF-8 bytes."
                )
            }
            totalBytes += bytes
        }
        guard totalBytes <= 4_096 else {
            throw issue(
                "stop_sequence_total_too_large",
                "/generation/stopSequences",
                "stopSequences must not exceed 4,096 total UTF-8 bytes."
            )
        }
    }

    private static func validateStructuredSchemaSafety(
        _ root: UniversalAiJsonValue,
        path: String
    ) throws {
        struct JsonItem {
            let value: UniversalAiJsonValue
            let path: String
            let containerDepth: Int
        }
        var compactBytes = 0
        var jsonWork = [JsonItem(value: root, path: path, containerDepth: 1)]
        while let item = jsonWork.popLast() {
            switch item.value {
            case .null:
                compactBytes += 4
            case let .boolean(value):
                compactBytes += value ? 4 : 5
            case let .string(value):
                compactBytes += jsonStringBytes(value)
            case let .number(value):
                try validateJsonNumber(value.rawValue, path: item.path)
                compactBytes += value.rawValue.utf8.count
            case let .array(values):
                guard item.containerDepth <= 64 else {
                    throw issue(
                        "schema_document_limit_exceeded",
                        item.path,
                        "Structured-output schema JSON exceeds the canonical document depth."
                    )
                }
                compactBytes += 2 + max(values.count - 1, 0)
                for (index, value) in values.enumerated().reversed() {
                    jsonWork.append(
                        JsonItem(
                            value: value,
                            path: "\(item.path)/\(index)",
                            containerDepth:
                                item.containerDepth +
                                (value.isContainer ? 1 : 0)
                        )
                    )
                }
            case let .object(object):
                guard item.containerDepth <= 64 else {
                    throw issue(
                        "schema_document_limit_exceeded",
                        item.path,
                        "Structured-output schema JSON exceeds the canonical document depth."
                    )
                }
                compactBytes += 2 + max(object.members.count - 1, 0)
                for (name, value) in object.members.sorted(by: {
                    $0.key < $1.key
                }).reversed() {
                    compactBytes += jsonStringBytes(name) + 1
                    jsonWork.append(
                        JsonItem(
                            value: value,
                            path: item.path + "/" + pointerToken(name),
                            containerDepth:
                                item.containerDepth +
                                (value.isContainer ? 1 : 0)
                        )
                    )
                }
            }
            guard compactBytes <= 65_536 else {
                throw issue(
                    "schema_size_limit_exceeded",
                    path,
                    "Structured-output schema exceeds 65,536 compact UTF-8 bytes."
                )
            }
        }

        struct SchemaItem {
            let value: UniversalAiJsonValue
            let path: String
            let depth: Int
        }
        var schemaWork = [SchemaItem(value: root, path: path, depth: 1)]
        var schemaNodes = 0
        while let item = schemaWork.popLast() {
            guard item.depth <= 32 else {
                throw issue(
                    "schema_depth_limit_exceeded",
                    item.path,
                    "Structured-output schemas must not exceed schema depth 32."
                )
            }
            schemaNodes += 1
            guard schemaNodes <= 512 else {
                throw issue(
                    "schema_node_limit_exceeded",
                    item.path,
                    "Structured-output schemas must not exceed 512 schema nodes."
                )
            }

            guard case let .object(object) = item.value else {
                continue
            }
            for (keyword, value) in object.members {
                let keywordPath =
                    item.path + "/" + pointerToken(keyword)
                switch keyword {
                case "$defs", "properties":
                    guard case let .object(children) = value else {
                        continue
                    }
                    for (name, child) in children.members {
                        schemaWork.append(
                            SchemaItem(
                                value: child,
                                path:
                                    keywordPath + "/" + pointerToken(name),
                                depth: item.depth + 1
                            )
                        )
                    }
                case "additionalProperties", "items", "not":
                    schemaWork.append(
                        SchemaItem(
                            value: value,
                            path: keywordPath,
                            depth: item.depth + 1
                        )
                    )
                case "prefixItems", "allOf", "anyOf", "oneOf":
                    guard case let .array(children) = value else {
                        continue
                    }
                    for (index, child) in children.enumerated() {
                        schemaWork.append(
                            SchemaItem(
                                value: child,
                                path: "\(keywordPath)/\(index)",
                                depth: item.depth + 1
                            )
                        )
                    }
                default:
                    break
                }
            }
        }
    }

    private static func validateModelTokenLimit(
        _ value: Int64?,
        path: String,
        maximum: Int64
    ) throws {
        guard let value else {
            return
        }
        guard value >= 1 && value <= maximum else {
            throw issue(
                "model_token_limit_out_of_range",
                path,
                "Model token limits must be positive integers no greater than \(maximum)."
            )
        }
    }

    private static func isJsonNumber(_ bytes: [UInt8]) -> Bool {
        guard !bytes.isEmpty else {
            return false
        }
        var index = 0
        if bytes[index] == 0x2D {
            index += 1
            guard index < bytes.count else {
                return false
            }
        }
        if bytes[index] == 0x30 {
            index += 1
            if index < bytes.count && isDigit(bytes[index]) {
                return false
            }
        } else {
            guard isNonzeroDigit(bytes[index]) else {
                return false
            }
            repeat {
                index += 1
            } while index < bytes.count && isDigit(bytes[index])
        }
        if index < bytes.count && bytes[index] == 0x2E {
            index += 1
            guard index < bytes.count && isDigit(bytes[index]) else {
                return false
            }
            repeat {
                index += 1
            } while index < bytes.count && isDigit(bytes[index])
        }
        if
            index < bytes.count &&
            (bytes[index] == 0x65 || bytes[index] == 0x45)
        {
            index += 1
            if
                index < bytes.count &&
                (bytes[index] == 0x2B || bytes[index] == 0x2D)
            {
                index += 1
            }
            guard index < bytes.count && isDigit(bytes[index]) else {
                return false
            }
            repeat {
                index += 1
            } while index < bytes.count && isDigit(bytes[index])
        }
        return index == bytes.count
    }

    private static func normalizeJsonNumber(
        _ rawValue: String
    ) -> NormalizedJsonNumber? {
        var token = Array(rawValue.utf8)
        guard isJsonNumber(token) else {
            return nil
        }

        let negative = token.first == 0x2D
        if negative {
            token.removeFirst()
        }
        let exponentIndex = token.firstIndex {
            $0 == 0x65 || $0 == 0x45
        }
        let mantissa =
            Array(token[..<(exponentIndex ?? token.endIndex)])
        let exponentBytes =
            exponentIndex.map {
                Array(token[token.index(after: $0)...])
            } ?? [0x30]
        let decimalIndex = mantissa.firstIndex(of: 0x2E)
        let fractionDigits =
            decimalIndex.map { mantissa.count - $0 - 1 } ?? 0
        let coefficient = mantissa.filter { $0 != 0x2E }
        guard let firstNonzero = coefficient.firstIndex(where: {
            $0 != 0x30
        }) else {
            return NormalizedJsonNumber(
                negative: false,
                digits: "0",
                exponent: .zero
            )
        }

        let withoutLeadingZeroes = Array(coefficient[firstNonzero...])
        let trailingZeroes =
            withoutLeadingZeroes.reversed().prefix {
                $0 == 0x30
            }.count
        let significantBytes =
            Array(withoutLeadingZeroes.dropLast(trailingZeroes))
        let exponent =
            SignedMagnitude.parse(exponentBytes).plus(
                .fromInt(trailingZeroes - fractionDigits)
            )
        return NormalizedJsonNumber(
            negative: negative,
            digits: String(decoding: significantBytes, as: UTF8.self),
            exponent: exponent
        )
    }

    private struct NormalizedJsonNumber: Equatable {
        let negative: Bool
        let digits: String
        let exponent: SignedMagnitude
    }

    private struct SignedMagnitude: Equatable {
        let sign: Int
        let magnitude: String

        static let zero = SignedMagnitude(sign: 0, magnitude: "0")

        static func parse(_ rawValue: [UInt8]) -> SignedMagnitude {
            let negative = rawValue.first == 0x2D
            let unsigned =
                rawValue.drop {
                    $0 == 0x2D || $0 == 0x2B
                }.drop {
                    $0 == 0x30
                }
            guard !unsigned.isEmpty else {
                return .zero
            }
            return SignedMagnitude(
                sign: negative ? -1 : 1,
                magnitude: String(decoding: unsigned, as: UTF8.self)
            )
        }

        static func fromInt(_ value: Int) -> SignedMagnitude {
            if value == 0 {
                return .zero
            }
            return SignedMagnitude(
                sign: value < 0 ? -1 : 1,
                magnitude: String(abs(value))
            )
        }

        func plus(_ other: SignedMagnitude) -> SignedMagnitude {
            if sign == 0 {
                return other
            }
            if other.sign == 0 {
                return self
            }
            if sign == other.sign {
                return SignedMagnitude(
                    sign: sign,
                    magnitude: Self.addMagnitudes(
                        magnitude,
                        other.magnitude
                    )
                )
            }

            let comparison =
                Self.compareMagnitudes(magnitude, other.magnitude)
            if comparison == 0 {
                return .zero
            }
            if comparison > 0 {
                return SignedMagnitude(
                    sign: sign,
                    magnitude: Self.subtractMagnitudes(
                        magnitude,
                        other.magnitude
                    )
                )
            }
            return SignedMagnitude(
                sign: other.sign,
                magnitude: Self.subtractMagnitudes(
                    other.magnitude,
                    magnitude
                )
            )
        }

        private static func compareMagnitudes(
            _ left: String,
            _ right: String
        ) -> Int {
            if left.utf8.count != right.utf8.count {
                return left.utf8.count < right.utf8.count ? -1 : 1
            }
            if left == right {
                return 0
            }
            return left < right ? -1 : 1
        }

        private static func addMagnitudes(
            _ left: String,
            _ right: String
        ) -> String {
            let leftBytes = Array(left.utf8)
            let rightBytes = Array(right.utf8)
            var leftIndex = leftBytes.count - 1
            var rightIndex = rightBytes.count - 1
            var carry = 0
            var reversed: [UInt8] = []
            while leftIndex >= 0 || rightIndex >= 0 || carry != 0 {
                let leftDigit =
                    leftIndex >= 0 ? Int(leftBytes[leftIndex] - 0x30) : 0
                let rightDigit =
                    rightIndex >= 0 ? Int(rightBytes[rightIndex] - 0x30) : 0
                let sum = leftDigit + rightDigit + carry
                reversed.append(UInt8(sum % 10) + 0x30)
                carry = sum / 10
                leftIndex -= 1
                rightIndex -= 1
            }
            return String(decoding: reversed.reversed(), as: UTF8.self)
        }

        private static func subtractMagnitudes(
            _ larger: String,
            _ smaller: String
        ) -> String {
            let largerBytes = Array(larger.utf8)
            let smallerBytes = Array(smaller.utf8)
            var largerIndex = largerBytes.count - 1
            var smallerIndex = smallerBytes.count - 1
            var borrow = 0
            var reversed: [UInt8] = []
            while largerIndex >= 0 {
                var difference =
                    Int(largerBytes[largerIndex] - 0x30) -
                    (smallerIndex >= 0
                        ? Int(smallerBytes[smallerIndex] - 0x30)
                        : 0) -
                    borrow
                if difference < 0 {
                    difference += 10
                    borrow = 1
                } else {
                    borrow = 0
                }
                reversed.append(UInt8(difference) + 0x30)
                largerIndex -= 1
                smallerIndex -= 1
            }
            let result =
                Array(reversed.reversed()).drop {
                    $0 == 0x30
                }
            return result.isEmpty
                ? "0"
                : String(decoding: result, as: UTF8.self)
        }
    }

    private static func jsonStringBytes(_ value: String) -> Int {
        var count = 2
        for scalar in value.unicodeScalars {
            switch scalar.value {
            case 0x22, 0x5C:
                count += 2
            case 0x08, 0x09, 0x0A, 0x0C, 0x0D:
                count += 2
            case 0x00...0x1F:
                count += 6
            default:
                count += String(scalar).utf8.count
            }
        }
        return count
    }

    private static func isLowercaseAsciiLetter(_ value: UInt8) -> Bool {
        value >= 0x61 && value <= 0x7A
    }

    private static func isLowercaseAsciiLetterOrDigit(
        _ value: UInt8
    ) -> Bool {
        isLowercaseAsciiLetter(value) || isDigit(value)
    }

    private static func isDigit(_ value: UInt8) -> Bool {
        value >= 0x30 && value <= 0x39
    }

    private static func isNonzeroDigit(_ value: UInt8) -> Bool {
        value >= 0x31 && value <= 0x39
    }

    private static func isControl(_ scalar: UnicodeScalar) -> Bool {
        scalar.value <= 0x1F || scalar.value == 0x7F
    }

    private static func isUnsafeErrorMessageScalar(
        _ scalar: UnicodeScalar
    ) -> Bool {
        let value = scalar.value
        return value <= 0x1F ||
            (value >= 0x7F && value <= 0x9F) ||
            value == 0x061C ||
            (value >= 0x200E && value <= 0x200F) ||
            (value >= 0x2028 && value <= 0x202E) ||
            (value >= 0x2066 && value <= 0x2069)
    }

    private static func pointerToken(_ value: String) -> String {
        value.replacingOccurrences(of: "~", with: "~0")
            .replacingOccurrences(of: "/", with: "~1")
    }

    private static func issue(
        _ code: String,
        _ path: String,
        _ message: String
    ) -> UniversalAiContractValidationError {
        UniversalAiContractValidationError(
            code: code,
            path: path,
            message: message
        )
    }
}

internal extension UniversalAiJsonValue {
    var isContainer: Bool {
        switch self {
        case .array, .object:
            return true
        default:
            return false
        }
    }
}

internal extension UniversalAiContractValidationError {
    var requestConnectorError: UniversalAiConnectorError {
        UniversalAiConnectorError(
            trustedCategory: .validation,
            code: .invalidRequest,
            message: "Request validation failed.",
            metadata: UniversalAiJsonObject([
                "path": .string(path),
                "validationCode": .string(code),
            ])
        )
    }
}
