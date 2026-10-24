package org.shoalsync.android

import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.shoalsync.android.folders.PhoneFolders
import org.shoalsync.android.folders.PhoneStorage
import org.shoalsync.android.folders.SyncChoice
import org.shoalsync.ffi.Operation
import org.shoalsync.ffi.Pairing
import org.shoalsync.ffi.PairingUpdate
import org.shoalsync.ffi.ProblemKind
import org.shoalsync.ffi.crashReport
import org.shoalsync.ffi.crashes
import org.shoalsync.ffi.deviceId
import org.shoalsync.ffi.dismissCrashes
import org.shoalsync.ffi.recordCrash
import java.io.File

/**
 * The exit criteria on an emulator against a real `shoald` on the host (see
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
 * ADR 0028: the phone folder is a documents root listing only the daemon's files that are not on
 * the phone. Opening one there downloads it into the phone folder, as does "Download to this phone".
 *
 * ADR 0030: the phone shows the daemon connected and what it received, who made each file and
 * what it applied; a file it cannot read is a problem until it can; and a recorded crash is
 * written up for a tracker and goes into a diagnostics bundle without full IDs or paths.
 *
 * The host reads the pairing code from logcat and approves it with `shoal pair`, and checks its
 * folders afterwards. The relay URL comes from the `shoalRelay` instrumentation argument.
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

    /** Names of the files the documents provider lists at the top of [folder]'s root. */
    private fun listed(resolver: android.content.ContentResolver, folder: String): List<String> =
        resolver.query(DocumentsContract.buildChildDocumentsUri(ShoalDocumentsProvider.AUTHORITY, "$folder/"), null, null, null, null)!!
            .use { cursor ->
                val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                buildList { while (cursor.moveToNext()) add(cursor.getString(name)) }
            }

    @Test
    fun pairJoinOpenSaveAndPhoneFolders() = runBlocking {
        val relay = InstrumentationRegistry.getArguments().getString("shoalRelay")
            ?: error("pass -Pandroid.testInstrumentationRunnerArguments.shoalRelay=<url>")
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

        // Pair: the host approves the code it finds in logcat.
        val pairing = Pairing.start(Peer.dataDir, Peer.settings.network(), "Emulator")
        Log.i(TAG, "PAIRING_CODE ${pairing.code()}")
        val confirming = withTimeout(120_000) { pairing.nextUpdate() }
        assertTrue("$confirming", confirming is PairingUpdate.Confirming)
        val paired = withTimeout(120_000) { pairing.nextUpdate() }
        assertTrue("$paired", paired is PairingUpdate.Paired)
        pairing.close()
        assertTrue(Peer.paired)
        // Only the Keystore-sealed device key is in the app's storage (ADR 0038).
        assertFalse("no plain device key", File(Peer.dataDir, "device.key").exists())
        assertTrue("a sealed device key", File(Peer.dataDir, "device.key.sealed").exists())
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
        val uri = DocumentsContract.buildDocumentUri(ShoalDocumentsProvider.AUTHORITY, "${photos.id}/beach.jpg")
        assertEquals("sand and sea", resolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })

        // Saving a new document syncs it to the daemon; the host checks its folder afterwards.
        val parent = DocumentsContract.buildDocumentUri(ShoalDocumentsProvider.AUTHORITY, "${photos.id}/")
        val created = DocumentsContract.createDocument(resolver, parent, "text/plain", "from-android.txt")!!
        resolver.openOutputStream(created, "wt")!!.use { it.write("hello from android".toByteArray()) }
        eventually("the saved file is recorded") { node.files(photos.id).any { it.path == "from-android.txt" && it.onDisk } }
        eventually("the daemon is connected") { node.peers().isNotEmpty() }
        Log.i(TAG, "SAVED from-android.txt")

        // ADR 0030: status, who made each file, and what the phone applied.
        val daemon = node.peerStates().single { it.connected }
        assertTrue("received the daemon's file", node.transfer().received > 0uL)
        assertEquals(daemon.name, node.files(photos.id).single { it.path == "beach.jpg" }.authorName)
        eventually("the activity log lists both files") {
            val activity = node.activity(photos.id, 50u)
            activity.any { it.path == "beach.jpg" && !it.local } && activity.any { it.path == "from-android.txt" && it.local }
        }

        // A file the phone cannot read is a problem until it can, and is not taken for deleted.
        val photosDir = File(node.folders().single { it.id == photos.id }.path)
        val locked = File(photosDir, "locked.txt").apply { writeText("secret") }
        assertTrue(locked.setReadable(false, false))
        node.scan(photos.id)
        val problem = node.pathProblems(photos.id).single { it.path == "locked.txt" }
        assertEquals(Operation.SCAN, problem.operation)
        assertEquals(ProblemKind.PERMISSION_DENIED, problem.kind)
        assertEquals(1uL, node.folders().single { it.id == photos.id }.problems)
        assertTrue(locked.setReadable(true, true))
        node.scan(photos.id)
        assertTrue("cleared once it can be read", node.pathProblems(photos.id).isEmpty())
        assertTrue(locked.delete())
        node.scan(photos.id)

        // A crash: reported as an issue for the tracker, and in a diagnostics bundle with names
        // hashed and IDs shortened.
        recordCrash(Peer.dataDir, "a test crash", "java.lang.IllegalStateException: test\n\tat ExitCriterionTest")
        val crash = crashes(Peer.dataDir).last { !it.seen }
        val report = crashReport(Peer.dataDir, crash.id, "https://example.org/issues")
        assertEquals("Crash: a test crash", report.title)
        assertTrue(report.url!!.startsWith("https://example.org/issues/new?title=Crash%3A%20a%20test%20crash"))
        val bundle = node.diagnostics(File(context.cacheDir, "diagnostics").path, false, "{\"android_api\": 34}")
        val names = bundle.entries.map { it.name }
        for (name in listOf("summary.json", "status.json", "problems.json", "doctor.json", "crashes/${crash.id}")) {
            assertTrue("$name in $names", name in names)
        }
        assertTrue(File(bundle.path).length() > 0)
        val text = bundle.entries.joinToString("\n") { it.text }
        assertFalse("no full device ID", text.contains(node.status().device))
        assertFalse("no folder path", text.contains(Peer.foldersDir.path))
        // The app's own messages are in the engine's log files with the engine's (AppLog).
        assertTrue("the app's log in the bundle", text.contains("node started"))
        dismissCrashes(Peer.dataDir)
        assertTrue(crashes(Peer.dataDir).all { it.seen })
        Log.i(TAG, "DIAGNOSED status, problems, activity, crash report and bundle")

        // Camera, at a folder on the phone, with remove after backup. All files access is granted
        // the way a user would in Settings.
        instrumentation.uiAutomation
            .executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
            .close()
        eventually("All files access") { PhoneStorage.hasAccess() }
        val dir = File(Environment.getExternalStorageDirectory(), "DCIM/ShoalTest")
        dir.deleteRecursively()
        val camera = PhoneFolders.join(cameraOffer, dir, SyncChoice.REMOVE_AFTER_BACKUP)
        assertEquals(SyncChoice.REMOVE_AFTER_BACKUP, PhoneFolders.choice(camera))
        val roots = resolver.query(DocumentsContract.buildRootsUri(ShoalDocumentsProvider.AUTHORITY), null, null, null, null)!!
        roots.use { assertEquals("folders on the phone are document roots too", 2, it.count) }

        // The daemon's file is listed but never downloaded; the documents root lists it.
        eventually("the daemon's camera file is listed") { node.files(camera.id).any { it.path == "desktop.txt" } }
        assertEquals(listOf("desktop.txt"), listed(resolver, camera.id))

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

        // ADR 0028: back in the documents root, which lists only files off the phone. Opening it
        // there downloads it into the phone folder.
        assertEquals(listOf("desktop.txt"), listed(resolver, camera.id))
        val desktopUri = DocumentsContract.buildDocumentUri(ShoalDocumentsProvider.AUTHORITY, "${camera.id}/desktop.txt")
        assertEquals("made on the laptop", resolver.openInputStream(desktopUri)!!.use { it.readBytes().decodeToString() })
        assertTrue("downloaded into the phone folder", desktop.exists())
        assertEquals(emptyList<String>(), listed(resolver, camera.id))

        // "Download to this phone" does the same.
        assertTrue(desktop.delete())
        node.scan(camera.id)
        PhoneFolders.download(camera.id, "desktop.txt")
        assertEquals("made on the laptop", desktop.readText())
        assertTrue(node.files(camera.id).single { it.path == "desktop.txt" }.onDisk)

        // Give the daemon time to fetch everything before the test process ends.
        delay(10_000)
    }

    /** Phase 5l: a device key left as a plain file by an earlier version moves into the Keystore. */
    @Test
    fun aPlainDeviceKeyMovesIntoTheKeystore() = runBlocking {
        Peer.init(InstrumentationRegistry.getInstrumentation().targetContext)
        Peer.stop()
        val dir = File(Peer.dataDir)
        clearDataDir()
        dir.mkdirs()
        // As an earlier version left it: the seed in hex, in a file of its own.
        File(dir, "device.key").writeText("shoal-device-key 1\n${"07".repeat(SEED_BYTES)}\n")

        // The same device, with the key now sealed and the plain file gone.
        assertEquals(SEED_SEVENS_ID, deviceId(Peer.dataDir))
        assertFalse("no plain device key", File(dir, "device.key").exists())
        val sealed = File(dir, "device.key.sealed").readBytes().toList()
        assertFalse("the seed is not in the sealed file", sealed.windowed(SEED_BYTES).any { w -> w.all { it == SEVEN } })
        assertEquals(SEED_SEVENS_ID, deviceId(Peer.dataDir))
        clearDataDir()
        Unit
    }

    companion object {
        private const val TAG = "ExitCriterion"
        private const val SEED_BYTES = 32
        private const val SEVEN: Byte = 7

        /** The Ed25519 public key of a seed of 32 sevens. */
        private const val SEED_SEVENS_ID = "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c"
    }
}
