package org.tidefell.android

import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tidefell.android.ui.join
import org.tidefell.ffi.Pairing
import org.tidefell.ffi.PairingUpdate

/**
 * The Phase 4 exit criterion, on an emulator against a real `tidefelld` on the host (see
 * scripts/ci-exit-criterion.sh): pair, join a folder, list it, open a file on demand through
 * the documents provider, and save a file that syncs back to the daemon.
 *
 * The host reads the pairing code from logcat and approves it with `tidefell pair`. The relay URL
 * comes from the `tidefellRelay` instrumentation argument.
 */
@RunWith(AndroidJUnit4::class)
class ExitCriterionTest {
    private suspend fun eventually(what: String, check: () -> Boolean) {
        repeat(600) {
            if (runCatching(check).getOrDefault(false)) return
            delay(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun pairJoinOpenAndSave() = runBlocking {
        val relay = InstrumentationRegistry.getArguments().getString("tidefellRelay")
            ?: error("pass -Pandroid.testInstrumentationRunnerArguments.tidefellRelay=<url>")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Peer.init(context)
        Peer.stop()
        File(Peer.dataDir).deleteRecursively()
        Peer.foldersDir.deleteRecursively()
        Peer.settings.relay = relay
        Peer.settings.addressLookup = false

        // Pair: the host approves the code it finds in logcat.
        val pairing = Pairing.start(Peer.dataDir, Peer.settings.network(), "Emulator")
        Log.i(TAG, "PAIRING_CODE ${pairing.code()}")
        val confirming = withTimeout(120_000) { pairing.nextUpdate() }
        assertTrue("$confirming", confirming is PairingUpdate.Confirming)
        val paired = withTimeout(120_000) { pairing.nextUpdate() }
        assertTrue("$paired", paired is PairingUpdate.Paired)
        pairing.close()
        assertTrue(Peer.paired)
        val offer = (paired as PairingUpdate.Paired).folders.single()
        Log.i(TAG, "paired as ${paired.name}; joining ${offer.name}")

        // Join as a partial replica and wait for the daemon's file to be listed.
        join(offer)
        val node = Peer.start()
        eventually("the daemon's file is listed") { node.files(offer.id).any { it.path == "beach.jpg" } }
        val placeholder = node.files(offer.id).single { it.path == "beach.jpg" }
        assertTrue("a placeholder until opened", !placeholder.onDisk)

        // The folder is a documents root; opening the file fetches it.
        val resolver = context.contentResolver
        val roots = resolver.query(DocumentsContract.buildRootsUri(TidefellDocumentsProvider.AUTHORITY), null, null, null, null)!!
        roots.use { assertEquals(1, it.count) }
        val uri = DocumentsContract.buildDocumentUri(TidefellDocumentsProvider.AUTHORITY, "${offer.id}/beach.jpg")
        val content = resolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        assertEquals("sand and sea", content)

        // Saving a new document syncs it to the daemon; the host checks its folder afterwards.
        val parent = DocumentsContract.buildDocumentUri(TidefellDocumentsProvider.AUTHORITY, "${offer.id}/")
        val created = DocumentsContract.createDocument(resolver, parent, "text/plain", "from-android.txt")!!
        resolver.openOutputStream(created, "wt")!!.use { it.write("hello from android".toByteArray()) }
        eventually("the saved file is recorded") {
            node.files(offer.id).any { it.path == "from-android.txt" && it.onDisk }
        }
        eventually("the daemon is connected") { node.peers().isNotEmpty() }
        Log.i(TAG, "SAVED from-android.txt")
        // Give the daemon time to fetch it before the test process ends.
        delay(10_000)
    }

    companion object {
        private const val TAG = "ExitCriterion"
    }
}
