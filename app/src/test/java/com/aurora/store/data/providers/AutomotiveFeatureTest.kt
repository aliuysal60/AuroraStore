// AutomotiveFeatureTest.kt — the head unit must look to Play like a regular device with a rotating screen
package com.aurora.store.data.providers

import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomotiveFeatureTest {

    @Test
    fun headUnitLosesAutomotiveAndGainsPortrait() {
        val properties = Properties().apply {
            setProperty(
                "Features",
                "android.hardware.wifi,android.hardware.type.automotive," +
                    "android.hardware.screen.landscape,android.software.webview"
            )
            setProperty("Platforms", "arm64-v8a,armeabi-v7a")
        }
        SpoofProvider.adaptHeadUnitFeatures(properties)
        assertEquals(
            "android.hardware.wifi,android.hardware.screen.landscape,android.software.webview," +
                "android.hardware.screen.portrait",
            properties.getProperty("Features")
        )
        assertEquals("arm64-v8a,armeabi-v7a", properties.getProperty("Platforms"))
    }

    @Test
    fun portraitIsNotDuplicated() {
        val properties = Properties().apply {
            setProperty(
                "Features",
                "android.hardware.type.automotive,android.hardware.screen.portrait"
            )
        }
        SpoofProvider.adaptHeadUnitFeatures(properties)
        assertEquals("android.hardware.screen.portrait", properties.getProperty("Features"))
    }

    @Test
    fun nonHeadUnitProfilesAreLeftAlone() {
        val properties = Properties().apply {
            setProperty("Features", "android.hardware.wifi,android.hardware.screen.landscape")
        }
        SpoofProvider.adaptHeadUnitFeatures(properties)
        assertEquals(
            "android.hardware.wifi,android.hardware.screen.landscape",
            properties.getProperty("Features")
        )

        val empty = Properties()
        SpoofProvider.adaptHeadUnitFeatures(empty)
        assertNull(empty.getProperty("Features"))
    }
}
