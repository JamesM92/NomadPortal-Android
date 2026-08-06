package com.jamesm92.nomadportal

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, runs on an Android device/emulator (`./gradlew
 * connectedAndroidTest`). Placeholder — verifies the applicationId resolves
 * correctly, which is a cheap canary for manifest/build-config drift.
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.jamesm92.nomadportal", appContext.packageName)
    }
}
