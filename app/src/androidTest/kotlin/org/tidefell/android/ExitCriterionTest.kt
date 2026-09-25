package org.tidefell.android

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import org.tidefell.android.backup.CameraRollBackup
import org.tidefell.android.backup.RemoveOriginals
import org.tidefell.android.ui.join
import org.tidefell.ffi.Pairing
import org.tidefell.ffi.PairingUpdate

/**
 * The Phase 4 exit criterion, on an emulator against a real `tidefelld` on the host (see
 * scripts/ci-exit-criterion.sh): pair, join a folder, list it, open a file on demand through
 * the documents provider, and save a file that syncs back to the daemon. Then the Phase 5 checks:
 * a photo in the camera roll is backed up into the folder once, and retention can be changed.
 * Then Phase 5b: with "remove from phone after backup" on, the photo becomes eligible for removal
 * only once the daemon, a full replica, confirms it holds the backed-up copy, and stops being
 * eligible if it is edited afterwards, when it is backed up again.
 *
 * The host reads the pairing code from logcat and approves it with `tidefell pair`. The relay URL
 * comes from the `tidefellRelay` instrumentation argument.
 */
@RunWith(AndroidJUnit4::class)
class ExitCriterionTest {
    /**
     * Writes [text] as the content of the pending item [photo] and publishes it, as a camera app
     * does, so `MediaStore` has its size and modification time before anything reads them.
     */
    private fun writePhoto(resolver: ContentResolver, photo: Uri, text: String) {
        resolver.openOutputStream(photo, "wt")!!.use { it.write(text.toByteArray()) }
        resolver.update(photo, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

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
        CameraRollBackup.Ledger.of(context).clear()
        RemoveOriginals.Candidates.of(context).clear()
        Peer.settings.removeOriginals = true

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

        // Camera-roll backup: a new photo is copied into Camera/<year>/<month>/, once.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_IMAGES)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_MEDIA_VIDEO)
        val photo = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "tidefell-test.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tidefell")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        )!!
        writePhoto(resolver, photo, "photo ${System.nanoTime()}")
        val candidates = RemoveOriginals.Candidates.of(context)
        val written = CameraRollBackup.run(context, node, offer.id, includeVideos = true, candidates = candidates)
        assertTrue("backed up $written", written >= 1)
        val backedUp = node.files(offer.id).map { it.path }
        val photoPath = backedUp.single { Regex("""Camera/\d{4}/\d{2}/tidefell-test\.jpg""").matches(it) }
        // Just written: no full replica can have confirmed it, so the original stays.
        assertTrue("only here", node.files(offer.id).single { it.path == photoPath }.onlyHere)
        assertTrue("not eligible before a confirmation", RemoveOriginals.eligible(context, node, offer.id).none { it.path == photoPath })
        assertEquals("a second run finds nothing new", 0, CameraRollBackup.run(context, node, offer.id, includeVideos = true))
        Log.i(TAG, "BACKED UP tidefell-test.jpg")

        // Removing originals (ADR 0020, 0022): eligible once the daemon, a full replica, confirms it.
        assertTrue("recorded while the setting is on", candidates.all().values.any { it?.path == photoPath })
        eventually("the daemon confirms the backed-up photo") {
            RemoveOriginals.eligible(context, node, offer.id).any { it.path == photoPath }
        }
        val file = node.files(offer.id).single { it.path == photoPath }
        assertTrue("confirmed: $file", file.confirmedCopies >= 1u && !file.onlyHere)
        assertEquals("laptop", node.copies(offer.id, photoPath).single().name)
        val eligible = RemoveOriginals.eligible(context, node, offer.id).filter { it.path == photoPath }
        // Deleting goes through Android's own confirmation; building the request must work.
        if (RemoveOriginals.supported) RemoveOriginals.deleteRequest(context, eligible)
        Log.i(TAG, "ELIGIBLE tidefell-test.jpg")

        // An original edited after its backup is not what the folder holds: it is no longer
        // offered, and the next backup uploads the edited version beside the first.
        resolver.update(photo, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 1) }, null, null)
        writePhoto(resolver, photo, "edited photo ${System.nanoTime()}, now longer than before")
        eventually("the edited photo is no longer offered") {
            RemoveOriginals.eligible(context, node, offer.id).none { it.path == photoPath }
        }
        val again = CameraRollBackup.run(context, node, offer.id, includeVideos = true, candidates = candidates)
        assertTrue("the edited photo is backed up again: $again", again >= 1)
        val edited = node.files(offer.id).map { it.path }
            .single { Regex("""Camera/\d{4}/\d{2}/tidefell-test \(2\)\.jpg""").matches(it) }
        assertTrue("the edited copy is a new candidate", candidates.all().values.any { it?.path == edited })
        assertTrue("the first copy is no longer one", candidates.all().values.none { it?.path == photoPath })
        Log.i(TAG, "REBACKED tidefell-test.jpg")

        // Retention is per device.
        assertEquals(7u, node.setRetention(offer.id, 7u).retentionDays)
        // Give the daemon time to fetch it before the test process ends.
        delay(10_000)
    }

    companion object {
        private const val TAG = "ExitCriterion"
    }
}
