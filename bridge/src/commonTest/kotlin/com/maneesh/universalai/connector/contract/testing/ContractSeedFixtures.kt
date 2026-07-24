package com.maneesh.universalai.connector.contract.testing

internal enum class ContractSeedFamily {
    CONTRACT_ENVELOPE,
    EXTENSION_VALUE,
    EXTENSIONS,
    PROVIDER_ID,
    MODEL_ID,
    MODEL_TARGET,
    TEXT_INPUT,
    RESPONSE_FORMAT,
    GENERATION_PARAMETERS,
    REQUEST,
    OPERATION_ID,
    OUTPUT,
    USAGE,
    ERROR,
    RESPONSE,
    STREAM_EVENT,
    STREAM_SEQUENCE,
    CAPABILITY_SET,
    PROVIDER_CAPABILITY_PROFILE,
    MODEL_TOKEN_LIMITS,
    MODEL_DESCRIPTOR,
}

internal enum class ContractSeedLayer {
    SCHEMA,
    SEMANTIC,
}

internal data class ContractSeedFixture(
    val id: String,
    val repositoryPath: String,
    val family: ContractSeedFamily,
    val schemaId: String,
    val json: String,
    val expectedLayer: ContractSeedLayer? = null,
    val expectedCode: String? = null,
    val expectedKeyword: String? = null,
    val expectedPath: String? = null,
    val compatibilityPurpose: String? = null,
) {
    val isValid: Boolean
        get() = expectedLayer == null && expectedCode == null
}

/**
 * A common-test mirror of the tracked authoritative contract bundle.
 *
 * JVM tooling mechanically checks every [repositoryPath] and manifest record against this mirror,
 * while every configured Kotlin target executes the same fixture semantics without filesystem
 * access.
 */
internal object ContractSeedFixtures {
    const val CONTRACT_VERSION: String = "1"
    const val ENVELOPE_SCHEMA_ID: String = "urn:universal-ai-connector:contract:v1:envelope"
    const val EXTENSION_VALUE_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:extension-value"
    const val EXTENSIONS_SCHEMA_ID: String = "urn:universal-ai-connector:contract:v1:extensions"
    const val PROVIDER_ID_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:provider-id"
    const val MODEL_ID_SCHEMA_ID: String = "urn:universal-ai-connector:contract:v1:model-id"
    const val MODEL_TARGET_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:model-target"
    const val TEXT_INPUT_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:text-input"
    const val RESPONSE_FORMAT_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:response-format"
    const val GENERATION_PARAMETERS_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:generation-parameters"
    const val REQUEST_SCHEMA_ID: String = "urn:universal-ai-connector:contract:v1:request"
    const val OPERATION_ID_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:operation-id"
    const val OUTPUT_SCHEMA_ID: String = "urn:universal-ai-connector:contract:v1:output"
    const val USAGE_SCHEMA_ID: String = "urn:universal-ai-connector:contract:v1:usage"
    const val ERROR_SCHEMA_ID: String = "urn:universal-ai-connector:contract:v1:error"
    const val RESPONSE_SCHEMA_ID: String = "urn:universal-ai-connector:contract:v1:response"
    const val STREAM_EVENT_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:stream-event"
    const val STREAM_SEQUENCE_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:stream-sequence"
    const val CAPABILITY_SET_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:capability-set"
    const val PROVIDER_CAPABILITY_PROFILE_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:provider-capability-profile"
    const val MODEL_TOKEN_LIMITS_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:model-token-limits"
    const val MODEL_DESCRIPTOR_SCHEMA_ID: String =
        "urn:universal-ai-connector:contract:v1:model-descriptor"

