package org.shoalsync.android

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.shoalsync.android.folders.Housekeeping
import org.shoalsync.ffi.EventListener
import org.shoalsync.ffi.Folder
import org.shoalsync.ffi.Limits
import org.shoalsync.ffi.Network
import org.shoalsync.ffi.Node
import org.shoalsync.ffi.NodeEvent
import org.shoalsync.ffi.Relay
import org.shoalsync.ffi.isPaired
import java.io.File

/** This device's peer: one [Node] per process, started once the device is paired. */
object Peer {
    private lateinit var app: Context
    private val lock = Mutex()
    private val running = MutableStateFlow<Node?>(null)
    private val changes = MutableSharedFlow<NodeEvent>(extraBufferCapacity = 64)
    private val stopped = MutableStateFlow<String?>(null)

    /** The running node, or null before pairing or while starting. */
    val node: StateFlow<Node?> = running

    /** Everything that changes in the node's folders. */
    val events: SharedFlow<NodeEvent> = changes

    /**
     * Why the node stopped by itself, after an error in the engine (ADR 0030), or null while it
     * runs. [restart] starts it again from its saved state.
     */
    val crashed: StateFlow<String?> = stopped

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** The application context. */
    val context: Context get() = app

    /** Keys, certificate and engine state. */
    val dataDir: String get() = File(app.filesDir, "shoal").path

    /** Where joined folders' files live; app-private, exposed through the documents provider. */
    val foldersDir: File get() = File(app.filesDir, "folders")

    val paired: Boolean get() = isPaired(dataDir)

    val settings: Settings get() = Settings(app.getSharedPreferences("settings", Context.MODE_PRIVATE))

    /** Starts the node if it is not running yet. */
    suspend fun start(): Node = lock.withLock {
        running.value ?: Node.start(dataDir, settings.network()).also { node ->
            node.subscribe(object : EventListener {
                override fun onEvent(event: NodeEvent) {
                    if (event is NodeEvent.Crashed) {
                        AppLog.e(TAG, "the node stopped by itself: ${event.message}")
                        stopped.value = event.message
                    }
                    changes.tryEmit(event)
                }
            })
            // The engine keeps limits until it stops; the app keeps them for good (ADR 0029).
            node.setLimits(settings.limits())
            stopped.value = null
            running.value = node
            startedAt = SystemClock.elapsedRealtime()
            Usage.add(app, Usage.NODE_STARTS)
            AppLog.i(TAG, "node started: ${node.folders().size} folders")
            Housekeeping.start(app, node, events)
        }
    }

    /** For callers that cannot suspend (the documents provider): the node, or null if unpaired. */
    fun startBlocking(): Node? = if (paired) runBlocking { start() } else null

    suspend fun stop() = lock.withLock {
        Housekeeping.stop()
        running.value?.let { node ->
            val live = live(node)
            for ((key, amount) in live) Usage.add(app, key, amount)
            AppLog.i(
                TAG,
                "node stopped after ${(live[Usage.NODE_MS] ?: 0) / MS_PER_S} s; sent ${live[Usage.BYTES_SENT]} bytes, " +
                    "received ${live[Usage.BYTES_RECEIVED]}",
            )
            running.value = null
            node.shutdown()
            node.close()
        }
    }

    /** When the running node started, by the elapsed-time clock. */
    private var startedAt = 0L

    /** What the running node has done that [Usage] has not counted yet. */
    fun live(): Map<String, Long> = running.value?.let(::live) ?: emptyMap()

    private fun live(node: Node): Map<String, Long> {
        val transfer = runCatching { node.transfer() }.getOrNull()
        return mapOf(
            Usage.NODE_MS to SystemClock.elapsedRealtime() - startedAt,
            Usage.BYTES_SENT to (transfer?.sent?.toLong() ?: 0),
            Usage.BYTES_RECEIVED to (transfer?.received?.toLong() ?: 0),
        )
    }

    /** Starts the node again after it stopped by itself. */
    suspend fun restart(): Node {
        AppLog.i(TAG, "restarting the node")
        // A node that crashed may fail to shut down cleanly; its saved state is what counts.
        runCatching { stop() }.onFailure {
            AppLog.w(TAG, "stopping the node failed", it)
            running.value = null
        }
        return start()
    }

    fun folderName(folder: Folder): String = File(folder.path).name

    private const val TAG = "Peer"
    private const val MS_PER_S = 1000
}

