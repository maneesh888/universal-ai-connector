package example.uac.consumer

import com.myadidi.universalai.connector.UniversalAiConnector
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateConsumerTest {
    @Test fun constructsChecksVersionAndClosesWithoutNetwork() {
        UniversalAiConnector().use { client -> assertEquals(BuildConfig.UAC_EXPECTED_VERSION, client.version) }
    }
}
