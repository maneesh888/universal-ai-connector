package example.uac

import com.myadidi.universalai.connector.UniversalAiConnector

// Compile-only proof that both published native variants remain consumable after license staging.
fun candidateVersion(): String {
    val client = UniversalAiConnector()
    return try {
        client.version
    } finally {
        client.close()
    }
}
