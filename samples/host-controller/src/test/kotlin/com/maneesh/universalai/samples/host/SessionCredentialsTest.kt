package com.maneesh.universalai.samples.host

import kotlin.test.*

class SessionCredentialsTest {
    @Test fun clearAndCloseRemoveCredentialsWithoutRenderingThem() {
        val store = SessionCredentials()
        store.save("one", "synthetic-secret")
        assertFalse(store.toString().contains("synthetic-secret"))
        assertEquals("synthetic-secret", store.read("one"))
        store.clearAll(); assertNull(store.read("one"))
        store.save("two", "synthetic-new-secret")
        store.close(); assertNull(store.read("two"))
    }
    @Test fun invalidCredentialsAndUrlsAreRejectedWithStaticMessages() {
        assertFailsWith<IllegalArgumentException> { SessionCredentials().save("one", "invalid\ncredential") }
        assertFailsWith<IllegalArgumentException> { LiveConfiguration(LiveProvider.GATEWAY, "https://user:secret@example.com/v1") }
        assertFailsWith<IllegalArgumentException> { LiveConfiguration(LiveProvider.GATEWAY, "http://remote.example/v1") }
        assertEquals("LiveConfiguration(redacted)", LiveConfiguration(LiveProvider.OPENAI, "").toString())
    }
}