    val all: List<ContractSeedFixture> =
        listOf(
            ContractSeedFixture(
                id = "v1-envelope-minimal",
                repositoryPath = "contracts/fixtures/v1/valid/contract-envelope-minimal.json",
                family = ContractSeedFamily.CONTRACT_ENVELOPE,
                schemaId = ENVELOPE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1"
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-extension-value-minimal",
                repositoryPath = "contracts/fixtures/v1/valid/extension-value-minimal.json",
                family = ContractSeedFamily.EXTENSION_VALUE,
                schemaId = EXTENSION_VALUE_SCHEMA_ID,
                json = "null",
            ),
            ContractSeedFixture(
                id = "v1-extension-value-complete",
                repositoryPath = "contracts/fixtures/v1/valid/extension-value-complete.json",
                family = ContractSeedFamily.EXTENSION_VALUE,
                schemaId = EXTENSION_VALUE_SCHEMA_ID,
                json =
                    """
                    {
                      "nullValue": null,
                      "booleanValue": true,
                      "stringValue": "preserved",
                      "numberValue": 1.2300e+40,
                      "arrayValue": [
                        null,
                        false,
                        "nested",
                        -2.5
                      ],
                      "objectValue": {
                        "nested": true
                      }
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-extension-value-number-token-too-long",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/extension-value-number-token-too-long.json",
                family = ContractSeedFamily.EXTENSION_VALUE,
                schemaId = EXTENSION_VALUE_SCHEMA_ID,
                json =
                    "9999999999999999999999999999999999999999999999999999999999999999" +
                        "99999999999999999999999999999999999999999999999999999999999999999",
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "extension_number_token_too_long",
                expectedPath = "",
            ),
            ContractSeedFixture(
                id = "v1-extension-value-future-object",
                repositoryPath =
                    "contracts/fixtures/v1/compatibility/extension-value-future-object.json",
                family = ContractSeedFamily.EXTENSION_VALUE,
                schemaId = EXTENSION_VALUE_SCHEMA_ID,
                json =
                    """
                    {
                      "futureMember": {
                        "futureNested": [
                          "preserved",
                          1.2300e+40,
                          null
                        ]
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve unknown nested extension members and exact number tokens.",
            ),
            ContractSeedFixture(
                id = "v1-extension-primitives",
                repositoryPath = "contracts/fixtures/v1/valid/extension-primitives.json",
                family = ContractSeedFamily.EXTENSIONS,
                schemaId = EXTENSIONS_SCHEMA_ID,
                json =
                    """
                    {
                      "com.example.connector": {
                        "nullValue": null,
                        "booleanValue": true,
                        "stringValue": "preserved",
                        "numberValue": 1.2300e+4,
                        "arrayValue": [
                          null,
                          false,
                          "nested",
                          -2.5
                        ],
                        "objectValue": {
                          "nested": true
                        }
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve every JSON value kind, including nested null and the raw numeric token.",
            ),
            ContractSeedFixture(
                id = "v1-extension-value-depth-boundary",
                repositoryPath =
                    "contracts/fixtures/v1/valid/extension-value-depth-boundary.json",
                family = ContractSeedFamily.EXTENSION_VALUE,
                schemaId = EXTENSION_VALUE_SCHEMA_ID,
                json = "[".repeat(16) + "null" + "]".repeat(16),
            ),
            ContractSeedFixture(
                id = "v1-extension-value-depth-exceeded",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/extension-value-depth-exceeded.json",
                family = ContractSeedFamily.EXTENSION_VALUE,
                schemaId = EXTENSION_VALUE_SCHEMA_ID,
                json = "[".repeat(17) + "null" + "]".repeat(17),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "extension_depth_limit_exceeded",
                expectedPath = "/" + List(16) { "0" }.joinToString("/"),
            ),
            ContractSeedFixture(
                id = "v1-extension-value-member-name-utf8-boundary",
                repositoryPath =
                    "contracts/fixtures/v1/valid/extension-value-member-name-utf8-boundary.json",
                family = ContractSeedFamily.EXTENSION_VALUE,
                schemaId = EXTENSION_VALUE_SCHEMA_ID,
                json =
                    """
                    {
                      "${"é".repeat(128)}": null
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-extension-value-member-name-utf8-exceeded",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/extension-value-member-name-utf8-exceeded.json",
                family = ContractSeedFamily.EXTENSION_VALUE,
                schemaId = EXTENSION_VALUE_SCHEMA_ID,
                json =
                    """
                    {
                      "${"é".repeat(129)}": null
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "extension_member_name_too_long",
                expectedPath = "/${"é".repeat(129)}",
            ),
            ContractSeedFixture(
                id = "v1-extensions-namespace-count-boundary",
                repositoryPath =
                    "contracts/fixtures/v1/valid/extensions-namespace-count-boundary.json",
                family = ContractSeedFamily.EXTENSIONS,
                schemaId = EXTENSIONS_SCHEMA_ID,
                json =
                    """
                    {
                      "com.example.namespace0": {},
                      "com.example.namespace1": {},
                      "com.example.namespace2": {},
                      "com.example.namespace3": {},
                      "com.example.namespace4": {},
                      "com.example.namespace5": {},
                      "com.example.namespace6": {},
                      "com.example.namespace7": {},
                      "com.example.namespace8": {},
                      "com.example.namespace9": {},
                      "com.example.namespace10": {},
                      "com.example.namespace11": {},
                      "com.example.namespace12": {},
                      "com.example.namespace13": {},
                      "com.example.namespace14": {},
                      "com.example.namespace15": {}
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-extensions-namespace-count-exceeded",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/extensions-namespace-count-exceeded.json",
                family = ContractSeedFamily.EXTENSIONS,
                schemaId = EXTENSIONS_SCHEMA_ID,
                json =
                    """
                    {
                      "com.example.namespace0": {},
                      "com.example.namespace1": {},
                      "com.example.namespace2": {},
                      "com.example.namespace3": {},
                      "com.example.namespace4": {},
                      "com.example.namespace5": {},
                      "com.example.namespace6": {},
                      "com.example.namespace7": {},
                      "com.example.namespace8": {},
                      "com.example.namespace9": {},
                      "com.example.namespace10": {},
                      "com.example.namespace11": {},
                      "com.example.namespace12": {},
                      "com.example.namespace13": {},
                      "com.example.namespace14": {},
                      "com.example.namespace15": {},
                      "com.example.namespace16": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "extension_namespace_limit_exceeded",
                expectedKeyword = "maxProperties",
                expectedPath = "",
            ),
            ContractSeedFixture(
                id = "v1-extensions-invalid-namespace",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/extensions-invalid-namespace.json",
                family = ContractSeedFamily.EXTENSIONS,
                schemaId = EXTENSIONS_SCHEMA_ID,
                json =
                    """
                    {
                      "Bad.Namespace": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_extension_namespace",
                expectedKeyword = "propertyNames",
                expectedPath = "",
            ),
            ContractSeedFixture(
                id = "v1-extensions-non-object-payload",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/extensions-non-object-payload.json",
                family = ContractSeedFamily.EXTENSIONS,
                schemaId = EXTENSIONS_SCHEMA_ID,
                json =
                    """
                    {
                      "com.example.connector": [
                        "not",
                        "an",
                        "object"
                      ]
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_extension_payload",
                expectedKeyword = "type",
                expectedPath = "/com.example.connector",
            ),
            ContractSeedFixture(
                id = "v1-envelope-missing-version",
                repositoryPath = "contracts/fixtures/v1/invalid/contract-envelope-missing-version.json",
                family = ContractSeedFamily.CONTRACT_ENVELOPE,
                schemaId = ENVELOPE_SCHEMA_ID,
                json = "{}",
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "missing_contract_version",
                expectedKeyword = "required",
                expectedPath = "",
            ),
            ContractSeedFixture(
                id = "v1-envelope-null-version",
                repositoryPath = "contracts/fixtures/v1/invalid/contract-envelope-null-version.json",
                family = ContractSeedFamily.CONTRACT_ENVELOPE,
                schemaId = ENVELOPE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": null
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_contract_version",
                expectedKeyword = "const",
                expectedPath = "/contractVersion",
            ),
            ContractSeedFixture(
                id = "v1-extension-number-token-too-long",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/extension-number-token-too-long.json",
                family = ContractSeedFamily.EXTENSIONS,
                schemaId = EXTENSIONS_SCHEMA_ID,
                json =
                    """
                    {
                      "com.example.connector": {
                        "numberValue": 999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999999
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "extension_number_token_too_long",
                expectedPath = "/com.example.connector/numberValue",
            ),
            ContractSeedFixture(
                id = "v1-envelope-future-member",
                repositoryPath =
                    "contracts/fixtures/v1/compatibility/contract-envelope-future-member.json",
                family = ContractSeedFamily.CONTRACT_ENVELOPE,
                schemaId = ENVELOPE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "futureMember": {
                        "enabled": true,
                        "mode": "future"
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "A V1 reader accepts and ignores an additive ordinary member it does not understand.",
            ),
            ContractSeedFixture(
                id = "v1-request-minimal",
                repositoryPath = "contracts/fixtures/v1/valid/request-minimal.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ]
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-request-complete",
                repositoryPath = "contracts/fixtures/v1/valid/request-complete.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "system",
                          "content": "Answer concisely."
                        },
                        {
                          "role": "developer",
                          "content": "Return only the requested shape."
                        },
                        {
                          "role": "user",
                          "content": "Return an answer object."
                        },
                        {
                          "role": "assistant",
                          "content": "Acknowledged."
                        }
                      ],
                      "responseFormat": {
                        "kind": "json_schema",
                        "schema": {
                          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
                          "title": "Answer",
                          "type": "object",
                          "properties": {
                            "answer": {
                              "type": "string",
                              "minLength": 1
                            }
                          },
                          "required": [
                            "answer"
                          ],
                          "additionalProperties": false
                        }
                      },
                      "generation": {
                        "maxOutputTokens": 512,
                        "temperature": 0.2,
                        "topP": 0.95,
                        "stopSequences": [
                          "\nEND"
                        ]
                      },
                      "extensions": {
                        "com.example.connector": {
                          "mode": "future",
                          "priority": 1.2300e+2,
                          "nested": {
                            "enabled": true
                          }
                        }
                      }
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-request-boundary-boolean-schema",
                repositoryPath =
                    "contracts/fixtures/v1/valid/request-boundary-boolean-schema.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "a",
                        "modelId": "モデル/v1"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "x"
                        }
                      ],
                      "responseFormat": {
                        "kind": "json_schema",
                        "schema": true
                      },
                      "generation": {
                        "maxOutputTokens": 1048576,
                        "temperature": 0.0,
                        "topP": 1.0,
                        "stopSequences": []
                      },
                      "extensions": {}
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-request-future-values",
                repositoryPath =
                    "contracts/fixtures/v1/compatibility/request-future-values.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "future-provider",
                        "modelId": "future/model:v2",
                        "futureTargetMember": true
                      },
                      "input": [
                        {
                          "role": "critic_v2",
                          "content": "Preserve this role.",
                          "futureInputMember": {
                            "mode": "future"
                          }
                        }
                      ],
                      "responseFormat": {
                        "kind": "future_binary",
                        "futureFormatMember": {
                          "encoding": "future"
                        }
                      },
                      "generation": {
                        "futureGenerationMember": 42
                      },
                      "extensions": {
                        "com.example.future": {
                          "preserved": {
                            "value": null
                          }
                        }
                      },
                      "futureRequestMember": [
                        1,
                        2,
                        3
                      ]
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Unknown ordinary members are ignored while future input-role and " +
                        "response-format values and governed extensions remain distinguishable.",
            ),
            ContractSeedFixture(
                id = "v1-request-missing-version",
                repositoryPath = "contracts/fixtures/v1/invalid/request-missing-version.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ]
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "missing_contract_version",
                expectedKeyword = "required",
                expectedPath = "",
            ),
            ContractSeedFixture(
                id = "v1-request-null-version",
                repositoryPath = "contracts/fixtures/v1/invalid/request-null-version.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": null,
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ]
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_contract_version",
                expectedKeyword = "const",
                expectedPath = "/contractVersion",
            ),
            ContractSeedFixture(
                id = "v1-request-future-version",
                repositoryPath = "contracts/fixtures/v1/invalid/request-future-version.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "2",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ]
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "unsupported_contract_version",
                expectedKeyword = "const",
                expectedPath = "/contractVersion",
            ),
            ContractSeedFixture(
                id = "v1-request-null-response-format",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-null-response-format.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "responseFormat": null
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "null_response_format",
                expectedKeyword = "type",
                expectedPath = "/responseFormat",
            ),
            ContractSeedFixture(
                id = "v1-request-empty-input",
                repositoryPath = "contracts/fixtures/v1/invalid/request-empty-input.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": []
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "empty_input",
                expectedKeyword = "minItems",
                expectedPath = "/input",
            ),
            ContractSeedFixture(
                id = "v1-request-temperature-out-of-range",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-temperature-out-of-range.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "generation": {
                        "temperature": 1.1
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "temperature_out_of_range",
                expectedKeyword = "maximum",
                expectedPath = "/generation/temperature",
            ),
            ContractSeedFixture(
                id = "v1-request-invalid-provider-id",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-invalid-provider-id.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "OpenAI",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ]
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_provider_id",
                expectedKeyword = "pattern",
                expectedPath = "/target/providerId",
            ),
            ContractSeedFixture(
                id = "v1-request-invalid-model-id",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-invalid-model-id.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "model with spaces"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ]
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_model_id",
                expectedPath = "/target/modelId",
            ),
            ContractSeedFixture(
                id = "v1-request-blank-input",
                repositoryPath = "contracts/fixtures/v1/invalid/request-blank-input.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "   "
                        }
                      ]
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "blank_input_content",
                expectedPath = "/input/0/content",
            ),
            ContractSeedFixture(
                id = "v1-request-json-schema-missing-schema",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-json-schema-missing-schema.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "responseFormat": {
                        "kind": "json_schema"
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "missing_response_schema",
                expectedPath = "/responseFormat/schema",
            ),
            ContractSeedFixture(
                id = "v1-request-plain-text-with-schema",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-plain-text-with-schema.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "responseFormat": {
                        "kind": "plain_text",
                        "schema": {
                          "type": "string"
                        }
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "unexpected_response_schema",
                expectedPath = "/responseFormat/schema",
            ),
            ContractSeedFixture(
                id = "v1-request-duplicate-stop-sequence",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-duplicate-stop-sequence.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "generation": {
                        "stopSequences": [
                          "END",
                          "END"
                        ]
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "duplicate_stop_sequence",
                expectedPath = "/generation/stopSequences/1",
            ),
            ContractSeedFixture(
                id = "v1-request-schema-unsupported-keyword",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-schema-unsupported-keyword.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "responseFormat": {
                        "kind": "json_schema",
                        "schema": {
                          "type": "string",
                          "pattern": "[a-z]+"
                        }
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "unsupported_schema_keyword",
                expectedPath = "/responseFormat/schema/pattern",
            ),
            ContractSeedFixture(
                id = "v1-request-schema-external-reference",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-schema-external-reference.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "responseFormat": {
                        "kind": "json_schema",
                        "schema": {
                          "${'$'}ref": "https://example.com/schema"
                        }
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "unsupported_schema_reference",
                expectedPath = "/responseFormat/schema/${'$'}ref",
            ),
            ContractSeedFixture(
                id = "v1-request-schema-recursive-reference",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-schema-recursive-reference.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "responseFormat": {
                        "kind": "json_schema",
                        "schema": {
                          "${'$'}defs": {
                            "node": {
                              "${'$'}ref": "#/${'$'}defs/node"
                            }
                          },
                          "${'$'}ref": "#/${'$'}defs/node"
                        }
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "recursive_schema_not_supported",
                expectedPath = "/responseFormat/schema/${'$'}defs",
            ),
            ContractSeedFixture(
                id = "v1-provider-id-minimal",
                repositoryPath = "contracts/fixtures/v1/valid/provider-id-minimal.json",
                family = ContractSeedFamily.PROVIDER_ID,
                schemaId = PROVIDER_ID_SCHEMA_ID,
                json = "\"a\"",
            ),
            ContractSeedFixture(
                id = "v1-provider-id-future",
                repositoryPath =
                    "contracts/fixtures/v1/compatibility/provider-id-future.json",
                family = ContractSeedFamily.PROVIDER_ID,
                schemaId = PROVIDER_ID_SCHEMA_ID,
                json = "\"future-provider\"",
                compatibilityPurpose =
                    "Preserve a valid future provider registry key without requiring registry recognition.",
            ),
            ContractSeedFixture(
                id = "v1-provider-id-uppercase",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/provider-id-uppercase.json",
                family = ContractSeedFamily.PROVIDER_ID,
                schemaId = PROVIDER_ID_SCHEMA_ID,
                json = "\"OpenAI\"",
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_provider_id",
                expectedKeyword = "pattern",
                expectedPath = "",
            ),
            ContractSeedFixture(
                id = "v1-model-id-minimal",
                repositoryPath = "contracts/fixtures/v1/valid/model-id-minimal.json",
                family = ContractSeedFamily.MODEL_ID,
                schemaId = MODEL_ID_SCHEMA_ID,
                json = "\"m\"",
            ),
            ContractSeedFixture(
                id = "v1-model-id-future",
                repositoryPath =
                    "contracts/fixtures/v1/compatibility/model-id-future.json",
                family = ContractSeedFamily.MODEL_ID,
                schemaId = MODEL_ID_SCHEMA_ID,
                json = "\"future/model:v2\"",
                compatibilityPurpose =
                    "Preserve an opaque future provider-owned model identifier exactly.",
            ),
            ContractSeedFixture(
                id = "v1-model-id-whitespace",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/model-id-whitespace.json",
                family = ContractSeedFamily.MODEL_ID,
                schemaId = MODEL_ID_SCHEMA_ID,
                json = "\"model with spaces\"",
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_model_id",
                expectedPath = "/modelId",
            ),
            ContractSeedFixture(
                id = "v1-model-target-minimal",
                repositoryPath = "contracts/fixtures/v1/valid/model-target-minimal.json",
                family = ContractSeedFamily.MODEL_TARGET,
                schemaId = MODEL_TARGET_SCHEMA_ID,
                json =
                    """
                    {
                      "providerId": "openai",
                      "modelId": "gpt-4.1-mini"
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-model-target-future-member",
                repositoryPath =
                    "contracts/fixtures/v1/compatibility/model-target-future-member.json",
                family = ContractSeedFamily.MODEL_TARGET,
                schemaId = MODEL_TARGET_SCHEMA_ID,
                json =
                    """
                    {
                      "providerId": "future-provider",
                      "modelId": "future/model:v2",
                      "futureTargetMember": {
                        "mode": "future"
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Ignore an additive ordinary target member while preserving provider and model identifiers.",
            ),
            ContractSeedFixture(
                id = "v1-model-target-missing-model-id",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/model-target-missing-model-id.json",
                family = ContractSeedFamily.MODEL_TARGET,
                schemaId = MODEL_TARGET_SCHEMA_ID,
                json =
                    """
                    {
                      "providerId": "openai"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "missing_model_id",
                expectedKeyword = "required",
                expectedPath = "",
            ),
            ContractSeedFixture(
                id = "v1-text-input-minimal",
                repositoryPath = "contracts/fixtures/v1/valid/text-input-minimal.json",
                family = ContractSeedFamily.TEXT_INPUT,
                schemaId = TEXT_INPUT_SCHEMA_ID,
                json =
                    """
                    {
                      "role": "user",
                      "content": "Hello"
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-text-input-future-role",
                repositoryPath =
                    "contracts/fixtures/v1/compatibility/text-input-future-role.json",
                family = ContractSeedFamily.TEXT_INPUT,
                schemaId = TEXT_INPUT_SCHEMA_ID,
                json =
                    """
                    {
                      "role": "critic_v2",
                      "content": "Preserve this role.",
                      "futureInputMember": {
                        "mode": "future"
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve an unknown valid input role while ignoring an additive ordinary member.",
            ),
            ContractSeedFixture(
                id = "v1-text-input-blank",
                repositoryPath = "contracts/fixtures/v1/invalid/text-input-blank.json",
                family = ContractSeedFamily.TEXT_INPUT,
                schemaId = TEXT_INPUT_SCHEMA_ID,
                json =
                    """
                    {
                      "role": "user",
                      "content": "   "
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "blank_input_content",
                expectedPath = "/content",
            ),
            ContractSeedFixture(
                id = "v1-response-format-minimal",
                repositoryPath =
                    "contracts/fixtures/v1/valid/response-format-minimal.json",
                family = ContractSeedFamily.RESPONSE_FORMAT,
                schemaId = RESPONSE_FORMAT_SCHEMA_ID,
                json =
                    """
                    {
                      "kind": "plain_text"
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-response-format-complete",
                repositoryPath =
                    "contracts/fixtures/v1/valid/response-format-complete.json",
                family = ContractSeedFamily.RESPONSE_FORMAT,
                schemaId = RESPONSE_FORMAT_SCHEMA_ID,
                json =
                    """
                    {
                      "kind": "json_schema",
                      "schema": {
                        "type": "object",
                        "additionalProperties": false
                      }
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-response-format-future-kind",
                repositoryPath =
                    "contracts/fixtures/v1/compatibility/response-format-future-kind.json",
                family = ContractSeedFamily.RESPONSE_FORMAT,
                schemaId = RESPONSE_FORMAT_SCHEMA_ID,
                json =
                    """
                    {
                      "kind": "future_binary",
                      "futureFormatMember": {
                        "encoding": "future"
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve an unknown valid response-format kind while ignoring its additive ordinary member.",
            ),
            ContractSeedFixture(
                id = "v1-response-format-plain-text-with-schema",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/response-format-plain-text-with-schema.json",
                family = ContractSeedFamily.RESPONSE_FORMAT,
                schemaId = RESPONSE_FORMAT_SCHEMA_ID,
                json =
                    """
                    {
                      "kind": "plain_text",
                      "schema": {
                        "type": "string"
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "unexpected_response_schema",
                expectedPath = "/schema",
            ),
            ContractSeedFixture(
                id = "v1-response-format-future-kind-with-schema",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/response-format-future-kind-with-schema.json",
                family = ContractSeedFamily.RESPONSE_FORMAT,
                schemaId = RESPONSE_FORMAT_SCHEMA_ID,
                json =
                    """
                    {
                      "kind": "future_binary",
                      "schema": {
                        "type": "string"
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "unexpected_response_schema",
                expectedPath = "/schema",
            ),
            ContractSeedFixture(
                id = "v1-generation-parameters-minimal",
                repositoryPath =
                    "contracts/fixtures/v1/valid/generation-parameters-minimal.json",
                family = ContractSeedFamily.GENERATION_PARAMETERS,
                schemaId = GENERATION_PARAMETERS_SCHEMA_ID,
                json = "{}",
            ),
            ContractSeedFixture(
                id = "v1-generation-parameters-complete",
                repositoryPath =
                    "contracts/fixtures/v1/valid/generation-parameters-complete.json",
                family = ContractSeedFamily.GENERATION_PARAMETERS,
                schemaId = GENERATION_PARAMETERS_SCHEMA_ID,
                json =
                    """
                    {
                      "maxOutputTokens": 512,
                      "temperature": 0.2,
                      "topP": 0.95,
                      "stopSequences": [
                        "\nEND"
                      ]
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-generation-parameters-future-member",
                repositoryPath =
                    "contracts/fixtures/v1/compatibility/generation-parameters-future-member.json",
                family = ContractSeedFamily.GENERATION_PARAMETERS,
                schemaId = GENERATION_PARAMETERS_SCHEMA_ID,
                json =
                    """
                    {
                      "futureGenerationMember": 42
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Ignore an additive ordinary generation member and retain empty canonical generation intent.",
            ),
            ContractSeedFixture(
                id = "v1-generation-parameters-duplicate-stop-sequence",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/generation-parameters-duplicate-stop-sequence.json",
                family = ContractSeedFamily.GENERATION_PARAMETERS,
                schemaId = GENERATION_PARAMETERS_SCHEMA_ID,
                json =
                    """
                    {
                      "stopSequences": [
                        "END",
                        "END"
                      ]
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "duplicate_stop_sequence",
                expectedPath = "/stopSequences/1",
            ),
            ContractSeedFixture(
                id = "v1-request-integral-exponent-token",
                repositoryPath =
                    "contracts/fixtures/v1/valid/request-integral-exponent-token.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "generation": {
                        "maxOutputTokens": 1e0
                      }
                    }
                    """.trimIndent(),
            ),
            ContractSeedFixture(
                id = "v1-request-top-p-underflow",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-top-p-underflow.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "generation": {
                        "topP": 1e-400
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "generation_number_not_round_trippable",
                expectedPath = "/generation/topP",
            ),
            ContractSeedFixture(
                id = "v1-request-temperature-high-precision",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-temperature-high-precision.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "generation": {
                        "temperature": 0.123456789012345678901234567890123456789
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "generation_number_not_round_trippable",
                expectedPath = "/generation/temperature",
            ),
            ContractSeedFixture(
                id = "v1-request-temperature-just-over-one",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-temperature-just-over-one.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "generation": {
                        "temperature": 1.000000000000000000000000000000000000001
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "temperature_out_of_range",
                expectedKeyword = "maximum",
                expectedPath = "/generation/temperature",
            ),
            ContractSeedFixture(
                id = "v1-request-max-output-tokens-huge-negative",
                repositoryPath =
                    "contracts/fixtures/v1/invalid/request-max-output-tokens-huge-negative.json",
                family = ContractSeedFamily.REQUEST,
                schemaId = REQUEST_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "input": [
                        {
                          "role": "user",
                          "content": "Hello"
                        }
                      ],
                      "generation": {
                        "maxOutputTokens": -1e10
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "max_output_tokens_out_of_range",
                expectedKeyword = "minimum",
                expectedPath = "/generation/maxOutputTokens",
            ),
        ) + P2GContractSeedFixtures.all + P2HContractSeedFixtures.all
}
