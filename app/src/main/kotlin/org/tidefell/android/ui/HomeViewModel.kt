package org.tidefell.android.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tidefell.android.Peer
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.Removable
import org.tidefell.ffi.File
import org.tidefell.ffi.Folder
import org.tidefell.ffi.Node
import org.tidefell.ffi.Recycled

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

    data class Files(val folder: String) : Page {
        override val parent: Page = Folders
    }

    data class RecycleBin(val folder: String) : Page {
        override val parent: Page = Files(folder)
    }
}

/** Folders, the files and recycle bin of the open one, and what can be done to them. */
class HomeViewModel : ViewModel() {
    private val current = MutableStateFlow<Page>(Page.Folders)
    private val messages = MutableStateFlow<String?>(null)
    private val changes = MutableStateFlow(0)

    /** The running node, or null while it starts. */
    val node: StateFlow<Node?> = Peer.node

    val page: StateFlow<Page> = current

    /** A result or error to show once. */
    val message: StateFlow<String?> = messages

    val folders: StateFlow<List<Folder>> = viewModelScope.nodeState(emptyList(), refresh = changes) { it.folders() }

    val files: StateFlow<List<File>> = viewModelScope.nodeState(emptyList(), refresh = merge(current, changes)) { node ->
        folderOf(current.value)?.let { node.files(it) } ?: emptyList()
    }

    val recycleBin: StateFlow<List<Recycled>> = viewModelScope.nodeState(emptyList(), refresh = merge(current, changes)) { node ->
        (current.value as? Page.RecycleBin)?.let { node.recycleBin(it.folder) } ?: emptyList()
    }

    init {
        viewModelScope.launch {
            runCatching { Peer.start() }.onFailure { Log.e(TAG, "starting the node failed", it) }
        }
    }

    fun go(page: Page) {
        current.value = page
    }

    /** Goes to the current page's parent; false on the top page. */
    fun back(): Boolean {
        val parent = current.value.parent ?: return false
        current.value = parent
        return true
    }

    fun messageShown() {
        messages.value = null
    }

    /** Files of the open folder that clean up would move to this phone's recycle bin. */
    fun cleanUpCandidates(): List<File> = Removable.confirmed(files.value)

    fun cleanUp(folder: String, paths: List<String>) = act {
        val removed = PhoneFolders.removeHere(folder, paths)
        "Moved $removed files to this phone's recycle bin. Your other devices keep them."
    }

    fun removeHere(folder: String, path: String) = act {
        PhoneFolders.removeHere(folder, listOf(path))
        "Moved $path to this phone's recycle bin. Your other devices keep it."
    }

    fun deleteEverywhere(folder: String, path: String) = act {
        Peer.start().delete(folder, path)
        "Deleted $path from all your devices. Each keeps it in its recycle bin."
    }

    fun restore(folder: String, item: Recycled) = act {
        PhoneFolders.restore(folder, item)
        if (item.hereOnly) "Restored ${item.path} on this phone." else "Restored ${item.path} on all your devices."
    }

    fun discard(folder: String, item: Recycled) = act {
        withContext(Dispatchers.IO) { Peer.start().discard(folder, item.entry) }
        "Deleted ${item.path} from this phone for good."
    }

    fun emptyRecycleBin(folder: String) = act {
        val removed = withContext(Dispatchers.IO) { Peer.start().emptyRecycleBin(folder) }
        "Deleted ${removed.size} files from this phone for good."
    }

    /** Runs [action] and shows what it returns, or its error. */
    private fun act(action: suspend () -> String) {
        viewModelScope.launch {
            messages.value = runCatching { action() }.getOrElse { it.message ?: it.toString() }
            changes.update { it + 1 }
        }
    }

    private fun folderOf(page: Page): String? = when (page) {
        is Page.Files -> page.folder
        is Page.RecycleBin -> page.folder
        else -> null
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
