package org.tidefell.android.folders

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tidefell.ffi.Direction
import org.tidefell.ffi.LocalDeletes
import org.tidefell.ffi.SyncOptions
import org.tidefell.ffi.File as FolderFile

class FoldersTest {
    private fun file(
        path: String,
        modifiedMs: Long = 1_000,
        onDisk: Boolean = true,
        confirmed: UInt = 1u,
        onlyHere: Boolean = false,
    ) = FolderFile(
        entry = path,
        path = path,
        size = 10u,
        modifiedMs = modifiedMs,
        onDisk = onDisk,
        conflicts = 0u,
        confirmedCopies = confirmed,
        onlyHere = onlyHere,
    )

    @Test
    fun choices_are_offered_in_order() {
        assertEquals(
            listOf(SyncChoice.BACKUP_ONLY, SyncChoice.TWO_WAY, SyncChoice.REMOVE_AFTER_BACKUP),
            SyncChoice.forPhoneFolders,
        )
    }

    @Test
    fun choices_map_to_engine_settings_and_back() {
        val sendOnly = SyncOptions(Direction.SEND_ONLY, LocalDeletes.THIS_DEVICE)
        assertEquals(sendOnly, SyncChoice.BACKUP_ONLY.options)
        assertEquals(sendOnly, SyncChoice.REMOVE_AFTER_BACKUP.options)
        assertEquals(SyncOptions(Direction.BOTH, LocalDeletes.THIS_DEVICE), SyncChoice.TWO_WAY.options)
        assertEquals(SyncOptions(Direction.BOTH, LocalDeletes.EVERYWHERE), SyncChoice.FILES_ON_DEMAND.options)

        for (choice in SyncChoice.forPhoneFolders) {
            val since = if (choice == SyncChoice.REMOVE_AFTER_BACKUP) 5L else null
            assertEquals(choice, SyncChoice.of(choice.options, inAppStorage = false, removingSince = since))
        }
        assertEquals(
            SyncChoice.FILES_ON_DEMAND,
            SyncChoice.of(SyncChoice.FILES_ON_DEMAND.options, inAppStorage = true, removingSince = null),
        )
    }

    @Test
    fun clean_up_offers_only_confirmed_files_on_the_phone() {
        val files = listOf(
            file("safe.jpg"),
            file("placeholder.jpg", onDisk = false),
            file("unconfirmed.jpg", confirmed = 0u, onlyHere = true),
            file("elsewhere.jpg", confirmed = 0u),
        )
        assertEquals(listOf("safe.jpg"), Removable.confirmed(files).map { it.path })
    }

    @Test
    fun remove_after_backup_leaves_older_files_alone() {
        val files = listOf(
            file("before.jpg", modifiedMs = 999),
            file("at.jpg", modifiedMs = 1_000),
            file("after.jpg", modifiedMs = 2_000),
            file("after-unconfirmed.jpg", modifiedMs = 2_000, confirmed = 0u, onlyHere = true),
        )
        assertEquals(listOf("at.jpg", "after.jpg"), Removable.afterBackup(files, sinceMs = 1_000).map { it.path })
        assertEquals(
            "restored files are kept",
            listOf("after.jpg"),
            Removable.afterBackup(files, sinceMs = 1_000, kept = setOf("at.jpg")).map { it.path },
        )
    }

    @Test
    fun the_camera_folder_comes_first() {
        val internal = PhoneStorage.Place("Internal storage", File("/storage/emulated/0"))
        val card = PhoneStorage.Place("SD card", File("/storage/1234-5678"))
        val camera = PhoneStorage.Place("Camera", File("/storage/emulated/0/DCIM/Camera"))
        assertEquals(listOf(camera, internal, card), PhoneStorage.ordered(camera, listOf(internal, card)))
        assertEquals(listOf(internal, card), PhoneStorage.ordered(null, listOf(internal, card)))
    }

    @Test
    fun subfolders_are_sorted_and_skip_hidden_ones() {
        val root = Files.createTempDirectory("folders").toFile()
        try {
            listOf("b", "A", ".tidefell", ".thumbnails").forEach { File(root, it).mkdir() }
            File(root, "file.txt").writeText("not a folder")
            assertEquals(listOf("A", "b"), PhoneStorage.subfolders(root).map { it.name })
            assertTrue(PhoneStorage.isWithin(File(root, "A"), root))
            assertTrue(PhoneStorage.isWithin(root, root))
            assertFalse(PhoneStorage.isWithin(File(root.path + "-other"), root))
        } finally {
            root.deleteRecursively()
        }
    }
}
