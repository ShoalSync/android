package org.shoalsync.android.folders

import android.content.Context
import android.media.MediaScannerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.shoalsync.android.AppLog
import org.shoalsync.android.Notifications
import org.shoalsync.ffi.Node
import org.shoalsync.ffi.NodeEvent
import java.io.File

/**
 * Work that follows the node's events while it runs: files written into or removed from phone
 * folders are announced to `MediaStore`, so galleries show them (ADR 0025), a new conflict is
 * notified (ADR 0029), and remove after backup runs shortly after folders change and every
 * minute, as confirmations arrive.
 */
object Housekeeping {
    private const val TAG = "Housekeeping"
    private const val SETTLE_MS = 3_000L
    private const val RECHECK_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start(context: Context, node: Node, events: Flow<NodeEvent>) {
        job?.cancel()
        AppLog.d(TAG, "started")
        val app = context.applicationContext
        job = scope.launch {
            runCatching { FolderSync.schedule(app, node.folders().any { !PhoneFolders.inAppStorage(it) }) }
                .onFailure { AppLog.w(TAG, "scheduling background sync failed", it) }
            launch {
                events.collect { event ->
                    if (event is NodeEvent.ConflictDetected) {
                        AppLog.i(TAG, "conflict in ${event.path}")
                        runCatching { Notifications.conflict(app, node, event.folder, event.path) }
                            .onFailure { AppLog.w(TAG, "notifying a conflict failed", it) }
                        return@collect
                    }
                    val (folder, path) = when (event) {
                        is NodeEvent.FileUpdated -> event.folder to event.path
                        is NodeEvent.FileRemoved -> event.folder to event.path
                        else -> return@collect
                    }
                    runCatching { announce(app, node, folder, path) }
                        .onFailure { AppLog.w(TAG, "announcing $path failed", it) }
                }
            }
            // Confirmations from full replicas arrive without an event, so look again every so
            // often as well as after changes.
            val ticks = flow {
                while (true) {
                    delay(RECHECK_MS)
                    emit(Unit)
                }
            }
            merge(events.filter { it is NodeEvent.FolderChanged || it is NodeEvent.FileUpdated }.map { }, ticks)
                .onStart { emit(Unit) }
                .debounce(SETTLE_MS)
                .collect {
                    runCatching { PhoneFolders.removeBackedUp(node) }
                        .onFailure { AppLog.w(TAG, "remove after backup failed", it) }
                }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Tells `MediaStore` that [path] in a phone folder was written or removed. */
    private fun announce(context: Context, node: Node, folderId: String, path: String) {
        val folder = node.folders().firstOrNull { it.id == folderId } ?: return
        if (PhoneFolders.inAppStorage(folder)) return
        MediaScannerConnection.scanFile(context, arrayOf(File(folder.path, path).path), null, null)
    }
}
