package org.tidefell.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.tidefell.ffi.Activity
import org.tidefell.ffi.ChangeKind
import org.tidefell.ffi.CheckLevel
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
