package com.maneesh.universalai.connector.contract.testing

/** P2-G response-side fixtures kept separate from the earlier seed for reviewability. */
internal object P2GContractSeedFixtures {
    val all: List<ContractSeedFixture> =
        listOf(
            fixture(
                id = "v1-operation-id-minimal",
                relativePath = "valid/operation-id-minimal.json",
                family = ContractSeedFamily.OPERATION_ID,
                schemaId = ContractSeedFixtures.OPERATION_ID_SCHEMA_ID,
                json = """"a"""",
            ),
            fixture(
                id = "v1-operation-id-unicode",
                relativePath = "compatibility/operation-id-unicode.json",
                family = ContractSeedFamily.OPERATION_ID,
                schemaId = ContractSeedFixtures.OPERATION_ID_SCHEMA_ID,
                json = """"req_日本-v2"""",
                compatibilityPurpose =
                    "Preserve an opaque valid Unicode operation identifier exactly across every " +
                        "operation-ID type.",
            ),
            fixture(
                id = "v1-operation-id-empty",
                relativePath = "invalid/operation-id-empty.json",
                family = ContractSeedFamily.OPERATION_ID,
                schemaId = ContractSeedFixtures.OPERATION_ID_SCHEMA_ID,
                json = "\"\"",
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_operation_id",
                expectedKeyword = "minLength",
                expectedPath = "",
            ),
            fixture(
                id = "v1-request-id-whitespace",
                relativePath = "invalid/request-id-whitespace.json",
                family = ContractSeedFamily.OPERATION_ID,
                schemaId = ContractSeedFixtures.OPERATION_ID_SCHEMA_ID,
                json = """"request 1"""",
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_request_id",
                expectedPath = "/requestId",
            ),
            fixture(
                id = "v1-response-id-whitespace",
                relativePath = "invalid/response-id-whitespace.json",
                family = ContractSeedFamily.OPERATION_ID,
                schemaId = ContractSeedFixtures.OPERATION_ID_SCHEMA_ID,
                json = """"response\t1"""",
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_response_id",
                expectedPath = "/id",
            ),
            fixture(
                id = "v1-output-id-control",
                relativePath = "invalid/output-id-control.json",
                family = ContractSeedFamily.OPERATION_ID,
                schemaId = ContractSeedFixtures.OPERATION_ID_SCHEMA_ID,
                json = """"output\u007f1"""",
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_output_id",
                expectedPath = "/id",
            ),
            fixture(
                id = "v1-request-id-utf8-too-long",
                relativePath = "invalid/request-id-utf8-too-long.json",
                family = ContractSeedFamily.OPERATION_ID,
                schemaId = ContractSeedFixtures.OPERATION_ID_SCHEMA_ID,
                json =
                    """"😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀"""",
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_request_id",
                expectedPath = "/requestId",
            ),
            fixture(
                id = "v1-output-minimal",
                relativePath = "valid/output-minimal.json",
                family = ContractSeedFamily.OUTPUT,
                schemaId = ContractSeedFixtures.OUTPUT_SCHEMA_ID,
                json =
                    """
                    {
                      "id": "out_0",
                      "index": 0,
                      "kind": "text",
                      "text": "Hello"
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-output-complete",
                relativePath = "valid/output-complete.json",
                family = ContractSeedFamily.OUTPUT,
                schemaId = ContractSeedFixtures.OUTPUT_SCHEMA_ID,
                json =
                    """
                    {
                      "id": "out_1",
                      "index": 1,
                      "kind": "structured_json",
                      "json": {
                        "answer": "Hello",
                        "confidence": 0.875
                      },
                      "extensions": {
                        "com.example.connector": {
                          "trace": "output-complete",
                          "nested": {
                            "preserved": true
                          }
                        }
                      }
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-output-future-kind",
                relativePath = "compatibility/output-future-kind.json",
                family = ContractSeedFamily.OUTPUT,
                schemaId = ContractSeedFixtures.OUTPUT_SCHEMA_ID,
                json =
                    """
                    {
                      "id": "out_future",
                      "index": 2,
                      "kind": "future_audio",
                      "extensions": {
                        "com.example.future": {
                          "codec": "future-codec",
                          "payload": [
                            1,
                            null,
                            true
                          ]
                        }
                      },
                      "futureOutputMember": {
                        "ignored": true
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve an unknown output kind and governed extensions while ignoring an " +
                        "additive ordinary member.",
            ),
            fixture(
                id = "v1-output-negative-index",
                relativePath = "invalid/output-negative-index.json",
                family = ContractSeedFamily.OUTPUT,
                schemaId = ContractSeedFixtures.OUTPUT_SCHEMA_ID,
                json =
                    """
                    {
                      "id": "out_0",
                      "index": -1,
                      "kind": "text",
                      "text": "Hello"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "output_index_out_of_range",
                expectedKeyword = "minimum",
                expectedPath = "/index",
            ),
            fixture(
                id = "v1-output-text-with-json",
                relativePath = "invalid/output-text-with-json.json",
                family = ContractSeedFamily.OUTPUT,
                schemaId = ContractSeedFixtures.OUTPUT_SCHEMA_ID,
                json =
                    """
                    {
                      "id": "out_0",
                      "index": 0,
                      "kind": "text",
                      "text": "Hello",
                      "json": {
                        "answer": "unexpected"
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "unexpected_output_json",
                expectedPath = "/json",
            ),
            fixture(
                id = "v1-output-structured-missing-json",
                relativePath = "invalid/output-structured-missing-json.json",
                family = ContractSeedFamily.OUTPUT,
                schemaId = ContractSeedFixtures.OUTPUT_SCHEMA_ID,
                json =
                    """
                    {
                      "id": "out_0",
                      "index": 0,
                      "kind": "structured_json"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "missing_structured_output_json",
                expectedPath = "/json",
            ),
            fixture(
                id = "v1-usage-minimal",
                relativePath = "valid/usage-minimal.json",
                family = ContractSeedFamily.USAGE,
                schemaId = ContractSeedFixtures.USAGE_SCHEMA_ID,
                json =
                    """
                    {
                      "inputTokens": 0,
                      "outputTokens": 0,
                      "totalTokens": 0
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-usage-complete",
                relativePath = "valid/usage-complete.json",
                family = ContractSeedFamily.USAGE,
                schemaId = ContractSeedFixtures.USAGE_SCHEMA_ID,
                json =
                    """
                    {
                      "inputTokens": 10,
                      "outputTokens": 5,
                      "totalTokens": 15,
                      "inputDetails": {
                        "cached": 4,
                        "uncached": 6
                      },
                      "outputDetails": {
                        "reasoning": 2,
                        "visible": 3
                      },
                      "extensions": {
                        "com.example.connector": {
                          "measured": true
                        }
                      }
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-usage-integral-number-forms",
                relativePath = "valid/usage-integral-number-forms.json",
                family = ContractSeedFamily.USAGE,
                schemaId = ContractSeedFixtures.USAGE_SCHEMA_ID,
                json =
                    """
                    {
                      "inputTokens": 1e0,
                      "outputTokens": 2.0,
                      "totalTokens": 3e0,
                      "inputDetails": {
                        "cached": 1.0
                      },
                      "outputDetails": {
                        "visible": 2e0
                      }
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-usage-future-breakdowns",
                relativePath = "compatibility/usage-future-breakdowns.json",
                family = ContractSeedFamily.USAGE,
                schemaId = ContractSeedFixtures.USAGE_SCHEMA_ID,
                json =
                    """
                    {
                      "inputTokens": 3,
                      "outputTokens": 2,
                      "totalTokens": 5,
                      "inputDetails": {
                        "future_cached_v2": 3
                      },
                      "outputDetails": {
                        "future_reasoning_v2": 2
                      },
                      "futureUsageMember": "ignored"
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve future raw usage-breakdown names while ignoring an additive ordinary " +
                        "member.",
            ),
            fixture(
                id = "v1-usage-negative-input",
                relativePath = "invalid/usage-negative-input.json",
                family = ContractSeedFamily.USAGE,
                schemaId = ContractSeedFixtures.USAGE_SCHEMA_ID,
                json =
                    """
                    {
                      "inputTokens": -1,
                      "outputTokens": 1,
                      "totalTokens": 0
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "usage_token_count_out_of_range",
                expectedKeyword = "minimum",
                expectedPath = "/inputTokens",
            ),
            fixture(
                id = "v1-usage-total-mismatch",
                relativePath = "invalid/usage-total-mismatch.json",
                family = ContractSeedFamily.USAGE,
                schemaId = ContractSeedFixtures.USAGE_SCHEMA_ID,
                json =
                    """
                    {
                      "inputTokens": 2,
                      "outputTokens": 3,
                      "totalTokens": 4
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "usage_total_mismatch",
                expectedPath = "/totalTokens",
            ),
            fixture(
                id = "v1-usage-input-details-exceed-aggregate",
                relativePath = "invalid/usage-input-details-exceed-aggregate.json",
                family = ContractSeedFamily.USAGE,
                schemaId = ContractSeedFixtures.USAGE_SCHEMA_ID,
                json =
                    """
                    {
                      "inputTokens": 2,
                      "outputTokens": 1,
                      "totalTokens": 3,
                      "inputDetails": {
                        "cached": 2,
                        "uncached": 1
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "usage_details_exceed_aggregate",
                expectedPath = "/inputDetails",
            ),
            fixture(
                id = "v1-error-minimal",
                relativePath = "valid/error-minimal.json",
                family = ContractSeedFamily.ERROR,
                schemaId = ContractSeedFixtures.ERROR_SCHEMA_ID,
                json =
                    """
                    {
                      "category": "internal",
                      "code": "connector_failure",
                      "message": "The connector failed."
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-error-complete",
                relativePath = "valid/error-complete.json",
                family = ContractSeedFamily.ERROR,
                schemaId = ContractSeedFixtures.ERROR_SCHEMA_ID,
                json =
                    """
                    {
                      "category": "provider",
                      "code": "simulated_failure",
                      "message": "The deterministic provider reported a safe failure.",
                      "metadata": {
                        "requestPath": "/input/0/content",
                        "retryable": false,
                        "attempt": 1,
                        "nested": {
                          "preserved": null
                        }
                      },
                      "extensions": {
                        "com.example.connector": {
                          "trace": "safe-trace"
                        }
                      }
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-error-future-values",
                relativePath = "compatibility/error-future-values.json",
                family = ContractSeedFamily.ERROR,
                schemaId = ContractSeedFixtures.ERROR_SCHEMA_ID,
                json =
                    """
                    {
                      "category": "future_provider",
                      "code": "future_condition_v2",
                      "message": "A future error remains distinguishable.",
                      "futureErrorMember": {
                        "ignored": true
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve unknown error category and code values while ignoring an additive " +
                        "ordinary member.",
            ),
            fixture(
                id = "v1-error-uppercase-category",
                relativePath = "invalid/error-uppercase-category.json",
                family = ContractSeedFamily.ERROR,
                schemaId = ContractSeedFixtures.ERROR_SCHEMA_ID,
                json =
                    """
                    {
                      "category": "Provider",
                      "code": "simulated_failure",
                      "message": "Invalid category spelling."
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_error_category",
                expectedKeyword = "pattern",
                expectedPath = "/category",
            ),
            fixture(
                id = "v1-error-blank-message",
                relativePath = "invalid/error-blank-message.json",
                family = ContractSeedFamily.ERROR,
                schemaId = ContractSeedFixtures.ERROR_SCHEMA_ID,
                json =
                    """
                    {
                      "category": "provider",
                      "code": "simulated_failure",
                      "message": "   "
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_error_message",
                expectedPath = "/message",
            ),
            fixture(
                id = "v1-error-message-line-separator",
                relativePath = "invalid/error-message-line-separator.json",
                family = ContractSeedFamily.ERROR,
                schemaId = ContractSeedFixtures.ERROR_SCHEMA_ID,
                json =
                    """
                    {
                      "category": "provider",
                      "code": "unsafe_message",
                      "message": "Safe prefix\u2028injected line."
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_error_message",
                expectedPath = "/message",
            ),
            fixture(
                id = "v1-response-minimal",
                relativePath = "valid/response-minimal.json",
                family = ContractSeedFamily.RESPONSE,
                schemaId = ContractSeedFixtures.RESPONSE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "id": "resp_0",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "outputs": [],
                      "completionReason": "stop"
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-response-complete",
                relativePath = "valid/response-complete.json",
                family = ContractSeedFamily.RESPONSE,
                schemaId = ContractSeedFixtures.RESPONSE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "id": "resp_complete",
                      "requestId": "req_complete",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "outputs": [
                        {
                          "id": "out_0",
                          "index": 0,
                          "kind": "text",
                          "text": "Hello"
                        },
                        {
                          "id": "out_1",
                          "index": 1,
                          "kind": "structured_json",
                          "json": {
                            "answer": "Hello"
                          }
                        }
                      ],
                      "usage": {
                        "inputTokens": 4,
                        "outputTokens": 2,
                        "totalTokens": 6
                      },
                      "completionReason": "stop",
                      "extensions": {
                        "com.example.connector": {
                          "trace": "response-complete"
                        }
                      }
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-response-future-values",
                relativePath = "compatibility/response-future-values.json",
                family = ContractSeedFamily.RESPONSE,
                schemaId = ContractSeedFixtures.RESPONSE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "id": "resp_future",
                      "target": {
                        "providerId": "future-provider",
                        "modelId": "future/model:v2",
                        "futureTargetMember": true
                      },
                      "outputs": [
                        {
                          "id": "out_future",
                          "index": 0,
                          "kind": "future_audio",
                          "extensions": {
                            "com.example.future": {
                              "payload": "preserved"
                            }
                          },
                          "futureOutputMember": true
                        }
                      ],
                      "completionReason": "future_pause",
                      "futureResponseMember": {
                        "ignored": true
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve future completion and output-kind values while ignoring additive " +
                        "ordinary members.",
            ),
            fixture(
                id = "v1-response-missing-version",
                relativePath = "invalid/response-missing-version.json",
                family = ContractSeedFamily.RESPONSE,
                schemaId = ContractSeedFixtures.RESPONSE_SCHEMA_ID,
                json =
                    """
                    {
                      "id": "resp_0",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "outputs": [],
                      "completionReason": "stop"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "missing_contract_version",
                expectedKeyword = "required",
                expectedPath = "",
            ),
            fixture(
                id = "v1-response-null-version",
                relativePath = "invalid/response-null-version.json",
                family = ContractSeedFamily.RESPONSE,
                schemaId = ContractSeedFixtures.RESPONSE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": null,
                      "id": "resp_0",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "outputs": [],
                      "completionReason": "stop"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_contract_version",
                expectedKeyword = "const",
                expectedPath = "/contractVersion",
            ),
            fixture(
                id = "v1-response-future-version",
                relativePath = "invalid/response-future-version.json",
                family = ContractSeedFamily.RESPONSE,
                schemaId = ContractSeedFixtures.RESPONSE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "2",
                      "id": "resp_0",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "outputs": [],
                      "completionReason": "stop"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "unsupported_contract_version",
                expectedKeyword = "const",
                expectedPath = "/contractVersion",
            ),
            fixture(
                id = "v1-response-missing-outputs",
                relativePath = "invalid/response-missing-outputs.json",
                family = ContractSeedFamily.RESPONSE,
                schemaId = ContractSeedFixtures.RESPONSE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "id": "resp_0",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "completionReason": "stop"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "missing_outputs",
                expectedKeyword = "required",
                expectedPath = "",
            ),
            fixture(
                id = "v1-response-output-index-mismatch",
                relativePath = "invalid/response-output-index-mismatch.json",
                family = ContractSeedFamily.RESPONSE,
                schemaId = ContractSeedFixtures.RESPONSE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "id": "resp_0",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "outputs": [
                        {
                          "id": "out_0",
                          "index": 1,
                          "kind": "text",
                          "text": "Hello"
                        }
                      ],
                      "completionReason": "stop"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "output_index_mismatch",
                expectedPath = "/outputs/0/index",
            ),
            fixture(
                id = "v1-response-duplicate-output-id",
                relativePath = "invalid/response-duplicate-output-id.json",
                family = ContractSeedFamily.RESPONSE,
                schemaId = ContractSeedFixtures.RESPONSE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "id": "resp_0",
                      "target": {
                        "providerId": "openai",
                        "modelId": "gpt-4.1-mini"
                      },
                      "outputs": [
                        {
                          "id": "out_duplicate",
                          "index": 0,
                          "kind": "text",
                          "text": "First"
                        },
                        {
                          "id": "out_duplicate",
                          "index": 1,
                          "kind": "text",
                          "text": "Second"
                        }
                      ],
                      "completionReason": "stop"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "duplicate_output_id",
                expectedPath = "/outputs/1/id",
            ),
            fixture(
                id = "v1-stream-event-minimal",
                relativePath = "valid/stream-event-minimal.json",
                family = ContractSeedFamily.STREAM_EVENT,
                schemaId = ContractSeedFixtures.STREAM_EVENT_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "type": "response.started",
                      "terminal": false,
                      "sequence": 1,
                      "responseId": "resp_0"
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-stream-event-complete-delta",
                relativePath = "valid/stream-event-complete-delta.json",
                family = ContractSeedFamily.STREAM_EVENT,
                schemaId = ContractSeedFixtures.STREAM_EVENT_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "type": "output.delta",
                      "terminal": false,
                      "sequence": 3,
                      "requestId": "req_0",
                      "responseId": "resp_0",
                      "outputId": "out_0",
                      "outputIndex": 0,
                      "delta": "Hello",
                      "extensions": {
                        "com.example.connector": {
                          "trace": "delta-event"
                        }
                      }
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-stream-event-future-type",
                relativePath = "compatibility/stream-event-future-type.json",
                family = ContractSeedFamily.STREAM_EVENT,
                schemaId = ContractSeedFixtures.STREAM_EVENT_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "type": "future.trace",
                      "terminal": false,
                      "sequence": 2,
                      "responseId": "resp_future",
                      "extensions": {
                        "com.example.future": {
                          "payload": {
                            "preserved": true
                          }
                        }
                      },
                      "futureEventMember": "ignored"
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve an unknown nonterminal event type and governed extensions while " +
                        "ignoring an additive ordinary member.",
            ),
            fixture(
                id = "v1-stream-event-missing-version",
                relativePath = "invalid/stream-event-missing-version.json",
                family = ContractSeedFamily.STREAM_EVENT,
                schemaId = ContractSeedFixtures.STREAM_EVENT_SCHEMA_ID,
                json =
                    """
                    {
                      "type": "response.started",
                      "terminal": false,
                      "sequence": 1,
                      "responseId": "resp_0"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "missing_contract_version",
                expectedKeyword = "required",
                expectedPath = "",
            ),
            fixture(
                id = "v1-stream-event-null-version",
                relativePath = "invalid/stream-event-null-version.json",
                family = ContractSeedFamily.STREAM_EVENT,
                schemaId = ContractSeedFixtures.STREAM_EVENT_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": null,
                      "type": "response.started",
                      "terminal": false,
                      "sequence": 1,
                      "responseId": "resp_0"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_contract_version",
                expectedKeyword = "const",
                expectedPath = "/contractVersion",
            ),
            fixture(
                id = "v1-stream-event-future-version",
                relativePath = "invalid/stream-event-future-version.json",
                family = ContractSeedFamily.STREAM_EVENT,
                schemaId = ContractSeedFixtures.STREAM_EVENT_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "2",
                      "type": "response.started",
                      "terminal": false,
                      "sequence": 1,
                      "responseId": "resp_0"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "unsupported_contract_version",
                expectedKeyword = "const",
                expectedPath = "/contractVersion",
            ),
            fixture(
                id = "v1-stream-event-null-terminal",
                relativePath = "invalid/stream-event-null-terminal.json",
                family = ContractSeedFamily.STREAM_EVENT,
                schemaId = ContractSeedFixtures.STREAM_EVENT_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "type": "response.started",
                      "terminal": null,
                      "sequence": 1,
                      "responseId": "resp_0"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_stream_terminal",
                expectedKeyword = "type",
                expectedPath = "/terminal",
            ),
            fixture(
                id = "v1-stream-event-unknown-terminal",
                relativePath = "invalid/stream-event-unknown-terminal.json",
                family = ContractSeedFamily.STREAM_EVENT,
                schemaId = ContractSeedFixtures.STREAM_EVENT_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "type": "future.terminal",
                      "terminal": true,
                      "sequence": 1,
                      "responseId": "resp_0"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "unsupported_terminal_event",
                expectedPath = "/terminal",
            ),
            fixture(
                id = "v1-stream-event-delta-missing-output-scope",
                relativePath = "invalid/stream-event-delta-missing-output-scope.json",
                family = ContractSeedFamily.STREAM_EVENT,
                schemaId = ContractSeedFixtures.STREAM_EVENT_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "type": "output.delta",
                      "terminal": false,
                      "sequence": 1,
                      "responseId": "resp_0",
                      "delta": "Hello"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_stream_sequence",
                expectedPath = "/outputId",
            ),
            fixture(
                id = "v1-stream-event-completed-not-terminal",
                relativePath = "invalid/stream-event-completed-not-terminal.json",
                family = ContractSeedFamily.STREAM_EVENT,
                schemaId = ContractSeedFixtures.STREAM_EVENT_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "type": "response.completed",
                      "terminal": false,
                      "sequence": 1,
                      "responseId": "resp_0",
                      "response": {
                        "contractVersion": "1",
                        "id": "resp_0",
                        "target": {
                          "providerId": "openai",
                          "modelId": "gpt-4.1-mini"
                        },
                        "outputs": [],
                        "completionReason": "stop"
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_stream_sequence",
                expectedPath = "/terminal",
            ),
            fixture(
                id = "v1-stream-sequence-minimal",
                relativePath = "valid/stream-sequence-minimal.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "responseId": "resp_minimal"
                      },
                      {
                        "contractVersion": "1",
                        "type": "response.completed",
                        "terminal": true,
                        "sequence": 2,
                        "responseId": "resp_minimal",
                        "response": {
                          "contractVersion": "1",
                          "id": "resp_minimal",
                          "target": {
                            "providerId": "openai",
                            "modelId": "gpt-4.1-mini"
                          },
                          "outputs": [],
                          "completionReason": "stop"
                        }
                      }
                    ]
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-stream-sequence-complete",
                relativePath = "valid/stream-sequence-complete.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "requestId": "req_complete",
                        "responseId": "resp_complete"
                      },
                      {
                        "contractVersion": "1",
                        "type": "output.started",
                        "terminal": false,
                        "sequence": 2,
                        "requestId": "req_complete",
                        "responseId": "resp_complete",
                        "outputId": "out_0",
                        "outputIndex": 0
                      },
                      {
                        "contractVersion": "1",
                        "type": "output.delta",
                        "terminal": false,
                        "sequence": 3,
                        "requestId": "req_complete",
                        "responseId": "resp_complete",
                        "outputId": "out_0",
                        "outputIndex": 0,
                        "delta": "Hel"
                      },
                      {
                        "contractVersion": "1",
                        "type": "output.delta",
                        "terminal": false,
                        "sequence": 4,
                        "requestId": "req_complete",
                        "responseId": "resp_complete",
                        "outputId": "out_0",
                        "outputIndex": 0,
                        "delta": "lo"
                      },
                      {
                        "contractVersion": "1",
                        "type": "output.completed",
                        "terminal": false,
                        "sequence": 5,
                        "requestId": "req_complete",
                        "responseId": "resp_complete",
                        "outputId": "out_0",
                        "outputIndex": 0,
                        "output": {
                          "id": "out_0",
                          "index": 0,
                          "kind": "text",
                          "text": "Hello"
                        }
                      },
                      {
                        "contractVersion": "1",
                        "type": "usage.updated",
                        "terminal": false,
                        "sequence": 6,
                        "requestId": "req_complete",
                        "responseId": "resp_complete",
                        "usage": {
                          "inputTokens": 4,
                          "outputTokens": 1,
                          "totalTokens": 5
                        }
                      },
                      {
                        "contractVersion": "1",
                        "type": "response.completed",
                        "terminal": true,
                        "sequence": 7,
                        "requestId": "req_complete",
                        "responseId": "resp_complete",
                        "response": {
                          "contractVersion": "1",
                          "id": "resp_complete",
                          "requestId": "req_complete",
                          "target": {
                            "providerId": "openai",
                            "modelId": "gpt-4.1-mini"
                          },
                          "outputs": [
                            {
                              "id": "out_0",
                              "index": 0,
                              "kind": "text",
                              "text": "Hello"
                            }
                          ],
                          "usage": {
                            "inputTokens": 4,
                            "outputTokens": 2,
                            "totalTokens": 6
                          },
                          "completionReason": "stop"
                        }
                      }
                    ]
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-stream-sequence-future-event",
                relativePath = "compatibility/stream-sequence-future-event.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "responseId": "resp_future"
                      },
                      {
                        "contractVersion": "1",
                        "type": "future.trace",
                        "terminal": false,
                        "sequence": 2,
                        "responseId": "resp_future",
                        "extensions": {
                          "com.example.future": {
                            "payload": "preserved"
                          }
                        },
                        "futureEventMember": true
                      },
                      {
                        "contractVersion": "1",
                        "type": "response.completed",
                        "terminal": true,
                        "sequence": 3,
                        "responseId": "resp_future",
                        "response": {
                          "contractVersion": "1",
                          "id": "resp_future",
                          "target": {
                            "providerId": "future-provider",
                            "modelId": "future/model:v2"
                          },
                          "outputs": [],
                          "completionReason": "future_pause"
                        }
                      }
                    ]
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve and order an unknown nonterminal event while dropping its additive " +
                        "ordinary member.",
            ),
            fixture(
                id = "v1-stream-sequence-empty",
                relativePath = "invalid/stream-sequence-empty.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json = "[]",
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "empty_stream_sequence",
                expectedKeyword = "minItems",
                expectedPath = "",
            ),
            fixture(
                id = "v1-stream-sequence-incomplete",
                relativePath = "invalid/stream-sequence-incomplete.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "responseId": "resp_0"
                      }
                    ]
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "incomplete_stream",
                expectedPath = "/terminal",
            ),
            fixture(
                id = "v1-stream-sequence-gap",
                relativePath = "invalid/stream-sequence-gap.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "responseId": "resp_0"
                      },
                      {
                        "contractVersion": "1",
                        "type": "response.completed",
                        "terminal": true,
                        "sequence": 3,
                        "responseId": "resp_0",
                        "response": {
                          "contractVersion": "1",
                          "id": "resp_0",
                          "target": {
                            "providerId": "openai",
                            "modelId": "gpt-4.1-mini"
                          },
                          "outputs": [],
                          "completionReason": "stop"
                        }
                      }
                    ]
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_stream_sequence",
                expectedPath = "/1/sequence",
            ),
            fixture(
                id = "v1-stream-sequence-response-correlation-change",
                relativePath = "invalid/stream-sequence-response-correlation-change.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "responseId": "resp_0"
                      },
                      {
                        "contractVersion": "1",
                        "type": "future.trace",
                        "terminal": false,
                        "sequence": 2,
                        "responseId": "resp_other"
                      }
                    ]
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_stream_sequence",
                expectedPath = "/1/responseId",
            ),
            fixture(
                id = "v1-stream-sequence-output-correlation-change",
                relativePath = "invalid/stream-sequence-output-correlation-change.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "responseId": "resp_0"
                      },
                      {
                        "contractVersion": "1",
                        "type": "output.started",
                        "terminal": false,
                        "sequence": 2,
                        "responseId": "resp_0",
                        "outputId": "out_0",
                        "outputIndex": 0
                      },
                      {
                        "contractVersion": "1",
                        "type": "output.delta",
                        "terminal": false,
                        "sequence": 3,
                        "responseId": "resp_0",
                        "outputId": "out_0",
                        "outputIndex": 1,
                        "delta": "Hello"
                      }
                    ]
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_stream_sequence",
                expectedPath = "/2/outputIndex",
            ),
            fixture(
                id = "v1-stream-sequence-duplicate-terminal",
                relativePath = "invalid/stream-sequence-duplicate-terminal.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "responseId": "resp_0"
                      },
                      {
                        "contractVersion": "1",
                        "type": "response.completed",
                        "terminal": true,
                        "sequence": 2,
                        "responseId": "resp_0",
                        "response": {
                          "contractVersion": "1",
                          "id": "resp_0",
                          "target": {
                            "providerId": "openai",
                            "modelId": "gpt-4.1-mini"
                          },
                          "outputs": [],
                          "completionReason": "stop"
                        }
                      },
                      {
                        "contractVersion": "1",
                        "type": "response.completed",
                        "terminal": true,
                        "sequence": 3,
                        "responseId": "resp_0",
                        "response": {
                          "contractVersion": "1",
                          "id": "resp_0",
                          "target": {
                            "providerId": "openai",
                            "modelId": "gpt-4.1-mini"
                          },
                          "outputs": [],
                          "completionReason": "stop"
                        }
                      }
                    ]
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_stream_sequence",
                expectedPath = "/2/terminal",
            ),
            fixture(
                id = "v1-stream-sequence-late-event",
                relativePath = "invalid/stream-sequence-late-event.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "responseId": "resp_0"
                      },
                      {
                        "contractVersion": "1",
                        "type": "response.completed",
                        "terminal": true,
                        "sequence": 2,
                        "responseId": "resp_0",
                        "response": {
                          "contractVersion": "1",
                          "id": "resp_0",
                          "target": {
                            "providerId": "openai",
                            "modelId": "gpt-4.1-mini"
                          },
                          "outputs": [],
                          "completionReason": "stop"
                        }
                      },
                      {
                        "contractVersion": "1",
                        "type": "future.trace",
                        "terminal": false,
                        "sequence": 3,
                        "responseId": "resp_0"
                      }
                    ]
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_stream_sequence",
                expectedPath = "/2/terminal",
            ),
            fixture(
                id = "v1-stream-sequence-delta-final-mismatch",
                relativePath = "invalid/stream-sequence-delta-final-mismatch.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "responseId": "resp_0"
                      },
                      {
                        "contractVersion": "1",
                        "type": "output.started",
                        "terminal": false,
                        "sequence": 2,
                        "responseId": "resp_0",
                        "outputId": "out_0",
                        "outputIndex": 0
                      },
                      {
                        "contractVersion": "1",
                        "type": "output.delta",
                        "terminal": false,
                        "sequence": 3,
                        "responseId": "resp_0",
                        "outputId": "out_0",
                        "outputIndex": 0,
                        "delta": "Hello"
                      },
                      {
                        "contractVersion": "1",
                        "type": "output.completed",
                        "terminal": false,
                        "sequence": 4,
                        "responseId": "resp_0",
                        "outputId": "out_0",
                        "outputIndex": 0,
                        "output": {
                          "id": "out_0",
                          "index": 0,
                          "kind": "text",
                          "text": "Goodbye"
                        }
                      }
                    ]
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_stream_sequence",
                expectedPath = "/3/output",
            ),
            fixture(
                id = "v1-stream-sequence-usage-regression",
                relativePath = "invalid/stream-sequence-usage-regression.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "responseId": "resp_0"
                      },
                      {
                        "contractVersion": "1",
                        "type": "usage.updated",
                        "terminal": false,
                        "sequence": 2,
                        "responseId": "resp_0",
                        "usage": {
                          "inputTokens": 5,
                          "outputTokens": 2,
                          "totalTokens": 7
                        }
                      },
                      {
                        "contractVersion": "1",
                        "type": "usage.updated",
                        "terminal": false,
                        "sequence": 3,
                        "responseId": "resp_0",
                        "usage": {
                          "inputTokens": 4,
                          "outputTokens": 2,
                          "totalTokens": 6
                        }
                      }
                    ]
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_stream_sequence",
                expectedPath = "/2/usage/inputTokens",
            ),
            fixture(
                id = "v1-output-text-missing-text",
                relativePath = "invalid/output-text-missing-text.json",
                family = ContractSeedFamily.OUTPUT,
                schemaId = ContractSeedFixtures.OUTPUT_SCHEMA_ID,
                json =
                    """
                    {
                      "id": "out_0",
                      "index": 0,
                      "kind": "text"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "missing_output_text",
                expectedPath = "/text",
            ),
            fixture(
                id = "v1-stream-event-zero-sequence",
                relativePath = "invalid/stream-event-zero-sequence.json",
                family = ContractSeedFamily.STREAM_EVENT,
                schemaId = ContractSeedFixtures.STREAM_EVENT_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "type": "response.started",
                      "terminal": false,
                      "sequence": 0,
                      "responseId": "resp_0"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_stream_sequence",
                expectedKeyword = "minimum",
                expectedPath = "/sequence",
            ),
            fixture(
                id = "v1-stream-sequence-structured-number-equivalence",
                relativePath = "valid/stream-sequence-structured-number-equivalence.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "responseId": "resp_equivalent"
                      },
                      {
                        "contractVersion": "1",
                        "type": "output.started",
                        "terminal": false,
                        "sequence": 2,
                        "responseId": "resp_equivalent",
                        "outputId": "out_0",
                        "outputIndex": 0
                      },
                      {
                        "contractVersion": "1",
                        "type": "output.completed",
                        "terminal": false,
                        "sequence": 3,
                        "responseId": "resp_equivalent",
                        "outputId": "out_0",
                        "outputIndex": 0,
                        "output": {
                          "id": "out_0",
                          "index": 0,
                          "kind": "structured_json",
                          "json": {
                            "value": 1.0
                          }
                        }
                      },
                      {
                        "contractVersion": "1",
                        "type": "response.completed",
                        "terminal": true,
                        "sequence": 4,
                        "responseId": "resp_equivalent",
                        "response": {
                          "contractVersion": "1",
                          "id": "resp_equivalent",
                          "target": {
                            "providerId": "openai",
                            "modelId": "gpt-4.1-mini"
                          },
                          "outputs": [
                            {
                              "id": "out_0",
                              "index": 0,
                              "kind": "structured_json",
                              "json": {
                                "value": 1
                              }
                            }
                          ],
                          "completionReason": "stop"
                        }
                      }
                    ]
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-stream-sequence-usage-detail-regression",
                relativePath = "invalid/stream-sequence-usage-detail-regression.json",
                family = ContractSeedFamily.STREAM_SEQUENCE,
                schemaId = ContractSeedFixtures.STREAM_SEQUENCE_SCHEMA_ID,
                json =
                    """
                    [
                      {
                        "contractVersion": "1",
                        "type": "response.started",
                        "terminal": false,
                        "sequence": 1,
                        "responseId": "resp_0"
                      },
                      {
                        "contractVersion": "1",
                        "type": "usage.updated",
                        "terminal": false,
                        "sequence": 2,
                        "responseId": "resp_0",
                        "usage": {
                          "inputTokens": 5,
                          "outputTokens": 0,
                          "totalTokens": 5,
                          "inputDetails": {
                            "cached": 3
                          }
                        }
                      },
                      {
                        "contractVersion": "1",
                        "type": "usage.updated",
                        "terminal": false,
                        "sequence": 3,
                        "responseId": "resp_0",
                        "usage": {
                          "inputTokens": 5,
                          "outputTokens": 0,
                          "totalTokens": 5,
                          "inputDetails": {
                            "cached": 2
                          }
                        }
                      }
                    ]
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_stream_sequence",
                expectedPath = "/2/usage/inputDetails/cached",
            ),
        )

    private fun fixture(
        id: String,
        relativePath: String,
        family: ContractSeedFamily,
        schemaId: String,
        json: String,
        expectedLayer: ContractSeedLayer? = null,
        expectedCode: String? = null,
        expectedKeyword: String? = null,
        expectedPath: String? = null,
        compatibilityPurpose: String? = null,
    ): ContractSeedFixture =
        ContractSeedFixture(
            id = id,
            repositoryPath = "contracts/fixtures/v1/$relativePath",
            family = family,
            schemaId = schemaId,
            json = json,
            expectedLayer = expectedLayer,
            expectedCode = expectedCode,
            expectedKeyword = expectedKeyword,
            expectedPath = expectedPath,
            compatibilityPurpose = compatibilityPurpose,
        )
}