/** User settings. */
class Settings(private val prefs: SharedPreferences) {
    /** Relay URL; empty for the default public relays. */
    var relay: String
        get() = prefs.getString("relay", "") ?: ""
        set(value) = prefs.edit().putString("relay", value.trim()).apply()

    /** Token for a relay that admits only holders of it; empty for a relay that admits anyone. */
    var relayToken: String
        get() = prefs.getString("relay_token", "") ?: ""
        set(value) = prefs.edit { putString("relay_token", value.trim()) }

    /** Publish and resolve addresses through DNS. Off only for tests on closed networks. */
    var addressLookup: Boolean
        get() = prefs.getBoolean("address_lookup", true)
        set(value) = prefs.edit().putBoolean("address_lookup", value).apply()

    /** Find this user's devices on the local network with mDNS (ADR 0029). Read when the peer starts. */
    var localDiscovery: Boolean
        get() = prefs.getBoolean("local_discovery", true)
        set(value) = prefs.edit().putBoolean("local_discovery", value).apply()

    /** Bytes of other devices' files kept on the phone per folder. */
    var budgetBytes: Long
        get() = prefs.getLong("budget_bytes", 2L shl 30)
        set(value) = prefs.edit().putLong("budget_bytes", value).apply()

    /** Sync phone folders in the background only on unmetered networks such as Wi-Fi. */
    var syncUnmeteredOnly: Boolean
        get() = prefs.getBoolean("sync_unmetered_only", true)
        set(value) = prefs.edit().putBoolean("sync_unmetered_only", value).apply()

    /** Sync phone folders in the background only while charging. */
    var syncChargingOnly: Boolean
        get() = prefs.getBoolean("sync_charging_only", false)
        set(value) = prefs.edit().putBoolean("sync_charging_only", value).apply()

    /**
     * When remove after backup was chosen for [folder], in milliseconds since the Unix epoch, or
     * null if it is not on. Only files added or changed since are removed (ADR 0025).
     */
    fun removingSince(folder: String): Long? =
        prefs.getLong("remove_after_backup.$folder", -1).takeIf { it >= 0 }

    /**
     * Entries of [folder] the user restored from the recycle bin: remove after backup leaves
     * them on the phone from then on.
     */
    fun kept(folder: String): Set<String> = prefs.getStringSet("kept.$folder", emptySet())!!.toSet()

    fun keep(folder: String, entry: String) {
        prefs.edit().putStringSet("kept.$folder", kept(folder) + entry).apply()
    }

    fun setRemovingSince(folder: String, sinceMs: Long?) {
        prefs.edit().apply {
            if (sinceMs == null) remove("remove_after_backup.$folder") else putLong("remove_after_backup.$folder", sinceMs)
        }.apply()
    }

    /** Most KiB per second sent to other devices, or 0 for no limit (ADR 0029). */
    var uploadKib: Long
        get() = prefs.getLong("upload_kib", 0)
        set(value) = prefs.edit().putLong("upload_kib", value).apply()

    /** Most KiB per second received from other devices, or 0 for no limit. */
    var downloadKib: Long
        get() = prefs.getLong("download_kib", 0)
        set(value) = prefs.edit().putLong("download_kib", value).apply()

    /** Also limit traffic to devices on the same network. */
    var limitLocal: Boolean
        get() = prefs.getBoolean("limit_local", false)
        set(value) = prefs.edit().putBoolean("limit_local", value).apply()

    /**
     * The issue tracker crash reports are written up for: its issues page, such as
     * `https://github.com/ShoalSync/shoal/issues`, or empty for the default (ADR 0030).
     */
    var reportUrl: String
        get() = prefs.getString("report_url", "") ?: ""
        set(value) = prefs.edit().putString("report_url", value.trim()).apply()

    fun limits(): Limits = Limits(
        upload = uploadKib.takeIf { it > 0 }?.let { (it * 1024).toULong() },
        download = downloadKib.takeIf { it > 0 }?.let { (it * 1024).toULong() },
        limitLocal = limitLocal,
    )

    fun network(): Network = Network(
        relay = if (relay.isEmpty()) {
            Relay.Default
        } else {
            Relay.Custom(listOf(relay), relayToken.ifEmpty { null })
        },
        addressLookup = addressLookup,
        localDiscovery = localDiscovery,
        bind = null,
    )
}
