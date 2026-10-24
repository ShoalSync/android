package org.shoalsync.android.ui

import kotlinx.coroutines.flow.StateFlow
import org.shoalsync.android.folders.SyncChoice
import org.shoalsync.ffi.Activity
import org.shoalsync.ffi.Check
import org.shoalsync.ffi.Crash
import org.shoalsync.ffi.DeviceStatus
import org.shoalsync.ffi.DiagnosticsBundle
import org.shoalsync.ffi.Folder
import org.shoalsync.ffi.FolderOffer
import org.shoalsync.ffi.HeldWithdrawals
import org.shoalsync.ffi.PathProblem
import org.shoalsync.ffi.PeerState
import org.shoalsync.ffi.Recycled
import org.shoalsync.ffi.StorageDropResult
import org.shoalsync.ffi.Transfer
import org.shoalsync.ffi.Version
import java.io.File
import org.shoalsync.ffi.File as SyncedFile

// What each screen reads and does. The view models implement these on the phone; screenshot
// tests implement them with fixed data, so every screen can be drawn without a running node.

/** The home screen: folders, their files, recycle bins and versions, and the pages above them. */
@Suppress("TooManyFunctions") // One per action on the screen, as on HomeViewModel.
interface HomeModel {
    /** True once the node runs. */
    val started: StateFlow<Boolean>
    val page: StateFlow<Page>

    /** A result or error to show once. */
    val message: StateFlow<String?>
    val folders: StateFlow<List<Folder>>
    val files: StateFlow<List<SyncedFile>>
    val problems: StateFlow<List<PathProblem>>

    /** The open folder's files a scan found deleted and held for a decision (ADR 0037). */
    val heldDeletes: StateFlow<List<String>>

    /** The open folder's withdrawals of revoked devices' changes, held for a decision (ADR 0040). */
    val heldWithdrawals: StateFlow<List<HeldWithdrawals>>
    val activity: StateFlow<List<Activity>>
    val recycleBin: StateFlow<List<Recycled>>
    val versions: StateFlow<List<Version>>
    val status: StateFlow<DeviceStatus?>
    val crashed: StateFlow<String?>
    val crashes: StateFlow<List<Crash>>

    fun go(page: Page)
    fun back(): Boolean
    fun show(message: String)
    fun messageShown()
    fun cleanUpCandidates(): List<SyncedFile>
    fun cleanUp(folder: String, paths: List<String>)
    fun removeHere(folder: String, path: String)
    fun download(folder: String, path: String)
    fun deleteEverywhere(folder: String, path: String)
    fun restore(folder: String, item: Recycled)
    fun discard(folder: String, item: Recycled)
    fun emptyRecycleBin(folder: String)
    fun pause(folder: String?)
    fun resume(folder: String?)
    fun rename(folder: String, from: String, to: String)
    fun pin(folder: String, path: String)
    fun unpin(folder: String, path: String)
    fun restoreVersion(folder: String, path: String, version: Version)
    fun resolve(folder: String, path: String, keep: String)

    /** Deletes a folder's held deletes on every device (ADR 0037). */
    fun confirmDeletes(folder: String)

    /** Brings a folder's held deletes back from other devices (ADR 0037). */
    fun restoreDeleted(folder: String)

    /** Withdraws what a revoked device changed after its loss ([withdraw]), or keeps it (ADR 0040). */
    fun decideWithdrawals(folder: String, device: String, withdraw: Boolean)

    /** Tries a folder's failed writes and deletes again (ADR 0037). */
    fun retryProblems(folder: String)
    suspend fun openVersion(folder: String, path: String, version: Version): String
    fun dismissCrashes()
    fun restart()

    /** The open folder's storage priorities and what its storage peers keep, on its Storage page (ADR 0023). */
    val storage: StateFlow<FolderStorage?>

    /** Ranks the file or directory at [path] for the folder's storage peers, or puts it back to the default for null. */
    fun setPriority(folder: String, path: String, directory: Boolean, priority: Int?)

    /** Asks storage peer [device] to forget what it holds of files ranked below [below]; only ever as the user asks. */
    suspend fun storageDrop(folder: String, device: String, below: Int, force: Boolean): StorageDropResult
}

/** The settings page. */
interface SettingsModel {
    val background: StateFlow<SettingsViewModel.Background>
    val localDiscovery: StateFlow<Boolean>
    val limits: StateFlow<SettingsViewModel.Limits>
    val limitsProblem: StateFlow<String?>
    val folders: StateFlow<List<Folder>>
    val reportUrl: StateFlow<String>
    val reportUrlProblem: StateFlow<String?>
    val errors: StateFlow<Map<String, String>>

    fun setBackground(change: (SettingsViewModel.Background) -> SettingsViewModel.Background)
    fun setLocalDiscovery(on: Boolean)
    fun setLimits(limits: SettingsViewModel.Limits)
    fun setReportUrl(url: String)
    fun syncNow()
    fun setChoice(folder: Folder, choice: SyncChoice)
    fun setRetention(folder: String, days: UInt)

    /** Starts the folder's metadata over (ADR 0034). */
    fun rebuild(folder: String)
}

/** The status page. */
interface StatusModel {
    val peers: StateFlow<List<PeerState>>
    val transfer: StateFlow<Transfer?>
    val checks: StateFlow<List<Check>?>
    val bundle: StateFlow<DiagnosticsBundle?>
    val error: StateFlow<String?>

    fun showing(shown: Boolean)
    fun check()
    fun buildBundle()
    fun bundleClosed()
}

/** Adding or joining a folder. */
interface AddFolderModel {
    val step: StateFlow<AddFolderViewModel.Step>

    fun start(offer: FolderOffer?, again: Boolean = false)
    fun reset()
    fun useAppStorage()
    fun usePhoneFolder()
    fun accessChanged()
    fun open(dir: File?)
    fun up(): Boolean
    fun choose(dir: File)
    fun add(dir: File, choice: SyncChoice)
}

/** Pairing this phone. */
interface PairingModel {
    val step: StateFlow<PairingViewModel.Step>

    fun retry()
}
