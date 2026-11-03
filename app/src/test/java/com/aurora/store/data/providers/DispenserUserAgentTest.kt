// DispenserUserAgentTest.kt — the anonymous-login dispenser must see upstream Aurora's client id
package com.aurora.store.data.providers

import com.aurora.store.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DispenserUserAgentTest {

    @Test
    fun forkVersionMapsToTheUpstreamRelease() {
        assertEquals(
            "com.aurora.store-4.8.4-76",
            AuthProvider.dispenserUserAgent("4.8.4-od.1", 76 * 100 + 1)
        )
        assertEquals(
            "com.aurora.store-4.9.0-80",
            AuthProvider.dispenserUserAgent("4.9.0-od.12", 80 * 100 + 12)
        )
    }

    @Test
    fun currentBuildNeverSendsTheForkPackageName() {
        // the dispenser answers 403 to "com.overdrive.aurora-..."
        val ua = AuthProvider.dispenserUserAgent(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        assertTrue(ua, ua.startsWith("com.aurora.store-"))
        assertTrue(ua, !ua.contains("overdrive") && !ua.contains("-od"))
    }
}
