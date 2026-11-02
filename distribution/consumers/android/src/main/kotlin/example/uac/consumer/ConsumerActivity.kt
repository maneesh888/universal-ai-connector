package example.uac.consumer

import android.app.Activity
import android.os.Bundle
import com.myadidi.universalai.connector.UniversalAiConnector

class ConsumerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UniversalAiConnector().use { client -> check(client.version == BuildConfig.UAC_EXPECTED_VERSION) }
    }
}
