package org.tidefell.android.folders

import org.tidefell.ffi.File

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
