package com.maneesh.universalai.samples.android

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class LiveProofSeedTest {
    @Test fun framedTransportDoesNotRequireSocketEofAndRejectsInvalidLengths() {
        val body = frame()
        val output = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(output).use { it.writeInt(body.size); it.write(body) }
        val valid = output.toByteArray()
        assertEquals("vendor/exact:version", LiveProofSeed.readFramed(ByteArrayInputStream(valid), true, 2000).model)
        listOf(valid.copyOf(valid.size - 1), valid.copyOf().apply { this[0] = 0x7f }).forEach { invalid ->
            assertThrows(Exception::class.java) { LiveProofSeed.readFramed(ByteArrayInputStream(invalid), true, 2000) }
        }
    }

    @Test fun optInAndAuthorizedPeerAreBothRequired() {
        assertThrows(IllegalArgumentException::class.java) { read(frame(), false, 2000) }
        assertThrows(IllegalArgumentException::class.java) { read(frame(), true, 10001) }
        assertEquals("vendor/exact:version", read(frame(), true, 0).model)
        assertEquals("vendor/exact:version", read(frame(), true, 2000).model)
    }

    @Test fun preservesIdentityDefaultsAndRedactsDiagnostics() {
        val seed = read(frame(model = "Vendor/exact@REV"))
        assertEquals("Vendor/exact@REV", seed.model)
        assertEquals("https://api.openai.com/v1", seed.configuration.baseUrl)
        assertFalse(seed.toString().contains(seed.credential))
        assertEquals("LiveConfiguration(redacted)", seed.configuration.toString())
    }

    @Test fun rejectsMissingInvalidAndOversizedFieldsWithoutExposingInput() {
        listOf(
            frame(provider = "unknown"), frame(provider = "openai-compatible"),
            frame(model = ""), frame(model = " model "), frame(model = "x".repeat(257)),
            frame(credential = ""), frame(credential = "a\nb"), frame(credential = "x".repeat(8193)),
            frame(base = "https://user:synthetic-secret@example.com/v1"),
            frame(base = "https://example.com/v1?key=synthetic-secret"), frame(base = "http://example.com/v1"),
            frame(base = "https://example.com/v2"), frame(base = "https://example.com:0/v1"),
        ).forEach { bytes ->
            val failure = assertThrows(IllegalArgumentException::class.java) { read(bytes) }
            assertEquals("Invalid development bootstrap input.", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test fun rejectsTruncationTrailingBytesWrongVersionAndMalformedUtf8() {
        val valid = frame()
        listOf(valid.copyOf(valid.size - 1), valid + 1.toByte(), valid.copyOf().apply { this[3] = 2 },
            valid.copyOf().apply { this[8] = 0xff.toByte() }, valid.copyOf().apply { this[4] = 0x7f }).forEach {
            assertThrows(IllegalArgumentException::class.java) { read(it) }
        }
    }

    @Test fun acceptsGatewayAndExactLoopbackButRejectsLookalikes() {
        assertEquals(LiveProvider.GATEWAY, read(frame(provider = "openai-compatible", base = "https://gateway.example/v1")).configuration.provider)
        listOf("http://127.0.0.1:8080/v1", "http://[::1]/v1", "http://localhost/v1").forEach { read(frame(base = it)) }
        listOf("http://localhost.example/v1", "http://127.0.0.2/v1").forEach { base ->
            assertThrows(IllegalArgumentException::class.java) { read(frame(base = base)) }
        }
    }

    private fun read(bytes: ByteArray, enabled: Boolean = true, uid: Int = 2000) = LiveProofSeed.read(ByteArrayInputStream(bytes), enabled, uid)
}
