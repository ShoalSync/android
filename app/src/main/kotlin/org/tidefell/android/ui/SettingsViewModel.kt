package org.tidefell.android.ui

import android.app.Application
import android.content.IntentSender
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tidefell.android.Peer
import org.tidefell.android.backup.Backups
import org.tidefell.android.backup.CameraRollBackup
import org.tidefell.android.backup.RemoveOriginals
import org.tidefell.ffi.Folder

/** Camera-roll backup, removing originals, and each folder's recycle bin on this phone. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    /** The camera-backup settings, as stored. */
    data class Backup(
        /** Folder ID backed up into; empty when backup is off. */
        val folder: String,
        val videos: Boolean,
        val albums: Set<String>,
        val unmeteredOnly: Boolean,
        val chargingOnly: Boolean,
        val removeOriginals: Boolean,
    )

    private val app get() = getApplication<Application>()
    private val settings = Peer.settings
    private val backupState = MutableStateFlow(
        Backup(
            folder = settings.backupFolder,
            videos = settings.backupVideos,
            albums = settings.backupAlbums,
            unmeteredOnly = settings.backupUnmeteredOnly,
            chargingOnly = settings.backupChargingOnly,
            removeOriginals = settings.removeOriginals,
        ),
    )
    private val removals = MutableStateFlow(0)
    private val errorsByFolder = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Items handed to Android's delete request, until it answers. */
    private var removing: List<RemoveOriginals.Eligible> = emptyList()

    val backup: StateFlow<Backup> = backupState

    val folders: StateFlow<List<Folder>> = viewModelScope.nodeState(emptyList()) { it.folders() }

    /** Camera-roll albums, for choosing which to back up. */
    val albums: StateFlow<List<CameraRollBackup.Album>> = backupState
        .map { it.videos }
        .distinctUntilChanged()
        .map { videos -> runCatching { CameraRollBackup.albums(app, videos) }.getOrDefault(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Backed-up originals that a full replica holds, when removing them is on. */
    val eligible: StateFlow<List<RemoveOriginals.Eligible>> =
        viewModelScope.nodeState(emptyList(), refresh = merge(backupState, removals)) { node ->
            val backup = backupState.value
            if (backup.removeOriginals && backup.folder.isNotEmpty()) {
                RemoveOriginals.eligible(app, node, backup.folder)
            } else {
                emptyList()
            }
        }

    /** The last error of an action on a folder, by folder ID. */
    val errors: StateFlow<Map<String, String>> = errorsByFolder

    /** Changes the backup settings, stores them and reschedules backups. */
    fun updateBackup(change: (Backup) -> Backup) {
        val backup = backupState.updateAndGet(change)
        settings.backupFolder = backup.folder
        settings.backupVideos = backup.videos
        settings.backupAlbums = backup.albums
        settings.backupUnmeteredOnly = backup.unmeteredOnly
        settings.backupChargingOnly = backup.chargingOnly
        Backups.schedule(app)
    }

    fun backUpNow() = Backups.now(app)

    /** Turns removing originals on or off; off forgets every candidate (ADR 0020). */
    fun setRemoveOriginals(on: Boolean) {
        settings.removeOriginals = on
        backupState.update { it.copy(removeOriginals = on) }
        if (!on) viewModelScope.launch(Dispatchers.IO) { RemoveOriginals.turnedOff(app) }
    }

    /** Android's delete request for [items]; call [removalAnswered] with its result. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun deleteRequest(items: List<RemoveOriginals.Eligible>): IntentSender {
        removing = items
        return RemoveOriginals.deleteRequest(app, items)
    }

    fun removalAnswered(deleted: Boolean) {
        val items = removing
        removing = emptyList()
        viewModelScope.launch {
            if (deleted) withContext(Dispatchers.IO) { RemoveOriginals.removed(app, items) }
            removals.update { it + 1 }
        }
    }

    fun setRetention(folder: String, days: UInt) = act(folder) { Peer.start().setRetention(folder, days) }

    fun emptyRecycleBin(folder: String) = act(folder) { Peer.start().emptyRecycleBin(folder) }

    /** Runs [action] on [Dispatchers.IO] and records its error, if any, against [folder]. */
    private fun act(folder: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { action() }.exceptionOrNull() }
            errorsByFolder.update { errors ->
                if (error == null) errors - folder else errors + (folder to (error.message ?: error.toString()))
            }
        }
    }
}
