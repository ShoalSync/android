package org.tidefell.android.folders

import org.tidefell.ffi.Direction
import org.tidefell.ffi.LocalDeletes
import org.tidefell.ffi.SyncOptions

/**
 * How a folder on the phone syncs (ADR 0025), in the order the app offers them. Folders in app
 * storage always use [FILES_ON_DEMAND].
 */
enum class SyncChoice(val title: String, val summary: String) {
    BACKUP_ONLY(
        "Backup only",
        "Files on this phone are copied to your other devices. Nothing is downloaded, and nothing " +
            "on the phone is changed or deleted.",
    ),
    TWO_WAY(
        "Two-way sync",
        "Changes go both ways. Deleting a file on this phone removes it from this phone only; " +
            "your other devices keep it.",
    ),
    REMOVE_AFTER_BACKUP(
        "Remove after backup",
        "Like backup only, and files are moved to this phone's recycle bin once a device that " +
            "keeps everything, such as a NAS, holds them.",
    ),

    /** A folder in app storage: other devices' files are fetched when opened (ADR 0016). */
    FILES_ON_DEMAND(
        "Files on demand",
        "Every file is listed, and other devices' files are downloaded when you open them.",
    ),
    ;

    /** The engine's settings for this choice. */
    val options: SyncOptions
        get() = when (this) {
            BACKUP_ONLY, REMOVE_AFTER_BACKUP -> SyncOptions(Direction.SEND_ONLY, LocalDeletes.THIS_DEVICE)
            TWO_WAY -> SyncOptions(Direction.BOTH, LocalDeletes.THIS_DEVICE)
            FILES_ON_DEMAND -> SyncOptions(Direction.BOTH, LocalDeletes.EVERYWHERE)
        }

    companion object {
        /** The choices offered for a folder on the phone, in order. */
        val forPhoneFolders = listOf(BACKUP_ONLY, TWO_WAY, REMOVE_AFTER_BACKUP)

        /**
         * The choice a folder's settings stand for. Backup only and remove after backup share
         * engine settings; [removingSince] tells them apart.
         */
        fun of(options: SyncOptions, inAppStorage: Boolean, removingSince: Long?): SyncChoice = when {
            inAppStorage -> FILES_ON_DEMAND
            options.direction == Direction.BOTH -> TWO_WAY
            removingSince != null -> REMOVE_AFTER_BACKUP
            else -> BACKUP_ONLY
        }
    }
}
