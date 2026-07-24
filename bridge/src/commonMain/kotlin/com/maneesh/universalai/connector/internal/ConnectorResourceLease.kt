package com.maneesh.universalai.connector.internal

import com.maneesh.universalai.connector.internal.transport.ConnectorTransport
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job

internal enum class ConnectorResourceOwnership {
    Owned,
    Borrowed,
}

/**
 * Applies connector ownership to one transport without leaking that policy into the transport
 * boundary itself.
 */
internal class ConnectorResourceLease private constructor(
    private val transport: ConnectorTransport?,
    private val ownership: ConnectorResourceOwnership,
) {
    private val closeSignal: CompletableJob = Job()

    fun close() {
        if (closeSignal.complete() && ownership == ConnectorResourceOwnership.Owned) {
            transport?.close()
        }
    }

    internal companion object {
        fun none(): ConnectorResourceLease =
            ConnectorResourceLease(
                transport = null,
                ownership = ConnectorResourceOwnership.Borrowed,
            )

        fun of(
            transport: ConnectorTransport,
            ownership: ConnectorResourceOwnership,
        ): ConnectorResourceLease =
            ConnectorResourceLease(
                transport = transport,
                ownership = ownership,
            )
    }
}
