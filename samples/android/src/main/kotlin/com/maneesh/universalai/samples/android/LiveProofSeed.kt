package com.maneesh.universalai.samples.android

import com.maneesh.universalai.connector.contract.ModelId
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Never a data class: generated diagnostics must not contain the credential. */
class LiveProofSeed private constructor(
    val configuration: LiveConfiguration,
    val model: String,
    val credential: String,
) {
    override fun toString() = "LiveProofSeed(redacted)"

    companion object {
        fun readFramed(input: InputStream, enabled: Boolean, peerUid: Int): LiveProofSeed {
            require(enabled && peerUid in listOf(0, 2000)) { "Development bootstrap is not authorized." }
            val data = DataInputStream(input)
            val size = data.readInt()
            require(size in 20..11000) { "Invalid development bootstrap frame." }
            val bytes = ByteArray(size)
            return try {
                data.readFully(bytes)
                read(java.io.ByteArrayInputStream(bytes), enabled, peerUid)
            } finally { bytes.fill(0) }
        }

        /** Bounded, strict UTF-8 framing; only the explicitly enabled ADB shell/root peer is accepted. */
        fun read(input: InputStream, enabled: Boolean, peerUid: Int): LiveProofSeed {
            require(enabled && peerUid in listOf(0, 2000)) { "Development bootstrap is not authorized." }
            try {
                val data = DataInputStream(input)
                require(data.readInt() == 1)
                val providerId = readField(data, 64)
                val provider = LiveProvider.entries.single { it.id == providerId }
                val configuration = LiveConfiguration(provider, readField(data, 2048))
                val model = readField(data, 256).also { ModelId.of(it) }
                val credential = readField(data, 8192).also(::validateLiveCredential)
                require(data.read() == -1)
                return LiveProofSeed(configuration, model, credential)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid development bootstrap input.")
            }
        }

        private fun readField(input: DataInputStream, maximum: Int): String {
            val size = input.readInt()
            require(size in 0..maximum)
            val bytes = ByteArray(size)
            return try {
                input.readFully(bytes)
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } finally {
                bytes.fill(0)
            }
        }
    }
}
