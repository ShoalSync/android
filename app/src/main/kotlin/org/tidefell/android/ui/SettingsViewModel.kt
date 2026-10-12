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
import org.tidefell.android.SyncService
import org.tidefell.android.folders.FolderSync
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.SyncChoice
import org.tidefell.ffi.Folder

/** How each folder syncs and how long it keeps deleted files, and when phone folders sync. */
class SettingsViewModel(application: Application) : AndroidViewModel(application), SettingsModel {
    /** When phone folders sync in the background. */
    data class Background(val unmeteredOnly: Boolean, val chargingOnly: Boolean)

    private val app get() = getApplication<Application>()
    private val settings = Peer.settings
    private val backgroundState = MutableStateFlow(Background(settings.syncUnmeteredOnly, settings.syncChargingOnly))
    private val changes = MutableStateFlow(0)
    private val errorsByFolder = MutableStateFlow<Map<String, String>>(emptyMap())

    override val background: StateFlow<Background> = backgroundState

    private val localDiscoveryState = MutableStateFlow(settings.localDiscovery)

    /** Whether this phone finds the user's devices on the local network. */
    override val localDiscovery: StateFlow<Boolean> = localDiscoveryState

    /** Turns local discovery on or off; the peer restarts, because it reads it at start. */
    override fun setLocalDiscovery(on: Boolean) {
        localDiscoveryState.value = on
        settings.localDiscovery = on
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                Peer.stop()
                SyncService.start(app)
            }
        }
    }

    /** Upload and download limits in KiB per second, 0 for none, and whether local devices count. */
    data class Limits(val uploadKib: Long, val downloadKib: Long, val limitLocal: Boolean)

    private val limitsState = MutableStateFlow(Limits(settings.uploadKib, settings.downloadKib, settings.limitLocal))
    private val limitsError = MutableStateFlow<String?>(null)

    override val limits: StateFlow<Limits> = limitsState

    /** Why the last limits could not be applied, if they could not. */
    override val limitsProblem: StateFlow<String?> = limitsError

    /** Keeps [limits] and applies them to the running node at once (ADR 0029). */
    override fun setLimits(limits: Limits) {
        limitsState.value = limits
        settings.uploadKib = limits.uploadKib
        settings.downloadKib = limits.downloadKib
        settings.limitLocal = limits.limitLocal
        viewModelScope.launch {
            limitsError.value = withContext(Dispatchers.IO) {
                runCatching { Peer.start().setLimits(settings.limits()) }.exceptionOrNull()?.let { it.message ?: it.toString() }
            }
        }
    }

    override val folders: StateFlow<List<Folder>> = viewModelScope.nodeState(emptyList(), refresh = changes) {
        it.folders()
    }

    private val reportUrlState = MutableStateFlow(settings.reportUrl)
    private val reportUrlError = MutableStateFlow<String?>(null)

    /** Where crash reports are filed (ADR 0030); empty for the default. */
    override val reportUrl: StateFlow<String> = reportUrlState

    /** Why the last report URL was not kept, if it was not. */
    override val reportUrlProblem: StateFlow<String?> = reportUrlError

    /** Keeps [url] if it is an issues page over HTTP or HTTPS without a query, or empty. */
    override fun setReportUrl(url: String) {
        val trimmed = url.trim()
        val valid = trimmed.isEmpty() ||
            (Regex("^https?://[^/\\s?#]+(/[^\\s?#]*)?$").matches(trimmed))
        reportUrlError.value = if (valid) null else "Enter the tracker's issues page, such as https://github.com/owner/repo/issues"
        if (valid) {
            settings.reportUrl = trimmed
            reportUrlState.value = trimmed
        }
    }

    /** The last error of an action on a folder, by folder ID. */
    override val errors: StateFlow<Map<String, String>> = errorsByFolder

    override fun setBackground(change: (Background) -> Background) {
        backgroundState.update(change)
        settings.syncUnmeteredOnly = backgroundState.value.unmeteredOnly
        settings.syncChargingOnly = backgroundState.value.chargingOnly
        FolderSync.schedule(app, folders.value.any { !PhoneFolders.inAppStorage(it) })
    }

    override fun syncNow() {
        FolderSync.now(app)
    }

    override fun setChoice(folder: Folder, choice: SyncChoice) = act(folder.id) { PhoneFolders.setChoice(folder, choice) }

    override fun setRetention(folder: String, days: UInt) = act(folder) { Peer.start().setRetention(folder, days) }

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
