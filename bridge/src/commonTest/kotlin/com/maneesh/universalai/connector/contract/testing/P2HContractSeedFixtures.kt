package com.maneesh.universalai.connector.contract.testing

/** P2-H capability and model-descriptor fixtures kept separate for bounded review. */
internal object P2HContractSeedFixtures {
    val all: List<ContractSeedFixture> =
        listOf(
            fixture(
                id = "v1-capability-set-minimal",
                relativePath = "valid/capability-set-minimal.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json = "{}",
            ),
            fixture(
                id = "v1-capability-set-complete",
                relativePath = "valid/capability-set-complete.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "streaming": {
                        "support": "supported",
                        "extensions": {
                          "com.example.connector": {
                            "delivery": {
                              "ordered": true
                            }
                          }
                        }
                      },
                      "structured_output": {
                        "support": "supported",
                        "limits": {
                          "max_schema_bytes": 65536,
                          "max_schema_depth": 16
                        }
                      },
                      "legacy_text": {
                        "support": "unsupported"
                      },
                      "embeddings": {
                        "support": "unknown"
                      }
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-capability-set-future-values",
                relativePath = "compatibility/capability-set-future-values.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "future_audio": {
                        "support": "conditionally_supported",
                        "limits": {
                          "max_frame_bytes": 4096
                        },
                        "extensions": {
                          "com.example.future": {
                            "nested": {
                              "mode": "preserve"
                            }
                          }
                        },
                        "futureDeclarationMember": {
                          "ignored": true
                        }
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve future capability, support, limit, and extension values while " +
                        "dropping an additive ordinary declaration member.",
            ),
            fixture(
                id = "v1-capability-set-malformed-name",
                relativePath = "invalid/capability-set-malformed-name.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "Bad Name": {
                        "support": "supported"
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_capability_name",
                expectedKeyword = "propertyNames",
                expectedPath = "",
            ),
            fixture(
                id = "v1-capability-set-malformed-support",
                relativePath = "invalid/capability-set-malformed-support.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "streaming": {
                        "support": "Supported"
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_capability_support",
                expectedKeyword = "pattern",
                expectedPath = "/streaming/support",
            ),
            fixture(
                id = "v1-capability-set-missing-support",
                relativePath = "invalid/capability-set-missing-support.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "streaming": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "missing_capability_support",
                expectedKeyword = "required",
                expectedPath = "/streaming",
            ),
            fixture(
                id = "v1-capability-set-null-support",
                relativePath = "invalid/capability-set-null-support.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "streaming": {
                        "support": null
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_capability_support",
                expectedKeyword = "type",
                expectedPath = "/streaming/support",
            ),
            fixture(
                id = "v1-capability-set-null-limits",
                relativePath = "invalid/capability-set-null-limits.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "streaming": {
                        "support": "supported",
                        "limits": null
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_capability_limits",
                expectedKeyword = "type",
                expectedPath = "/streaming/limits",
            ),
            fixture(
                id = "v1-capability-set-negative-limit",
                relativePath = "invalid/capability-set-negative-limit.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "structured_output": {
                        "support": "supported",
                        "limits": {
                          "max_schema_depth": -1
                        }
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "capability_limit_out_of_range",
                expectedKeyword = "minimum",
                expectedPath = "/structured_output/limits/max_schema_depth",
            ),
            fixture(
                id = "v1-capability-set-malformed-limit-name",
                relativePath = "invalid/capability-set-malformed-limit-name.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "structured_output": {
                        "support": "supported",
                        "limits": {
                          "Bad Limit": 1
                        }
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_capability_limit_name",
                expectedKeyword = "propertyNames",
                expectedPath = "/structured_output/limits",
            ),
            fixture(
                id = "v1-capability-set-oversized-limit",
                relativePath = "invalid/capability-set-oversized-limit.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "structured_output": {
                        "support": "supported",
                        "limits": {
                          "max_schema_bytes": 9007199254740992
                        }
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "capability_limit_out_of_range",
                expectedKeyword = "maximum",
                expectedPath = "/structured_output/limits/max_schema_bytes",
            ),
            fixture(
                id = "v1-capability-set-zero-known-limit",
                relativePath = "invalid/capability-set-zero-known-limit.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "structured_output": {
                        "support": "supported",
                        "limits": {
                          "max_schema_depth": 0
                        }
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "capability_limit_out_of_range",
                expectedPath = "/structured_output/limits/max_schema_depth",
            ),
            fixture(
                id = "v1-capability-set-unsupported-with-limits",
                relativePath = "invalid/capability-set-unsupported-with-limits.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "streaming": {
                        "support": "unsupported",
                        "limits": {
                          "future_limit": 1
                        }
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "unsupported_capability_limits",
                expectedPath = "/streaming/limits",
            ),
            fixture(
                id = "v1-capability-set-known-limit-not-applicable",
                relativePath = "invalid/capability-set-known-limit-not-applicable.json",
                family = ContractSeedFamily.CAPABILITY_SET,
                schemaId = ContractSeedFixtures.CAPABILITY_SET_SCHEMA_ID,
                json =
                    """
                    {
                      "streaming": {
                        "support": "supported",
                        "limits": {
                          "max_schema_bytes": 1024
                        }
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "capability_limit_not_applicable",
                expectedPath = "/streaming/limits/max_schema_bytes",
            ),
            fixture(
                id = "v1-provider-capability-profile-minimal",
                relativePath = "valid/provider-capability-profile-minimal.json",
                family = ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
                schemaId = ContractSeedFixtures.PROVIDER_CAPABILITY_PROFILE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "providerId": "example",
                      "capabilities": {}
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-provider-capability-profile-complete",
                relativePath = "valid/provider-capability-profile-complete.json",
                family = ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
                schemaId = ContractSeedFixtures.PROVIDER_CAPABILITY_PROFILE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "providerId": "example",
                      "capabilities": {
                        "streaming": {
                          "support": "supported",
                          "limits": {
                            "delivery_window": 8
                          }
                        },
                        "structured_output": {
                          "support": "unsupported"
                        }
                      },
                      "extensions": {
                        "com.example.connector": {
                          "profile": {
                            "tier": "deterministic"
                          }
                        }
                      }
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-provider-capability-profile-baseline",
                relativePath = "compatibility/provider-capability-profile-baseline.json",
                family = ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
                schemaId = ContractSeedFixtures.PROVIDER_CAPABILITY_PROFILE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "providerId": "example",
                      "capabilities": {
                        "streaming": {
                          "support": "unknown"
                        }
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Retain the first V1 provider-profile shape as an older compatibility baseline.",
            ),
            fixture(
                id = "v1-provider-capability-profile-future-additive",
                relativePath = "compatibility/provider-capability-profile-future-additive.json",
                family = ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
                schemaId = ContractSeedFixtures.PROVIDER_CAPABILITY_PROFILE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "providerId": "example",
                      "capabilities": {
                        "future_audio": {
                          "support": "preview",
                          "limits": {
                            "max_channels": 2
                          }
                        }
                      },
                      "extensions": {
                        "com.example.future": {
                          "nested": [
                            true,
                            null,
                            {
                              "value": 1
                            }
                          ]
                        }
                      },
                      "futureProfileMember": {
                        "ignored": true
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve future raw capability data and governed extensions while dropping " +
                        "an additive ordinary profile member.",
            ),
            fixture(
                id = "v1-provider-capability-profile-missing-capabilities",
                relativePath = "invalid/provider-capability-profile-missing-capabilities.json",
                family = ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
                schemaId = ContractSeedFixtures.PROVIDER_CAPABILITY_PROFILE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "providerId": "example"
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "missing_capabilities",
                expectedKeyword = "required",
                expectedPath = "",
            ),
            fixture(
                id = "v1-provider-capability-profile-null-capabilities",
                relativePath = "invalid/provider-capability-profile-null-capabilities.json",
                family = ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
                schemaId = ContractSeedFixtures.PROVIDER_CAPABILITY_PROFILE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "providerId": "example",
                      "capabilities": null
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_capabilities",
                expectedKeyword = "type",
                expectedPath = "/capabilities",
            ),
            fixture(
                id = "v1-provider-capability-profile-null-extensions",
                relativePath = "invalid/provider-capability-profile-null-extensions.json",
                family = ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
                schemaId = ContractSeedFixtures.PROVIDER_CAPABILITY_PROFILE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "providerId": "example",
                      "capabilities": {},
                      "extensions": null
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_extensions",
                expectedKeyword = "type",
                expectedPath = "/extensions",
            ),
            fixture(
                id = "v1-provider-capability-profile-missing-version",
                relativePath = "invalid/provider-capability-profile-missing-version.json",
                family = ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
                schemaId = ContractSeedFixtures.PROVIDER_CAPABILITY_PROFILE_SCHEMA_ID,
                json =
                    """
                    {
                      "providerId": "example",
                      "capabilities": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "missing_contract_version",
                expectedKeyword = "required",
                expectedPath = "",
            ),
            fixture(
                id = "v1-provider-capability-profile-null-version",
                relativePath = "invalid/provider-capability-profile-null-version.json",
                family = ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
                schemaId = ContractSeedFixtures.PROVIDER_CAPABILITY_PROFILE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": null,
                      "providerId": "example",
                      "capabilities": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_contract_version",
                expectedKeyword = "const",
                expectedPath = "/contractVersion",
            ),
            fixture(
                id = "v1-provider-capability-profile-future-version",
                relativePath = "invalid/provider-capability-profile-future-version.json",
                family = ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
                schemaId = ContractSeedFixtures.PROVIDER_CAPABILITY_PROFILE_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "2",
                      "providerId": "example",
                      "capabilities": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "unsupported_contract_version",
                expectedKeyword = "const",
                expectedPath = "/contractVersion",
            ),
            fixture(
                id = "v1-model-token-limits-minimal",
                relativePath = "valid/model-token-limits-minimal.json",
                family = ContractSeedFamily.MODEL_TOKEN_LIMITS,
                schemaId = ContractSeedFixtures.MODEL_TOKEN_LIMITS_SCHEMA_ID,
                json =
                    """
                    {
                      "maxOutputTokens": 1
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-model-token-limits-complete",
                relativePath = "valid/model-token-limits-complete.json",
                family = ContractSeedFamily.MODEL_TOKEN_LIMITS,
                schemaId = ContractSeedFixtures.MODEL_TOKEN_LIMITS_SCHEMA_ID,
                json =
                    """
                    {
                      "contextWindowTokens": 131072,
                      "maxInputTokens": 114688,
                      "maxOutputTokens": 16384
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-model-token-limits-empty",
                relativePath = "valid/model-token-limits-empty.json",
                family = ContractSeedFamily.MODEL_TOKEN_LIMITS,
                schemaId = ContractSeedFixtures.MODEL_TOKEN_LIMITS_SCHEMA_ID,
                json = "{}",
            ),
            fixture(
                id = "v1-model-token-limits-future-only",
                relativePath = "compatibility/model-token-limits-future-only.json",
                family = ContractSeedFamily.MODEL_TOKEN_LIMITS,
                schemaId = ContractSeedFixtures.MODEL_TOKEN_LIMITS_SCHEMA_ID,
                json =
                    """
                    {
                      "futureTokenWindow": 262144
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Accept and drop an additive future-only member, producing the empty known " +
                        "V1 limit view.",
            ),
            fixture(
                id = "v1-model-token-limits-null-input",
                relativePath = "invalid/model-token-limits-null-input.json",
                family = ContractSeedFamily.MODEL_TOKEN_LIMITS,
                schemaId = ContractSeedFixtures.MODEL_TOKEN_LIMITS_SCHEMA_ID,
                json =
                    """
                    {
                      "maxInputTokens": null
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_model_token_limit",
                expectedKeyword = "type",
                expectedPath = "/maxInputTokens",
            ),
            fixture(
                id = "v1-model-token-limits-negative-context",
                relativePath = "invalid/model-token-limits-negative-context.json",
                family = ContractSeedFamily.MODEL_TOKEN_LIMITS,
                schemaId = ContractSeedFixtures.MODEL_TOKEN_LIMITS_SCHEMA_ID,
                json =
                    """
                    {
                      "contextWindowTokens": -1
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "model_token_limit_out_of_range",
                expectedKeyword = "minimum",
                expectedPath = "/contextWindowTokens",
            ),
            fixture(
                id = "v1-model-token-limits-oversized-input",
                relativePath = "invalid/model-token-limits-oversized-input.json",
                family = ContractSeedFamily.MODEL_TOKEN_LIMITS,
                schemaId = ContractSeedFixtures.MODEL_TOKEN_LIMITS_SCHEMA_ID,
                json =
                    """
                    {
                      "maxInputTokens": 9007199254740992
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "model_token_limit_out_of_range",
                expectedKeyword = "maximum",
                expectedPath = "/maxInputTokens",
            ),
            fixture(
                id = "v1-model-token-limits-oversized-output",
                relativePath = "invalid/model-token-limits-oversized-output.json",
                family = ContractSeedFamily.MODEL_TOKEN_LIMITS,
                schemaId = ContractSeedFixtures.MODEL_TOKEN_LIMITS_SCHEMA_ID,
                json =
                    """
                    {
                      "maxOutputTokens": 1048577
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "model_token_limit_out_of_range",
                expectedKeyword = "maximum",
                expectedPath = "/maxOutputTokens",
            ),
            fixture(
                id = "v1-model-token-limits-input-exceeds-context",
                relativePath = "invalid/model-token-limits-input-exceeds-context.json",
                family = ContractSeedFamily.MODEL_TOKEN_LIMITS,
                schemaId = ContractSeedFixtures.MODEL_TOKEN_LIMITS_SCHEMA_ID,
                json =
                    """
                    {
                      "contextWindowTokens": 100,
                      "maxInputTokens": 101
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "model_input_limit_exceeds_context",
                expectedPath = "/maxInputTokens",
            ),
            fixture(
                id = "v1-model-token-limits-output-exceeds-context",
                relativePath = "invalid/model-token-limits-output-exceeds-context.json",
                family = ContractSeedFamily.MODEL_TOKEN_LIMITS,
                schemaId = ContractSeedFixtures.MODEL_TOKEN_LIMITS_SCHEMA_ID,
                json =
                    """
                    {
                      "contextWindowTokens": 100,
                      "maxOutputTokens": 101
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "model_output_limit_exceeds_context",
                expectedPath = "/maxOutputTokens",
            ),
            fixture(
                id = "v1-model-descriptor-minimal",
                relativePath = "valid/model-descriptor-minimal.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      },
                      "capabilities": {}
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-model-descriptor-missing-version",
                relativePath = "invalid/model-descriptor-missing-version.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      },
                      "capabilities": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "missing_contract_version",
                expectedKeyword = "required",
                expectedPath = "",
            ),
            fixture(
                id = "v1-model-descriptor-null-version",
                relativePath = "invalid/model-descriptor-null-version.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": null,
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      },
                      "capabilities": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_contract_version",
                expectedKeyword = "const",
                expectedPath = "/contractVersion",
            ),
            fixture(
                id = "v1-model-descriptor-future-version",
                relativePath = "invalid/model-descriptor-future-version.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "2",
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      },
                      "capabilities": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "unsupported_contract_version",
                expectedKeyword = "const",
                expectedPath = "/contractVersion",
            ),
            fixture(
                id = "v1-model-descriptor-complete",
                relativePath = "valid/model-descriptor-complete.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      },
                      "displayName": "Example Model V1",
                      "limits": {
                        "contextWindowTokens": 131072,
                        "maxInputTokens": 114688,
                        "maxOutputTokens": 16384
                      },
                      "capabilities": {
                        "streaming": {
                          "support": "supported"
                        },
                        "structured_output": {
                          "support": "supported",
                          "limits": {
                            "max_schema_bytes": 65536,
                            "max_schema_depth": 16
                          }
                        }
                      },
                      "extensions": {
                        "com.example.connector": {
                          "descriptor": {
                            "tier": "alpha",
                            "flags": [
                              true,
                              null
                            ]
                          }
                        }
                      }
                    }
                    """.trimIndent(),
            ),
            fixture(
                id = "v1-model-descriptor-future-additive",
                relativePath = "compatibility/model-descriptor-future-additive.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "example",
                        "modelId": "future-model",
                        "futureTargetMember": true
                      },
                      "displayName": "Future Model",
                      "limits": {
                        "contextWindowTokens": 262144,
                        "maxInputTokens": 245760,
                        "maxOutputTokens": 16384,
                        "futureLimit": 7
                      },
                      "capabilities": {
                        "future_audio": {
                          "support": "preview",
                          "limits": {
                            "max_channels": 2
                          }
                        }
                      },
                      "extensions": {
                        "com.example.future": {
                          "nested": {
                            "preserved": true
                          }
                        }
                      },
                      "futureDescriptorMember": {
                        "ignored": true
                      }
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Preserve future capability and extension data while dropping additive " +
                        "ordinary descriptor, target, and model-limit members.",
            ),
            fixture(
                id = "v1-model-descriptor-future-only-limits",
                relativePath = "compatibility/model-descriptor-future-only-limits.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "example",
                        "modelId": "future-limits-only"
                      },
                      "limits": {
                        "futureTokenWindow": 262144
                      },
                      "capabilities": {}
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Accept an additive future-only model-limit object and omit the empty known " +
                        "projection when re-encoding.",
            ),
            fixture(
                id = "v1-model-descriptor-null-display-name",
                relativePath = "invalid/model-descriptor-null-display-name.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      },
                      "displayName": null,
                      "capabilities": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_model_display_name",
                expectedKeyword = "type",
                expectedPath = "/displayName",
            ),
            fixture(
                id = "v1-model-descriptor-empty-display-name",
                relativePath = "invalid/model-descriptor-empty-display-name.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      },
                      "displayName": "",
                      "capabilities": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_model_display_name",
                expectedKeyword = "minLength",
                expectedPath = "/displayName",
            ),
            fixture(
                id = "v1-model-descriptor-blank-display-name",
                relativePath = "invalid/model-descriptor-blank-display-name.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      },
                      "displayName": "   ",
                      "capabilities": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "invalid_model_display_name",
                expectedPath = "/displayName",
            ),
            fixture(
                id = "v1-model-descriptor-display-name-too-large",
                relativePath = "invalid/model-descriptor-display-name-too-large.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      },
                      "displayName": "😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀",
                      "capabilities": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SEMANTIC,
                expectedCode = "model_display_name_too_large",
                expectedPath = "/displayName",
            ),
            fixture(
                id = "v1-model-descriptor-null-limits",
                relativePath = "invalid/model-descriptor-null-limits.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      },
                      "limits": null,
                      "capabilities": {}
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_model_limits",
                expectedKeyword = "type",
                expectedPath = "/limits",
            ),
            fixture(
                id = "v1-model-descriptor-empty-limits",
                relativePath = "compatibility/model-descriptor-empty-limits.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      },
                      "limits": {},
                      "capabilities": {}
                    }
                    """.trimIndent(),
                compatibilityPurpose =
                    "Normalize an empty known V1 limit view to an omitted descriptor limits field.",
            ),
            fixture(
                id = "v1-model-descriptor-missing-capabilities",
                relativePath = "invalid/model-descriptor-missing-capabilities.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      }
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "missing_capabilities",
                expectedKeyword = "required",
                expectedPath = "",
            ),
            fixture(
                id = "v1-model-descriptor-null-capabilities",
                relativePath = "invalid/model-descriptor-null-capabilities.json",
                family = ContractSeedFamily.MODEL_DESCRIPTOR,
                schemaId = ContractSeedFixtures.MODEL_DESCRIPTOR_SCHEMA_ID,
                json =
                    """
                    {
                      "contractVersion": "1",
                      "target": {
                        "providerId": "example",
                        "modelId": "model-v1"
                      },
                      "capabilities": null
                    }
                    """.trimIndent(),
                expectedLayer = ContractSeedLayer.SCHEMA,
                expectedCode = "invalid_capabilities",
                expectedKeyword = "type",
                expectedPath = "/capabilities",
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
