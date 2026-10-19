package org.tidefell.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.tidefell.android.folders.SyncChoice
import org.tidefell.ffi.Activity
import org.tidefell.ffi.ChangeKind
import org.tidefell.ffi.CheckLevel
import org.tidefell.ffi.Folder
import org.tidefell.ffi.FolderState
import org.tidefell.ffi.Operation
import org.tidefell.ffi.PathProblem
import org.tidefell.ffi.PeerState
import org.tidefell.ffi.ProblemKind

class StatusTest {
    private fun peer(connected: Boolean, relayed: Boolean? = null, rttMs: ULong? = null, error: String? = null) =
        PeerState("ab".repeat(32), "Laptop", connected, relayed, rttMs, null, error, null)

    @Test
    fun peers_say_how_they_are_connected() {
        assertEquals("Connected directly, 12 ms", peerText(peer(true, relayed = false, rttMs = 12u)))
        assertEquals("Connected through a relay", peerText(peer(true, relayed = true)))
        assertEquals("Not connected: timed out", peerText(peer(false, error = "timed out")))
        assertEquals("Not connected", peerText(peer(false)))
    }

    @Test
    fun one_file_is_not_files() {
        assertEquals("1 file", files(1))
        assertEquals("0 files", files(0))
        assertEquals("2 files", files(2UL.toLong()))
    }

    private fun folder(
        state: FolderState = FolderState.IDLE,
        error: String? = null,
        unavailable: String? = null,
        lowSpace: String? = null,
    ) = Folder(
        id = "ab".repeat(32),
        path = "/sdcard/Music",
        partialBudget = null,
        members = emptyList(),
        retentionDays = 30u,
        encryption = null,
        sync = SyncChoice.TWO_WAY.options,
        recycledFiles = 0u,
        recycledBytes = 0u,
        nextPurgeMs = null,
        onlyHere = 0u,
        paused = false,
        pinnedBytes = 0u,
        conflicts = 0u,
        state = state,
        error = error,
        pendingFiles = 0u,
        pendingBytes = 0u,
        lastScanMs = null,
        peers = emptyList(),
        problems = 0u,
        rebuild = null,
        unavailable = unavailable,
        heldDeletes = 0u,
        lowSpace = lowSpace,
    )

    @Test
    fun folders_say_why_they_do_not_sync() {
        assertEquals("Up to date", folderStateText(folder()))
        assertEquals("Not syncing: disk I/O error", folderStateText(folder(FolderState.ERROR, error = "disk I/O error")))
        val missing = "/sdcard/Music is missing; is its drive mounted?"
        assertEquals(
            "Not syncing: storage not available",
            folderStateText(folder(FolderState.ERROR, error = missing, unavailable = missing)),
        )
        assertEquals("Up to date · downloads paused, low on space", folderStateText(folder(lowSpace = "the folder's drive is low")))
    }

    @Test
    fun held_deletes_wait_for_a_decision() {
        assertEquals("240 files deleted here need a decision", heldDeletesLine(240u))
        assertEquals("1 file deleted here needs a decision", heldDeletesLine(1u))
        assert(heldDeletesText(240u).startsWith("240 files were deleted here at once, so the deletes wait for you."))
        assert(heldDeletesText(1u).startsWith("1 file was deleted here at once, so the delete waits for you."))
    }

    @Test
    fun problems_say_what_failed() {
        val problem = PathProblem("a.txt", Operation.WRITE, ProblemKind.PERMISSION_DENIED, "denied", 0, 2u)
        assertEquals("Write: permission denied", problemText(problem))
    }

    @Test
    fun activity_says_who_changed_what() {
        fun change(kind: ChangeKind, local: Boolean, oldPath: String? = null) =
            Activity(0, kind, "b.txt", oldPath, "ab".repeat(32), "Laptop", local)
        assertEquals("Added b.txt, by Laptop", activityText(change(ChangeKind.ADDED, false)))
        assertEquals("Renamed a.txt to b.txt, by this phone", activityText(change(ChangeKind.RENAMED, true, "a.txt")))
        assertEquals("b.txt was changed apart on two devices, by Laptop", activityText(change(ChangeKind.CONFLICT, false)))
    }

    @Test
    fun the_phones_checks() {
        val fine = androidChecks(
            notificationsAllowed = true,
            ignoringBatteryOptimizations = true,
            backgroundRestricted = false,
            hasPhoneFolders = true,
            allFilesAccess = true,
        )
        assertEquals(listOf("notifications", "background", "battery", "storage"), fine.map { it.name })
        assertEquals(setOf(CheckLevel.PASS), fine.map { it.level }.toSet())

        val restricted = androidChecks(
            notificationsAllowed = false,
            ignoringBatteryOptimizations = false,
            backgroundRestricted = true,
            hasPhoneFolders = false,
            allFilesAccess = false,
        )
        assertEquals(
            listOf(CheckLevel.WARNING, CheckLevel.FAILURE, CheckLevel.WARNING),
            restricted.map { it.level },
        )
        assert(restricted.all { it.advice != null })
    }
}
