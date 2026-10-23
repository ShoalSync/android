package org.tidefell.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tidefell.android.AppLog
import org.tidefell.android.Peer
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.Removable
import org.tidefell.ffi.Activity
import org.tidefell.ffi.Crash
import org.tidefell.ffi.DeviceStatus
import org.tidefell.ffi.File
import org.tidefell.ffi.Folder
import org.tidefell.ffi.HeldWithdrawals
import org.tidefell.ffi.Node
import org.tidefell.ffi.PathProblem
import org.tidefell.ffi.Recycled
import org.tidefell.ffi.StorageDropResult
import org.tidefell.ffi.Version

/** Where the app is. The back button goes to the page's parent. */
sealed interface Page {
    val parent: Page?

    data object Folders : Page {
        override val parent: Page? = null
    }

    data object Settings : Page {
        override val parent: Page = Folders
    }

    data object AddFolder : Page {
        override val parent: Page = Folders
    }

    /** This phone's connections, transfers, checks and diagnostics (ADR 0030). */
    data object Status : Page {
        override val parent: Page = Folders
    }

    /** What this phone applied to a folder, newest first (ADR 0030). */
    data class Activity(val folder: String) : Page {
        override val parent: Page = Files(folder)
    }

    data class Files(val folder: String) : Page {
        override val parent: Page = Folders
    }

    data class RecycleBin(val folder: String) : Page {
        override val parent: Page = Files(folder)
    }

    /** A file's earlier versions kept on this phone (ADR 0029). */
    data class History(val folder: String, val path: String) : Page {
        override val parent: Page = Files(folder)
    }

    /** A file changed on two devices apart, waiting for a decision (ADR 0029). */
    data class Conflict(val folder: String, val path: String) : Page {
        override val parent: Page = Files(folder)
    }

    /**
     * What an encrypted folder's storage peers keep, and its files' priorities (ADR 0023);
     * [focus] opens that path's priority at once.
     */
    data class Storage(val folder: String, val focus: String? = null) : Page {
        override val parent: Page = Files(folder)
    }
}

/** Pages asked for from outside the app, such as by a notification. */
object Navigation {
    val requests = MutableStateFlow<Page?>(null)
}

/** Folders, the files and recycle bin of the open one, and what can be done to them. */
class HomeViewModel : ViewModel(), HomeModel {
    private val current = MutableStateFlow<Page>(Page.Folders)
    private val messages = MutableStateFlow<String?>(null)
    private val changes = MutableStateFlow(0)

    /** The running node, or null while it starts. */
    val node: StateFlow<Node?> = Peer.node

