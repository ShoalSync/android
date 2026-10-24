package org.shoalsync.android

import android.app.Application
import android.util.Log
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppLogTest {
    @After
    fun reset() {
        AppLog.engineReady = false
    }

    @Test
    fun beforeTheEngineMessagesGoToLogcat() {
        AppLog.w("Test", "something failed", IllegalStateException("broken"))
        val logged = ShadowLog.getLogsForTag("Test").single()
        assertTrue(logged.type == Log.WARN)
        assertTrue(logged.msg.startsWith("something failed: java.lang.IllegalStateException: broken"))
    }

    @Test
    fun anEngineThatCannotLogFallsBackToLogcat() {
        // No native library in unit tests: the engine's logger throws, and the message still lands.
        AppLog.engineReady = true
        AppLog.i("Test", "started")
        assertTrue(ShadowLog.getLogsForTag("Test").any { it.msg == "started" && it.type == Log.INFO })
    }
}
