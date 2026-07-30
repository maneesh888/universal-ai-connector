package com.maneesh.universalai.connector.internal.provider

import com.maneesh.universalai.connector.UniversalAiConnector
import com.maneesh.universalai.connector.contract.ModelId
import com.maneesh.universalai.connector.contract.ProviderId
import com.maneesh.universalai.connector.contract.ResponseId
import com.maneesh.universalai.connector.contract.UniversalAiCompletionReason
import com.maneesh.universalai.connector.contract.UniversalAiErrorCategory
import com.maneesh.universalai.connector.contract.UniversalAiErrorCode
import com.maneesh.universalai.connector.contract.UniversalAiException
import com.maneesh.universalai.connector.contract.UniversalAiInputRole
import com.maneesh.universalai.connector.contract.UniversalAiRequest
import com.maneesh.universalai.connector.contract.UniversalAiResponse
import com.maneesh.universalai.connector.contract.UniversalAiStreamEvent
import com.maneesh.universalai.connector.contract.UniversalAiStreamEventType
import com.maneesh.universalai.connector.contract.UniversalAiTarget
import com.maneesh.universalai.connector.contract.UniversalAiTextInput
import com.maneesh.universalai.connector.internal.ConnectorEngine
import com.maneesh.universalai.connector.internal.ConnectorResourceOwnership
import com.maneesh.universalai.connector.internal.DeterministicConnectorEngine
import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportChunkReader
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportRequest
import com.maneesh.universalai.connector.internal.transport.ConnectorTransportResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProviderRegistryTests {
    @Test
    fun emptyRegistryHasNoSupportedProvider() {
        val registry = ProviderRegistry(emptyList(), RecordingTransport())

        assertTrue(registry.providerIds.isEmpty())
        assertNull(registry.adapterOrNull(ProviderId.of("future-provider")))
    }

    @Test
    fun canonicalIdentifiersRegisterAndListInDeterministicOrder() {
        val firstAdapter = FakeProviderAdapter()
        val secondAdapter = FakeProviderAdapter()
        val registry =
            ProviderRegistry(
                listOf(
                    registration("z-provider", secondAdapter),
                    registration("provider_name-v1", firstAdapter),
                ),
                RecordingTransport(),
            )

        assertEquals(
            listOf("provider_name-v1", "z-provider"),
            registry.providerIds.map(ProviderId::rawValue),
        )
        assertSame(
            firstAdapter,
            registry.adapterOrNull(ProviderId.of("provider_name-v1")),
        )
    }

    @Test
    fun duplicateIdentifiersFailDeterministicallyBeforeAdapterUse() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ProviderRegistry(
                    listOf(
                        registration("z-provider", FakeProviderAdapter()),
                        registration("z-provider", FakeProviderAdapter()),
                        registration("a-provider", FakeProviderAdapter()),
                        registration("a-provider", FakeProviderAdapter()),
                    ),
                    RecordingTransport(),
                )
            }

        assertEquals("Provider 'a-provider' is registered more than once.", failure.message)
    }

    @Test
    fun duplicateIdentifiersFailBeforeTransportBoundFactoriesRun() {
        var factoryCalls = 0
        val transport = RecordingTransport()

        assertFailsWith<IllegalArgumentException> {
            ProviderRegistry(
                registrations =
                    listOf(
                        ProviderRegistration(ProviderId.of("duplicate")) {
                            factoryCalls += 1
                            FakeProviderAdapter()
                        },
                        ProviderRegistration(ProviderId.of("duplicate")) {
                            factoryCalls += 1
                            FakeProviderAdapter()
                        },
                    ),
                transport = transport,
            )
        }

        assertEquals(0, factoryCalls)
        assertEquals(0, transport.closeCalls)
    }

    @Test
    fun immutableRegistrySupportsConcurrentReads() = runTest {
        val adapter = FakeProviderAdapter()
        val providerId = ProviderId.of("fake-provider")
        val registry =
            ProviderRegistry(
                listOf(ProviderRegistration(providerId, adapter)),
                RecordingTransport(),
            )

        val resolved =
            List(128) {
                async(Dispatchers.Default) {
                    registry.adapterOrNull(providerId)
                }
            }.awaitAll()

        assertTrue(resolved.all { candidate -> candidate === adapter })
        assertEquals(listOf(providerId), registry.providerIds)
    }

    @Test
    fun primaryClientRoutesResponseAndStreamToRegisteredFakeAdapter() = runTest {
        val adapter = FakeProviderAdapter()
        val transport = RecordingTransport()
        val connector =
            UniversalAiConnector.createForTesting(
                engineFactory = ::DeterministicConnectorEngine,
                transport = transport,
                ownership = ConnectorResourceOwnership.Owned,
                providerRegistrations =
                    listOf(
                        registration("fake-provider", adapter),
                    ),
            )
        val request = request(providerId = "fake-provider")

        val response = connector.respond(request)
        val events = connector.stream(request).toList()

        assertEquals(request.target, response.target)
        assertEquals("fake-response", response.id.rawValue)
        assertEquals(1, adapter.responseCalls)
        assertEquals(1, adapter.streamCalls)
        assertEquals(
            listOf(
                UniversalAiStreamEventType.ResponseStarted,
                UniversalAiStreamEventType.ResponseCompleted,
            ),
            events.map(UniversalAiStreamEvent::type),
        )

        connector.close()
        assertEquals(1, transport.closeCalls)
    }

    @Test
    fun clientClosePreventsRegisteredAdapterLookupAndInvocation() = runTest {
        val adapter = FakeProviderAdapter()
        val connector =
            UniversalAiConnector.createForTesting(
                engineFactory = ::DeterministicConnectorEngine,
                transport = RecordingTransport(),
                ownership = ConnectorResourceOwnership.Owned,
                providerRegistrations =
                    listOf(
                        registration("fake-provider", adapter),
                    ),
            )
        val request = request(providerId = "fake-provider")

        connector.close()

        val failure =
            assertFailsWith<UniversalAiException> {
                connector.respond(request)
            }
        assertEquals(UniversalAiErrorCategory.Validation, failure.error.category)
        assertEquals(UniversalAiErrorCode.InvalidRequest, failure.error.code)
        assertEquals(UniversalAiConnector.CLOSED_MESSAGE, failure.message)
        assertEquals(0, adapter.responseCalls)
    }

    @Test
    fun duplicateRegistryConstructionClosesOwnedTransport() {
        val transport = RecordingTransport()

        val failure =
            assertFailsWith<IllegalArgumentException> {
                UniversalAiConnector.createForTesting(
                    engineFactory = ::DeterministicConnectorEngine,
                    transport = transport,
                    ownership = ConnectorResourceOwnership.Owned,
                    providerRegistrations =
                        listOf(
                            registration("duplicate", FakeProviderAdapter()),
                            registration("duplicate", FakeProviderAdapter()),
                        ),
                )
            }

        assertEquals("Provider 'duplicate' is registered more than once.", failure.message)
        assertEquals(1, transport.closeCalls)
    }

    private fun registration(
        providerId: String,
        adapter: ConnectorEngine,
    ): ProviderRegistration =
        ProviderRegistration(
            providerId = ProviderId.of(providerId),
            adapter = adapter,
        )

    private fun request(providerId: String): UniversalAiRequest =
        UniversalAiRequest(
            target =
                UniversalAiTarget(
                    providerId = ProviderId.of(providerId),
                    modelId = ModelId.of("fake-model"),
                ),
            input =
                listOf(
                    UniversalAiTextInput(
                        role = UniversalAiInputRole.User,
                        content = "hello",
                    ),
                ),
        )

    private class FakeProviderAdapter : ConnectorEngine {
        var responseCalls: Int = 0
            private set
        var streamCalls: Int = 0
            private set

        override suspend fun respond(request: UniversalAiRequest): UniversalAiResponse {
            responseCalls += 1
            return response(request)
        }

        override fun stream(request: UniversalAiRequest): Flow<UniversalAiStreamEvent> =
            flow {
                streamCalls += 1
                val response = response(request)
                emit(
                    UniversalAiStreamEvent(
                        type = UniversalAiStreamEventType.ResponseStarted,
                        terminal = false,
                        sequence = 1,
                        responseId = response.id,
                    ),
                )
                emit(
                    UniversalAiStreamEvent(
                        type = UniversalAiStreamEventType.ResponseCompleted,
                        terminal = true,
                        sequence = 2,
                        responseId = response.id,
                        response = response,
                    ),
                )
            }

        private fun response(request: UniversalAiRequest): UniversalAiResponse =
            UniversalAiResponse(
                id = ResponseId.of("fake-response"),
                target = request.target,
                outputs = emptyList(),
                completionReason = UniversalAiCompletionReason.Stop,
            )
    }

    private class RecordingTransport : ConnectorTransport {
        var closeCalls: Int = 0
            private set

        override suspend fun <Result> execute(
            request: ConnectorTransportRequest,
            consumeResponse: suspend (ConnectorTransportResponse) -> Result,
        ): Result =
            consumeResponse(
                ConnectorTransportResponse(
                    statusCode = 204,
                    headers = emptyList(),
                    body = ConnectorTransportChunkReader { null },
                ),
            )

        override fun close() {
            closeCalls += 1
        }
    }
}
