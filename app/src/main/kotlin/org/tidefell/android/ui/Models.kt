package org.tidefell.android.ui

import kotlinx.coroutines.flow.StateFlow
import org.tidefell.android.folders.SyncChoice
import org.tidefell.ffi.Activity
import org.tidefell.ffi.Check
import org.tidefell.ffi.Crash
import org.tidefell.ffi.DeviceStatus
import org.tidefell.ffi.DiagnosticsBundle
import org.tidefell.ffi.Folder
import org.tidefell.ffi.FolderOffer
import org.tidefell.ffi.PathProblem
import org.tidefell.ffi.PeerState
import org.tidefell.ffi.Recycled
import org.tidefell.ffi.Transfer
import org.tidefell.ffi.Version
import java.io.File
import org.tidefell.ffi.File as SyncedFile

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
    suspend fun openVersion(folder: String, path: String, version: Version): String
    fun dismissCrashes()
    fun restart()
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
