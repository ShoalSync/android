package org.tidefell.android.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.tidefell.android.Peer
import org.tidefell.ffi.File
import org.tidefell.ffi.Folder
import org.tidefell.ffi.Node

/** Folders and the files of the open one. Starts the node. */
class HomeViewModel : ViewModel() {
    private val opened = MutableStateFlow<Folder?>(null)

    /** The running node, or null while it starts. */
    val node: StateFlow<Node?> = Peer.node

    /** The folder whose files are shown, if any. */
    val open: StateFlow<Folder?> = opened

    val folders: StateFlow<List<Folder>> = viewModelScope.nodeState(emptyList()) { it.folders() }

    val files: StateFlow<List<File>> = viewModelScope.nodeState(emptyList(), refresh = opened) { node ->
        opened.value?.let { node.files(it.id) } ?: emptyList()
    }

    init {
        viewModelScope.launch {
            runCatching { Peer.start() }.onFailure { Log.e(TAG, "starting the node failed", it) }
        }
    }

    fun open(folder: Folder?) {
        opened.value = folder
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
