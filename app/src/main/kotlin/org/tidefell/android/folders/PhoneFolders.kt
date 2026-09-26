package org.tidefell.android.folders

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tidefell.android.Peer
import org.tidefell.ffi.Folder
import org.tidefell.ffi.FolderOffer
import org.tidefell.ffi.Node
import org.tidefell.ffi.Recycled

/** Adding folders, choosing how they sync, and removing files after backup (ADR 0025). */
object PhoneFolders {
    private const val TAG = "PhoneFolders"
    private const val DEFAULT_RETENTION_DAYS = 30u

    /** True for a folder in the app's own storage, reached through the documents provider. */
    fun inAppStorage(folder: Folder): Boolean = PhoneStorage.isWithin(File(folder.path), Peer.foldersDir)

    /** How [folder] syncs on this phone. */
    fun choice(folder: Folder): SyncChoice =
        SyncChoice.of(folder.sync, inAppStorage(folder), Peer.settings.removingSince(folder.id))

    /** Creates a new folder at [dir] on this phone, shared with this phone's user. */
    suspend fun create(dir: File, choice: SyncChoice): Folder {
        val node = Peer.start()
        return withContext(Dispatchers.IO) {
            dir.mkdirs()
            node.createFolder(dir.path, null, DEFAULT_RETENTION_DAYS, choice.options)
        }.also { remember(it.id, choice) }
    }

    /**
     * Joins [offer] at [dir] with [choice], or in app storage with files on demand when [dir] is
     * null, the way folders were joined before phone folders.
     */
    suspend fun join(offer: FolderOffer, dir: File?, choice: SyncChoice): Folder {
        val node = Peer.start()
        val folder = withContext(Dispatchers.IO) {
            if (dir == null) {
                node.joinFolder(
                    offer.id,
                    File(Peer.foldersDir, offer.name).path,
                    Peer.settings.budgetBytes.toULong(),
                    offer.members,
                    offer.retentionDays,
                    SyncChoice.FILES_ON_DEMAND.options,
                )
            } else {
                dir.mkdirs()
                node.joinFolder(offer.id, dir.path, null, offer.members, offer.retentionDays, choice.options)
            }
        }
        remember(folder.id, if (dir == null) SyncChoice.FILES_ON_DEMAND else choice)
        return folder
    }

    /** Changes how [folder] syncs. Choosing remove after backup applies to files from now on. */
    suspend fun setChoice(folder: Folder, choice: SyncChoice) {
        require(!inAppStorage(folder) && choice != SyncChoice.FILES_ON_DEMAND) { "not a phone folder" }
        val node = Peer.start()
        withContext(Dispatchers.IO) { node.setSyncOptions(folder.id, choice.options) }
        remember(folder.id, choice)
    }

    private fun remember(folder: String, choice: SyncChoice) {
        if (choice != SyncChoice.FILES_ON_DEMAND) FolderSync.schedule(Peer.context, hasPhoneFolders = true)
        val settings = Peer.settings
        when {
            choice != SyncChoice.REMOVE_AFTER_BACKUP -> settings.setRemovingSince(folder, null)
            settings.removingSince(folder) == null -> settings.setRemovingSince(folder, System.currentTimeMillis())
        }
    }

    /**
     * Removes the files of remove-after-backup folders that a full replica holds, moving them to
     * this phone's recycle bin. Returns how many were removed.
     */
    suspend fun removeBackedUp(node: Node): Int = withContext(Dispatchers.IO) {
        var removed = 0
        for (folder in node.folders()) {
            val since = Peer.settings.removingSince(folder.id) ?: continue
            if (inAppStorage(folder)) continue
            for (file in Removable.afterBackup(node.files(folder.id), since, Peer.settings.kept(folder.id))) {
                runCatching { node.removeHere(folder.id, file.path) }
                    .onSuccess { removed++ }
                    // Edited since the last scan, or already gone: the next pass looks again.
                    .onFailure { Log.i(TAG, "not removing ${file.path} yet: ${it.message}") }
            }
        }
        removed
    }

    /**
     * Restores [item] from [folder]'s recycle bin. A file removed from this phone stays on it
     * afterwards: remove after backup does not take it again.
     */
    suspend fun restore(folder: String, item: Recycled) {
        if (item.hereOnly) Peer.settings.keep(folder, item.entry)
        Peer.start().restore(folder, item.entry)
    }

    /** Moves [paths] of [folder] to this phone's recycle bin; for clean up. */
    suspend fun removeHere(folder: String, paths: List<String>): Int {
        val node = Peer.start()
        return withContext(Dispatchers.IO) {
            paths.count { path -> runCatching { node.removeHere(folder, path) }.isSuccess }
        }
    }
}
