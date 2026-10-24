package org.shoalsync.android

import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shoalsync.android.folders.PhoneFolders
import org.shoalsync.android.folders.PhoneStorage
import org.shoalsync.android.folders.SyncChoice
import org.shoalsync.ffi.Pairing
import org.shoalsync.ffi.PairingUpdate
import java.io.File

/**
 * Sets the phone up for scripts/battery-bench.sh (ADR 0039), which runs it and then measures:
 * pairs with the host's `shoald`, and joins its two folders on the phone's storage, `In`
 * two-way, so the host's files download, and `Camera` backup only, so photos added there go up.
 * The host reads the pairing code from logcat. The relay URL comes from the `shoalRelay`
 * instrumentation argument.
 */
@RunWith(AndroidJUnit4::class)
class BatterySetup {
    @Test
    fun pairAndJoin() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val relay = arguments.getString("shoalRelay") ?: error("pass shoalRelay")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        Peer.init(context)
        Peer.stop()
        clearDataDir()
        Peer.foldersDir.deleteRecursively()
        Peer.settings.relay = relay
        Peer.settings.relayToken = InstrumentationRegistry.getArguments().getString("shoalRelayToken") ?: ""
        Peer.settings.addressLookup = false
        Peer.settings.localDiscovery = false
        // Background sync on any network, so the benchmark does not depend on the emulator's.
        Peer.settings.syncUnmeteredOnly = false

        val pairing = Pairing.start(Peer.dataDir, Peer.settings.network(), "Benchmark")
        Log.i(TAG, "PAIRING_CODE ${pairing.code()}")
        withTimeout(TIMEOUT_MS) { pairing.nextUpdate() }
        val paired = withTimeout(TIMEOUT_MS) { pairing.nextUpdate() }
        assertTrue("$paired", paired is PairingUpdate.Paired)
        pairing.close()
        val offers = (paired as PairingUpdate.Paired).folders

        instrumentation.uiAutomation
            .executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
            .close()
        repeat(TIMEOUT_MS.toInt() / POLL_MS) { if (!PhoneStorage.hasAccess()) Thread.sleep(POLL_MS.toLong()) }
        assertTrue("All files access", PhoneStorage.hasAccess())
        val storage = Environment.getExternalStorageDirectory()
        for ((name, dir, choice) in listOf(
            Triple("In", File(storage, "ShoalBench/In"), SyncChoice.TWO_WAY),
            Triple("Camera", File(storage, "DCIM/ShoalBench"), SyncChoice.BACKUP_ONLY),
        )) {
            dir.deleteRecursively()
            PhoneFolders.join(offers.single { it.name == name }, dir, choice)
        }
        Peer.stop()
        Log.i(TAG, "READY")
        Unit
    }

    private companion object {
        const val TAG = "BatteryBench"
        const val TIMEOUT_MS = 120_000L
        const val POLL_MS = 100
    }
}
