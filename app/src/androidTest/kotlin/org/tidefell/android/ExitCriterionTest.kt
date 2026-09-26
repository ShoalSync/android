package org.tidefell.android

import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.PhoneStorage
import org.tidefell.android.folders.SyncChoice
import org.tidefell.ffi.Pairing
import org.tidefell.ffi.PairingUpdate

/**
 * The exit criteria on an emulator against a real `tidefelld` on the host (see
 * scripts/ci-exit-criterion.sh), which shares two folders: Photos and Camera.
 *
 * Phase 4: pair, join Photos with files on demand, list it, open a file through the documents
 * provider, and save one that syncs back to the daemon.
 *
 * Phase 5d (ADR 0025): join Camera at a folder on the phone with remove after backup. A photo
 * taken there reaches the daemon and moves to the phone's recycle bin only once the daemon, a
 * full replica, confirms it, while the daemon's own file is never downloaded. Restoring it puts it
 * back. Switched to two-way sync, the daemon's file arrives, and deleting it on the phone leaves
 * it on the daemon.
 *
 * The host reads the pairing code from logcat and approves it with `tidefell pair`, and checks its
 * folders afterwards. The relay URL comes from the `tidefellRelay` instrumentation argument.
 */
@RunWith(AndroidJUnit4::class)
class ExitCriterionTest {
    private suspend fun eventually(what: String, seconds: Int = 90, check: suspend () -> Boolean) {
        repeat(seconds * 10) {
            if (runCatching { check() }.getOrDefault(false)) return
            delay(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun pairJoinOpenSaveAndPhoneFolders() = runBlocking {
        val relay = InstrumentationRegistry.getArguments().getString("tidefellRelay")
            ?: error("pass -Pandroid.testInstrumentationRunnerArguments.tidefellRelay=<url>")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
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
        val offers = (paired as PairingUpdate.Paired).folders
        val photos = offers.single { it.name == "Photos" }
        val cameraOffer = offers.single { it.name == "Camera" }
        Log.i(TAG, "paired as ${paired.name}; offered ${offers.map { it.name }}")

        // Photos, with files on demand: the daemon's file is a placeholder until opened.
        val photosFolder = PhoneFolders.join(photos, null, SyncChoice.FILES_ON_DEMAND)
        assertEquals(SyncChoice.FILES_ON_DEMAND, PhoneFolders.choice(photosFolder))
        val node = Peer.start()
        eventually("the daemon's file is listed") { node.files(photos.id).any { it.path == "beach.jpg" } }
        assertFalse("a placeholder until opened", node.files(photos.id).single { it.path == "beach.jpg" }.onDisk)

        // The folder is a documents root; opening the file fetches it.
        val resolver = context.contentResolver
        val uri = DocumentsContract.buildDocumentUri(TidefellDocumentsProvider.AUTHORITY, "${photos.id}/beach.jpg")
        assertEquals("sand and sea", resolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })

        // Saving a new document syncs it to the daemon; the host checks its folder afterwards.
        val parent = DocumentsContract.buildDocumentUri(TidefellDocumentsProvider.AUTHORITY, "${photos.id}/")
        val created = DocumentsContract.createDocument(resolver, parent, "text/plain", "from-android.txt")!!
        resolver.openOutputStream(created, "wt")!!.use { it.write("hello from android".toByteArray()) }
        eventually("the saved file is recorded") { node.files(photos.id).any { it.path == "from-android.txt" && it.onDisk } }
        eventually("the daemon is connected") { node.peers().isNotEmpty() }
        Log.i(TAG, "SAVED from-android.txt")

        // Camera, at a folder on the phone, with remove after backup. All files access is granted
        // the way a user would in Settings.
        instrumentation.uiAutomation
            .executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
            .close()
        eventually("All files access") { PhoneStorage.hasAccess() }
        val dir = File(Environment.getExternalStorageDirectory(), "DCIM/TidefellTest")
        dir.deleteRecursively()
        val camera = PhoneFolders.join(cameraOffer, dir, SyncChoice.REMOVE_AFTER_BACKUP)
        assertEquals(SyncChoice.REMOVE_AFTER_BACKUP, PhoneFolders.choice(camera))
        val roots = resolver.query(DocumentsContract.buildRootsUri(TidefellDocumentsProvider.AUTHORITY), null, null, null, null)!!
        roots.use { assertEquals("folders on the phone are not document roots", 1, it.count) }

        // The daemon's file is listed but never downloaded.
        eventually("the daemon's camera file is listed") { node.files(camera.id).any { it.path == "desktop.txt" } }

        // A photo taken now reaches the daemon, which confirms it; then it leaves the phone.
        val photo = File(dir, "IMG_0001.jpg")
        photo.writeText("photo ${System.nanoTime()}")
        node.scan(camera.id)
        assertTrue("only here until confirmed", node.files(camera.id).single { it.path == "IMG_0001.jpg" }.onlyHere)
        Log.i(TAG, "TAKEN IMG_0001.jpg")
        // The engine confirms copies every few seconds; remove after backup looks every minute.
        eventually("the photo is confirmed and moved to the recycle bin", seconds = 180) { !photo.exists() }
        val removed = node.recycleBin(camera.id).single { it.path == "IMG_0001.jpg" }
        assertTrue("removed from this phone only", removed.hereOnly)
        assertTrue("confirmed by the daemon", node.files(camera.id).single { it.path == "IMG_0001.jpg" }.confirmedCopies >= 1u)
        assertFalse("never downloaded", File(dir, "desktop.txt").exists())
        Log.i(TAG, "REMOVED IMG_0001.jpg")

        // Restoring puts it back on the phone, and remove after backup leaves it there.
        PhoneFolders.restore(camera.id, removed)
        assertTrue("restored", photo.exists())
        node.scan(camera.id)
        assertEquals("kept after restoring", 0, PhoneFolders.removeBackedUp(node))
        assertTrue(photo.exists())

        // Two-way sync: the daemon's file arrives; deleting it on the phone keeps it on the daemon.
        PhoneFolders.setChoice(camera, SyncChoice.TWO_WAY)
        assertEquals(SyncChoice.TWO_WAY, PhoneFolders.choice(node.folders().single { it.id == camera.id }))
        val desktop = File(dir, "desktop.txt")
        eventually("the daemon's file arrives") { desktop.exists() }
        assertTrue(desktop.delete())
        node.scan(camera.id)
        assertTrue("still listed", node.files(camera.id).any { it.path == "desktop.txt" && !it.onDisk })
        Log.i(TAG, "DELETED desktop.txt on the phone")

        // Give the daemon time to fetch everything before the test process ends.
        delay(10_000)
    }

    companion object {
        private const val TAG = "ExitCriterion"
    }
}
