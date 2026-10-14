package org.tidefell.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UsageTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun countsAddUpWithWhatIsRunning() {
        Usage.add(context, Usage.WORKER_RUNS)
        Usage.add(context, Usage.WORKER_RUNS)
        Usage.add(context, Usage.BYTES_SENT, 1_000)
        // Nothing is negative, whatever a clock did.
        Usage.add(context, Usage.NODE_MS, -5)

        val json = Usage.toJson(context, live = mapOf(Usage.BYTES_SENT to 500L))
        assertEquals(2L, json.getLong(Usage.WORKER_RUNS))
        assertEquals(1_500L, json.getLong(Usage.BYTES_SENT))
        assertEquals(0L, json.getLong(Usage.NODE_MS))
        assertTrue(json.getLong("${Usage.WORKER_RUNS}_per_hour") >= 2)
    }

    @Test
    fun timedAddsTheTimeTakenEvenWhenItFails() {
        runCatching {
            Usage.timed(context, Usage.SCAN_MS) {
                // Robolectric's clock moves only when told.
                ShadowSystemClock.advanceBy(Duration.ofMillis(20))
                error("scan failed")
            }
        }
        assertTrue(Usage.toJson(context).getLong(Usage.SCAN_MS) >= 20)
    }
}
