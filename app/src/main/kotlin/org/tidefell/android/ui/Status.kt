package org.tidefell.android.ui

import org.tidefell.ffi.Activity
import org.tidefell.ffi.ChangeKind
import org.tidefell.ffi.Check
import org.tidefell.ffi.CheckLevel
import org.tidefell.ffi.Folder
import org.tidefell.ffi.FolderState
import org.tidefell.ffi.Operation
import org.tidefell.ffi.PathProblem
import org.tidefell.ffi.PeerState
import org.tidefell.ffi.ProblemKind

/** "1 file" or "[count] files". */
fun files(count: Number): String = if (count.toLong() == 1L) "1 file" else "$count files"

/** What a folder is doing, in a few words: "Syncing: 3 files to fetch" (ADR 0030). */
fun folderStateText(folder: Folder): String {
    val state = when (folder.state) {
        FolderState.IDLE -> "Up to date"
        FolderState.SCANNING -> "Looking for changes"
        FolderState.SYNCING -> "Syncing"
        FolderState.PAUSED -> "Paused"
        // Its metadata is started over (ADR 0034): files here are matched to what a device sends.
        FolderState.REBUILDING -> {
            val waitingFor = if (folder.rebuild?.waitingFor.isNullOrEmpty()) "a device sharing it to connect" else "your other devices"
            "Rebuilding: waiting for $waitingFor"
        }
        FolderState.ERROR -> "Not syncing: ${folder.error ?: "an error"}"
    }
    val pending = if (folder.pendingFiles > 0UL && folder.state != FolderState.ERROR) {
        ": ${files(folder.pendingFiles.toLong())} to fetch"
    } else {
        ""
    }
    val problems = if (folder.problems > 0UL) " · ${files(folder.problems.toLong())} with problems" else ""
    return state + pending + problems
}

/** What failed for a path and why, such as "Write: permission denied". */
fun problemText(problem: PathProblem): String {
    val operation = when (problem.operation) {
        Operation.SCAN -> "Read"
        Operation.FETCH -> "Fetch"
        Operation.WRITE -> "Write"
        Operation.DELETE -> "Delete"
    }
    val kind = when (problem.kind) {
        ProblemKind.PERMISSION_DENIED -> "permission denied"
        ProblemKind.DISK_FULL -> "no space left"
        ProblemKind.CONTENT_UNAVAILABLE -> "no connected device has it"
        ProblemKind.NAME_NOT_ALLOWED -> "name not allowed on this phone"
        ProblemKind.CHANGED_WHILE_WRITING -> "changed while in use"
        ProblemKind.OTHER -> "failed"
    }
    return "$operation: $kind"
}

/** One change a folder applied: "Renamed a.txt to b.txt, by Laptop". */
fun activityText(activity: Activity): String {
    val what = when (activity.kind) {
        ChangeKind.ADDED -> "Added ${activity.path}"
        ChangeKind.CHANGED -> "Changed ${activity.path}"
        ChangeKind.DELETED -> "Deleted ${activity.path}"
        ChangeKind.RENAMED -> "Renamed ${activity.oldPath ?: "a file"} to ${activity.path}"
        ChangeKind.RESTORED -> "Restored ${activity.path}"
        ChangeKind.CONFLICT -> "${activity.path} was changed apart on two devices"
    }
    return "$what, by ${if (activity.local) "this phone" else activity.deviceName}"
}

/** How a device is connected: "Connected directly, 12 ms" or why it is not. */
fun peerText(peer: PeerState): String =
    if (peer.connected) {
        val path = when (peer.relayed) {
            true -> "through a relay"
            false -> "directly"
            null -> ""
        }
        listOfNotNull("Connected $path".trim(), peer.rttMs?.let { "$it ms" }).joinToString(", ")
    } else {
        listOfNotNull("Not connected", peer.lastError).joinToString(": ")
    }

/**
 * The phone's own checks for the doctor (ADR 0030), from what Android reports: whether
 * notifications, running in the background and, for folders on the phone, All files access are
 * allowed.
 */
fun androidChecks(
    notificationsAllowed: Boolean,
    ignoringBatteryOptimizations: Boolean,
    backgroundRestricted: Boolean,
    hasPhoneFolders: Boolean,
    allFilesAccess: Boolean,
): List<Check> = buildList {
    add(
        if (notificationsAllowed) {
            Check("notifications", CheckLevel.PASS, "Notifications are allowed", null)
        } else {
            Check(
                "notifications",
                CheckLevel.WARNING,
                "Notifications are off",
                "Allow notifications for Tidefell to hear about conflicts and to keep syncing visible.",
            )
        },
    )
    add(
        if (backgroundRestricted) {
            Check(
                "background",
                CheckLevel.FAILURE,
                "Android restricts Tidefell in the background",
                "In the app's battery settings, allow background use, or folders only sync while Tidefell is open.",
            )
        } else {
            Check("background", CheckLevel.PASS, "Tidefell may run in the background", null)
        },
    )
    add(
        if (ignoringBatteryOptimizations) {
            Check("battery", CheckLevel.PASS, "Battery optimisation does not delay syncing", null)
        } else {
            Check(
                "battery",
                CheckLevel.WARNING,
                "Battery optimisation may delay syncing",
                "Set Tidefell's battery use to unrestricted for syncing to keep up while the phone is idle.",
            )
        },
    )
    if (hasPhoneFolders) {
        add(
            if (allFilesAccess) {
                Check("storage", CheckLevel.PASS, "Folders on the phone can be read and written", null)
            } else {
                Check(
                    "storage",
                    CheckLevel.FAILURE,
                    "All files access is off, so folders on the phone cannot sync",
                    "Allow All files access for Tidefell in Settings.",
                )
            },
        )
    }
}
