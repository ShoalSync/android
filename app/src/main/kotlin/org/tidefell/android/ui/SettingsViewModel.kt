package org.tidefell.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tidefell.android.Peer
import org.tidefell.android.folders.FolderSync
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.SyncChoice
import org.tidefell.ffi.Folder

/** How each folder syncs and how long it keeps deleted files, and when phone folders sync. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    /** When phone folders sync in the background. */
    data class Background(val unmeteredOnly: Boolean, val chargingOnly: Boolean)

    private val app get() = getApplication<Application>()
    private val settings = Peer.settings
    private val backgroundState = MutableStateFlow(Background(settings.syncUnmeteredOnly, settings.syncChargingOnly))
    private val changes = MutableStateFlow(0)
    private val errorsByFolder = MutableStateFlow<Map<String, String>>(emptyMap())

    val background: StateFlow<Background> = backgroundState

    val folders: StateFlow<List<Folder>> = viewModelScope.nodeState(emptyList(), refresh = changes) { it.folders() }

    /** The last error of an action on a folder, by folder ID. */
    val errors: StateFlow<Map<String, String>> = errorsByFolder

    fun setBackground(change: (Background) -> Background) {
        backgroundState.update(change)
        settings.syncUnmeteredOnly = backgroundState.value.unmeteredOnly
        settings.syncChargingOnly = backgroundState.value.chargingOnly
        FolderSync.schedule(app, folders.value.any { !PhoneFolders.inAppStorage(it) })
    }

    fun syncNow() = FolderSync.now(app)

    fun setChoice(folder: Folder, choice: SyncChoice) = act(folder.id) { PhoneFolders.setChoice(folder, choice) }

    fun setRetention(folder: String, days: UInt) = act(folder) { Peer.start().setRetention(folder, days) }

    /** Runs [action] on [Dispatchers.IO] and records its error, if any, against [folder]. */
    private fun act(folder: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { action() }.exceptionOrNull() }
            errorsByFolder.update { errors ->
                if (error == null) errors - folder else errors + (folder to (error.message ?: error.toString()))
            }
            changes.update { it + 1 }
        }
    }
}
