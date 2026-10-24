package org.shoalsync.android.folders

import org.shoalsync.ffi.File

/**
 * Which files of a folder may be removed from this phone because a device that keeps everything
 * holds them (ADR 0022, 0025). Confirmed copies are only tracked for files made on this phone,
 * so a confirmed file is always one of the phone's own.
 */
object Removable {
    /** Files on this phone that a full replica has confirmed holding: what clean up offers. */
    fun confirmed(files: List<File>): List<File> =
        files.filter { it.onDisk && it.confirmedCopies > 0u && !it.onlyHere }

    /**
     * What remove after backup removes: confirmed files added or changed at or after [sinceMs],
     * when the option was chosen. Files from before, and [kept] entries the user restored, are
     * left alone.
     */
    fun afterBackup(files: List<File>, sinceMs: Long, kept: Set<String> = emptySet()): List<File> =
        confirmed(files).filter { it.modifiedMs >= sinceMs && it.entry !in kept }
}

/** A phone folder's files that are only on other devices (ADR 0028). */
object OnOtherDevices {
    /** What the folder's "On your other devices" root lists, and what can be downloaded. */
    fun files(files: List<File>): List<File> = files.filterNot { it.onDisk }
}
