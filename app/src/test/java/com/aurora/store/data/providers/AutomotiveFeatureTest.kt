// AutomotiveFeatureTest.kt — the head unit must not announce itself to Play as an automotive device
package com.aurora.store.data.providers

import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomotiveFeatureTest {

    @Test
    fun onlyTheAutomotiveFeatureIsRemoved() {
        val properties = Properties().apply {
            setProperty(
                "Features",
                "android.hardware.wifi,android.hardware.type.automotive,android.software.webview"
            )
            setProperty("Platforms", "arm64-v8a,armeabi-v7a")
        }
        SpoofProvider.stripAutomotiveFeature(properties)
        assertEquals("android.hardware.wifi,android.software.webview", properties.getProperty("Features"))
        assertEquals("arm64-v8a,armeabi-v7a", properties.getProperty("Platforms"))
    }

    @Test
    fun profilesWithoutTheFeatureAreLeftAlone() {
        val properties = Properties().apply { setProperty("Features", "android.hardware.wifi") }
        SpoofProvider.stripAutomotiveFeature(properties)
        assertEquals("android.hardware.wifi", properties.getProperty("Features"))

        val empty = Properties()
        SpoofProvider.stripAutomotiveFeature(empty)
        assertNull(empty.getProperty("Features"))
    }
}
