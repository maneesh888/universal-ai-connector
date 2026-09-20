package com.maneesh.universalai.samples.host

/** Process memory only; never logs or serializes credential values. */
class SessionCredentials : LiveCredentialStore {
    private val entries = mutableMapOf<String, CharArray>()
    @Synchronized override fun read(key: String): String? = entries[key]?.concatToString()
    @Synchronized override fun save(key: String, credential: String) {
        validateLiveCredential(credential)
        entries.put(key, credential.toCharArray())?.fill('\u0000')
    }
    @Synchronized override fun clearAll() { entries.values.forEach { it.fill('\u0000') }; entries.clear() }
    override fun close() = clearAll()
    override fun toString() = "SessionCredentials(redacted)"
}
