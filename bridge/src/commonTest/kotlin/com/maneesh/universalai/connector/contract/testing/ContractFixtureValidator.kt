package com.maneesh.universalai.connector.contract.testing

/**
 * Routes fixture families to their bounded validation oracle.
 *
 * P2-E seed validation intentionally remains limited to the envelope and extension families.
 * Model packages own their own fixture validators as the canonical contract grows.
 */
internal object ContractFixtureValidator {
    fun validate(fixture: ContractSeedFixture): ContractSeedIssue? =
        when (fixture.family) {
            ContractSeedFamily.CONTRACT_ENVELOPE,
            ContractSeedFamily.EXTENSION_VALUE,
            ContractSeedFamily.EXTENSIONS,
            -> ContractSeedValidator.validate(fixture)

            ContractSeedFamily.PROVIDER_ID,
            ContractSeedFamily.MODEL_ID,
            ContractSeedFamily.MODEL_TARGET,
            ContractSeedFamily.TEXT_INPUT,
            ContractSeedFamily.RESPONSE_FORMAT,
            ContractSeedFamily.GENERATION_PARAMETERS,
            -> ComponentContractFixtureValidator.validate(fixture)

            ContractSeedFamily.REQUEST -> RequestContractFixtureValidator.validate(fixture)

            ContractSeedFamily.OPERATION_ID,
            ContractSeedFamily.OUTPUT,
            ContractSeedFamily.USAGE,
            ContractSeedFamily.ERROR,
            ContractSeedFamily.RESPONSE,
            ContractSeedFamily.STREAM_EVENT,
            ContractSeedFamily.STREAM_SEQUENCE,
            -> P2GContractFixtureValidator.validate(fixture)

            ContractSeedFamily.CAPABILITY_SET,
            ContractSeedFamily.PROVIDER_CAPABILITY_PROFILE,
            ContractSeedFamily.MODEL_TOKEN_LIMITS,
            ContractSeedFamily.MODEL_DESCRIPTOR,
            -> P2HContractFixtureValidator.validate(fixture)
        }
}
