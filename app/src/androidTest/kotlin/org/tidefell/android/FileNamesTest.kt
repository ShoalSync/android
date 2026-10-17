package org.tidefell.android

import android.os.Environment
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
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.PhoneStorage
import org.tidefell.android.folders.SyncChoice
import org.tidefell.ffi.FolderOffer
import org.tidefell.ffi.Pairing
import org.tidefell.ffi.PairingUpdate
import org.tidefell.ffi.ProblemKind
import java.io.File

/**
 * File names across filesystems (ADR 0033), on an emulator against a real `tidefelld` on the host
 * (see scripts/file-names.sh), which shares two folders: Names, with `Photo.jpg` and `photo.jpg`
 * holding different content, and Card, with `a:b.txt` and `ok.txt`.
 *
 * Names goes in a phone folder in shared storage, which matches names without regard to case:
 * both files must survive there under distinct names, and the daemon keeps both paths unchanged
 * (the host checks). Card goes in a phone folder on a FAT volume, which refuses `:`: `a:b.txt` is
 * listed as a problem instead of failing on every pass, and `ok.txt` arrives.
 *
 * What the phone did is logged as OBSERVED lines before anything is asserted, so a run against a
 * release without ADR 0033 records what happens there. The relay URL comes from the
 * `tidefellRelay` instrumentation argument, and the FAT volume's path from `tidefellFatVolume`.
 */
@RunWith(AndroidJUnit4::class)
class FileNamesTest {
    private suspend fun eventually(what: String, seconds: Int = 90, check: suspend () -> Boolean) {
        repeat(seconds * 10) {
            if (runCatching { check() }.getOrDefault(false)) return
            delay(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** The files in [dir] and what each holds. */
    private fun contents(dir: File): Map<String, String> =
        dir.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readText() }

    /** Starts afresh, pairs with the host's daemon and returns the folders it offers. */
    private suspend fun pair(relay: String): List<FolderOffer> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        Peer.init(context)
        Peer.stop()
        clearDataDir()
        Peer.foldersDir.deleteRecursively()
        Peer.settings.relay = relay
        Peer.settings.relayToken = InstrumentationRegistry.getArguments().getString("tidefellRelayToken") ?: ""
        Peer.settings.addressLookup = false
        Peer.settings.localDiscovery = false

        val pairing = Pairing.start(Peer.dataDir, Peer.settings.network(), "Emulator")
        Log.i(TAG, "PAIRING_CODE ${pairing.code()}")
        val confirming = withTimeout(120_000) { pairing.nextUpdate() }
        assertTrue("$confirming", confirming is PairingUpdate.Confirming)
        val paired = withTimeout(120_000) { pairing.nextUpdate() }
        assertTrue("$paired", paired is PairingUpdate.Paired)
        pairing.close()

        instrumentation.uiAutomation
            .executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
            .close()
        eventually("All files access") { PhoneStorage.hasAccess() }
        return (paired as PairingUpdate.Paired).folders
    }

    @Test
    fun namesThatFoldTogetherOrCannotBeStored(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val relay = arguments.getString("tidefellRelay")
            ?: error("pass -Pandroid.testInstrumentationRunnerArguments.tidefellRelay=<url>")
        val fat = arguments.getString("tidefellFatVolume")
            ?: error("pass -Pandroid.testInstrumentationRunnerArguments.tidefellFatVolume=<path>")
        val offers = pair(relay)
        val namesOffer = offers.single { it.name == "Names" }
        val cardOffer = offers.single { it.name == "Card" }

        // Names, in shared storage.
        val namesDir = File(Environment.getExternalStorageDirectory(), "Documents/TidefellNames")
        namesDir.deleteRecursively()
        val names = PhoneFolders.join(namesOffer, namesDir, SyncChoice.TWO_WAY)
        // Card, on the FAT volume.
        val cardDir = File(fat, "TidefellCard")
        cardDir.deleteRecursively()
        val card = PhoneFolders.join(cardOffer, cardDir, SyncChoice.TWO_WAY)
        val node = Peer.start()

        eventually("the daemon's files are listed") {
            node.files(names.id).count { it.path.endsWith(".jpg") } >= 2 &&
                node.files(card.id).any { it.path == "ok.txt" }
        }
        eventually("ok.txt arrives on the card") { File(cardDir, "ok.txt").exists() }
        // Several passes, as a phone that keeps running would make.
        repeat(3) {
            node.scan(names.id)
            node.scan(card.id)
            delay(3_000)
        }

        Log.i(TAG, "OBSERVED Names on the phone: ${contents(namesDir)}")
        Log.i(TAG, "OBSERVED Names as the phone lists it: ${node.files(names.id).map { "${it.path} on disk ${it.onDisk}" }}")
        Log.i(TAG, "OBSERVED Names problems: ${node.pathProblems(names.id).map { "${it.path} ${it.kind} ${it.message}" }}")
        Log.i(TAG, "OBSERVED Card on the phone: ${contents(cardDir)}")
        Log.i(TAG, "OBSERVED Card as the phone lists it: ${node.files(card.id).map { "${it.path} on disk ${it.onDisk}" }}")
        Log.i(TAG, "OBSERVED Card problems: ${node.pathProblems(card.id).map { "${it.path} ${it.kind} ${it.message}" }}")

        // Both photos survive under distinct names, and the phone still lists both paths.
        val photos = contents(namesDir)
        assertEquals("both photos on the phone: $photos", setOf("upper", "lower"), photos.values.toSet())
        assertEquals(2, photos.size)
        // Listed where they are on the phone: one under its own name, the other as a conflict
        // copy, while every other device keeps both names (the host checks the daemon's).
        val listed = node.files(names.id).map { it.path }.filter { it.endsWith(".jpg") }
        assertEquals("both listed: $listed", 2, listed.size)
        assertTrue("one keeps its name: $listed", listed.any { it == "Photo.jpg" || it == "photo.jpg" })
        assertTrue("one is a conflict copy: $listed", listed.any { it.contains("(conflict ") })
        assertTrue("no problems: ${node.pathProblems(names.id)}", node.pathProblems(names.id).isEmpty())

        // The name FAT refuses is listed, as not on the phone, with a problem saying why.
        assertFalse(File(cardDir, "a:b.txt").exists())
        assertTrue(node.files(card.id).any { it.path == "a:b.txt" && !it.onDisk })
        val problem = node.pathProblems(card.id).single()
        assertEquals("a:b.txt", problem.path)
        assertEquals(ProblemKind.NAME_NOT_ALLOWED, problem.kind)
        Log.i(TAG, "NAMES survived and a:b.txt is a problem")

        // Renamed on all devices to a name the card can keep, it arrives and the problem goes.
        node.rename(card.id, "a:b.txt", "a-b.txt")
        eventually("a-b.txt arrives on the card") { File(cardDir, "a-b.txt").readText() == "colon" }
        eventually("the problem goes") { node.pathProblems(card.id).isEmpty() }
        Log.i(TAG, "RENAMED a:b.txt to a-b.txt")
    }

    companion object {
        private const val TAG = "FileNames"
    }
}
