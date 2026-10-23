package dev.heisluka.mobakula

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** One-off diagnostic: does Android instrumentation attach before ANY JNI call? */
@RunWith(AndroidJUnit4::class)
class AndroidStartupProbeTest {
    @Test
    fun instrumentedAppStartsWithoutNativeCalls() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.heisluka.mobakula", ctx.packageName)
    }
}