    override val started: StateFlow<Boolean> =
        Peer.node.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, Peer.node.value != null)

    override val page: StateFlow<Page> = current

    /** A result or error to show once. */
    override val message: StateFlow<String?> = messages

    override val folders: StateFlow<List<Folder>> = viewModelScope.nodeState(emptyList(), refresh = changes) {
        it.folders()
    }

    override val files: StateFlow<List<File>> = viewModelScope.nodeState(emptyList(), refresh = merge(current, changes)) { node ->
        folderOf(current.value)?.let { node.files(it) } ?: emptyList()
    }

    /** The open folder's paths that failed to read, fetch, write or delete (ADR 0030). */
    override val problems: StateFlow<List<PathProblem>> = viewModelScope.nodeState(emptyList(), refresh = merge(current, changes)) { node ->
        (current.value as? Page.Files)?.let { node.pathProblems(it.folder) } ?: emptyList()
    }

    /** The open folder's held deletes, read only while it has some (ADR 0037). */
    override val heldDeletes: StateFlow<List<String>> =
        viewModelScope.nodeState(emptyList(), refresh = merge(current, changes, folders)) { node ->
            (current.value as? Page.Files)
                ?.takeIf { page -> folders.value.any { it.id == page.folder && it.heldDeletes > 0UL } }
                ?.let { node.heldDeletes(it.folder) }
                ?: emptyList()
        }

    /** The open folder's held withdrawals of revoked devices' changes (ADR 0040). */
    override val heldWithdrawals: StateFlow<List<HeldWithdrawals>> =
        viewModelScope.nodeState(emptyList(), refresh = merge(current, changes)) { node ->
            (current.value as? Page.Files)?.let { node.heldWithdrawals(it.folder) } ?: emptyList()
        }

    /** What this phone applied to the folder whose activity is open. */
    override val activity: StateFlow<List<Activity>> = viewModelScope.nodeState(emptyList(), refresh = merge(current, changes)) { node ->
        (current.value as? Page.Activity)?.let { node.activity(it.folder, ACTIVITY_SHOWN) } ?: emptyList()
    }

    override val recycleBin: StateFlow<List<Recycled>> = viewModelScope.nodeState(emptyList(), refresh = merge(current, changes)) { node ->
        (current.value as? Page.RecycleBin)?.let { node.recycleBin(it.folder) } ?: emptyList()
    }

    /** The versions of the file whose history or conflict is open. */
    override val versions: StateFlow<List<Version>> = viewModelScope.nodeState(emptyList(), refresh = merge(current, changes)) { node ->
        when (val page = current.value) {
            is Page.History -> node.versions(page.folder, page.path)
            is Page.Conflict -> node.versions(page.folder, page.path)
            else -> emptyList()
        }
    }

    /** This phone, including whether syncing is paused on it. */
    override val status: StateFlow<DeviceStatus?> = viewModelScope.nodeState(null, refresh = changes) { it.status() }

    /** Why the node stopped by itself, if it did (ADR 0030). */
    override val crashed: StateFlow<String?> = Peer.crashed

    /** Crashes the user has not been told about yet (ADR 0030). */
    override val crashes: StateFlow<List<Crash>> = viewModelScope.nodeState(emptyList(), refresh = changes) {
        org.tidefell.ffi.crashes(Peer.dataDir).filter { !it.seen }
    }

    init {
        viewModelScope.launch {
            runCatching { Peer.start() }.onFailure { AppLog.e(TAG, "starting the node failed", it) }
        }
        viewModelScope.launch {
            Navigation.requests.collect { page ->
                if (page != null) {
                    current.value = page
                    Navigation.requests.value = null
                }
            }
        }
    }

    override fun go(page: Page) {
        current.value = page
        if (page is Page.Storage) {
            storageState.value = null
            loadStorage(page.folder)
        }
    }

    private val storageState = MutableStateFlow<FolderStorage?>(null)

    /**
     * Loaded when the page opens and after each change made there, not on every node event:
     * each load asks the storage peers over the network.
     */
    override val storage: StateFlow<FolderStorage?> = storageState

    private fun loadStorage(folder: String) {
        viewModelScope.launch {
            runCatching {
                val node = Peer.start()
                FolderStorage(storageRows(node.priorities(folder)), node.storageReport(folder))
            }.onSuccess { if ((current.value as? Page.Storage)?.folder == folder) storageState.value = it }
                .onFailure {
                    AppLog.w(TAG, "loading storage failed", it)
                    messages.value = it.message ?: it.toString()
                }
        }
    }

    override fun setPriority(folder: String, path: String, directory: Boolean, priority: Int?) {
        viewModelScope.launch {
            AppLog.i(TAG, "setPriority")
            runCatching { Peer.start().setPriority(folder, patternFor(path, directory), priority?.toUByte()) }
                .onSuccess {
                    messages.value = if (priority == null) "$path is back to the default" else "$path ranked $priority"
                }
                .onFailure {
                    AppLog.w(TAG, "setPriority failed", it)
                    messages.value = it.message ?: it.toString()
                }
            loadStorage(folder)
        }
    }

    override suspend fun storageDrop(folder: String, device: String, below: Int, force: Boolean): StorageDropResult {
        AppLog.i(TAG, "storageDrop")
        val result = Peer.start().storageDrop(folder, device, below.toUByte(), force)
        if (result.done) loadStorage(folder)
        return result
    }

    /** Goes to the current page's parent; false on the top page. */
    override fun back(): Boolean {
        val parent = current.value.parent ?: return false
        current.value = parent
        return true
    }

    override fun show(message: String) {
        messages.value = message
    }

    override fun messageShown() {
        messages.value = null
    }

    /** Files of the open folder that clean up would move to this phone's recycle bin. */
    override fun cleanUpCandidates(): List<File> = Removable.confirmed(files.value)

    override fun cleanUp(folder: String, paths: List<String>) = act("cleanUp") {
        val removed = PhoneFolders.removeHere(folder, paths)
        "Moved ${files(removed)} to this phone's recycle bin. Your other devices keep ${if (removed == 1) "it" else "them"}."
    }

    override fun removeHere(folder: String, path: String) = act("removeHere") {
        PhoneFolders.removeHere(folder, listOf(path))
        "Moved $path to this phone's recycle bin. Your other devices keep it."
    }

    override fun download(folder: String, path: String) = act("download") {
        PhoneFolders.download(folder, path)
        "Downloaded $path to this phone."
    }

    override fun deleteEverywhere(folder: String, path: String) = act("deleteEverywhere") {
        Peer.start().delete(folder, path)
        "Deleted $path from all your devices. Each keeps it in its recycle bin."
    }

    override fun restore(folder: String, item: Recycled) = act("restore") {
        PhoneFolders.restore(folder, item)
        if (item.hereOnly) "Restored ${item.path} on this phone." else "Restored ${item.path} on all your devices."
    }

    override fun discard(folder: String, item: Recycled) = act("discard") {
        withContext(Dispatchers.IO) { Peer.start().discard(folder, item.entry) }
        "Deleted ${item.path} from this phone for good."
    }

    override fun emptyRecycleBin(folder: String) = act("emptyRecycleBin") {
        val removed = withContext(Dispatchers.IO) { Peer.start().emptyRecycleBin(folder) }
        "Deleted ${files(removed.size)} from this phone for good."
    }

    override fun pause(folder: String?) = act("pause") {
        withContext(Dispatchers.IO) { Peer.start().pause(folder) }
        if (folder == null) "Syncing is paused on this phone." else "Syncing of this folder is paused."
    }

    override fun resume(folder: String?) = act("resume") {
        withContext(Dispatchers.IO) { Peer.start().resume(folder) }
        "Syncing again."
    }

    /** Renames [from] to [to] on all devices: offered for a name this phone cannot store (ADR 0033). */
    override fun rename(folder: String, from: String, to: String) = act("rename") {
        Peer.start().rename(folder, from, to)
        "Renamed $from to $to on all your devices."
    }

    /** Keeps [path] on this phone, fetched and never removed to save space (ADR 0029). */
    override fun pin(folder: String, path: String) = act("pin") {
        Peer.start().pin(folder, path)
        "$path stays on this phone."
    }

    override fun unpin(folder: String, path: String) = act("unpin") {
        Peer.start().unpin(folder, path)
        "$path may be removed from this phone again to save space."
    }

    override fun restoreVersion(folder: String, path: String, version: Version) = act("restoreVersion") {
        Peer.start().restoreVersion(folder, path, version.id)
        "Restored the earlier version of $path on all your devices."
    }

    /** Keeps [keep], a version's ID or `both` (ADR 0029). */
    override fun resolve(folder: String, path: String, keep: String) = act("resolve") {
        Peer.start().resolve(folder, path, keep)
        current.value = Page.Files(folder)
        if (keep == "both") "Kept both versions of $path on all your devices." else "Kept the version you chose on all your devices."
    }

    override fun confirmDeletes(folder: String) = act("confirmDeletes") {
        Peer.start().confirmDeletes(folder)
        "Deleted the files from all your devices. Each keeps them in its recycle bin."
    }

    override fun restoreDeleted(folder: String) = act("restoreDeleted") {
        Peer.start().restoreDeleted(folder)
        "Bringing the files back from your other devices."
    }

    override fun decideWithdrawals(folder: String, device: String, withdraw: Boolean) = act("decideWithdrawals") {
        Peer.start().decideWithdrawals(folder, device, withdraw)
        if (withdraw) {
            "Withdrawing the changes. What they replaced is put back, or the files go to the recycle bin."
        } else {
            "Keeping the changes, as if they were made before the device was lost."
        }
    }

    override fun retryProblems(folder: String) = act("retryProblems") {
        withContext(Dispatchers.IO) { Peer.start().retryProblems(folder) }
        "Trying again."
    }

    /** A read-only copy of [version] of [path], to open in another app. */
    override suspend fun openVersion(folder: String, path: String, version: Version): String =
        if (version.id.startsWith("previous:")) {
            withContext(Dispatchers.IO) { Peer.start().versionFile(folder, path, version.id) }
        } else {
            Peer.start().openVersion(folder, path, version.id)
        }

    override fun dismissCrashes() = act("dismissCrashes") {
        withContext(Dispatchers.IO) { org.tidefell.ffi.dismissCrashes(Peer.dataDir) }
        ""
    }

    /** Starts syncing again after the node stopped by itself. */
    override fun restart() = act("restart") {
        Peer.restart()
        "Syncing again."
    }

    /** Runs [action], the user's [what], and shows what it returns, or its error. */
    private fun act(what: String, action: suspend () -> String) {
        viewModelScope.launch {
            AppLog.i(TAG, what)
            messages.value = runCatching { action() }
                .getOrElse {
                    AppLog.w(TAG, "$what failed", it)
                    it.message ?: it.toString()
                }
                .ifEmpty { null }
            changes.update { it + 1 }
        }
    }

    private fun folderOf(page: Page): String? = when (page) {
        is Page.Files -> page.folder
        is Page.RecycleBin -> page.folder
        is Page.History -> page.folder
        is Page.Conflict -> page.folder
        is Page.Activity -> page.folder
        is Page.Storage -> page.folder
        else -> null
    }

    private companion object {
        const val TAG = "HomeViewModel"

        /** Changes shown on a folder's activity page. */
        const val ACTIVITY_SHOWN = 200u
    }
}
