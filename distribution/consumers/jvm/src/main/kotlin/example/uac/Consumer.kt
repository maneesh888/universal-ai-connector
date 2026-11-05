package example.uac

import com.myadidi.universalai.connector.UniversalAiConnector
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

fun main() {
    val expected = checkNotNull(System.getProperty("uac.expectedVersion"))
    UniversalAiConnector().use { client ->
        check(client.version == expected)
        val artifact = Paths.get(client.javaClass.protectionDomain.codeSource.location.toURI())
        val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(hash == System.getProperty("uac.expectedJarSha256"))
    }
    println("Standalone Maven coordinate construction/version/close passed.")
}
