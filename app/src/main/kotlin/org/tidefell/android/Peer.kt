package org.tidefell.android

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tidefell.android.folders.Housekeeping
import org.tidefell.ffi.EventListener
import org.tidefell.ffi.Folder
import org.tidefell.ffi.Network
import org.tidefell.ffi.Node
import org.tidefell.ffi.NodeEvent
import org.tidefell.ffi.Relay
import org.tidefell.ffi.isPaired

/** This device's peer: one [Node] per process, started once the device is paired. */
object Peer {
    private lateinit var app: Context
    private val lock = Mutex()
    private val running = MutableStateFlow<Node?>(null)
    private val changes = MutableSharedFlow<NodeEvent>(extraBufferCapacity = 64)

    /** The running node, or null before pairing or while starting. */
    val node: StateFlow<Node?> = running

    /** Everything that changes in the node's folders. */
    val events: SharedFlow<NodeEvent> = changes

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** The application context. */
    val context: Context get() = app

    /** Keys, certificate and engine state. */
    val dataDir: String get() = File(app.filesDir, "tidefell").path

    /** Where joined folders' files live; app-private, exposed through the documents provider. */
    val foldersDir: File get() = File(app.filesDir, "folders")

    val paired: Boolean get() = isPaired(dataDir)

    val settings: Settings get() = Settings(app.getSharedPreferences("settings", Context.MODE_PRIVATE))

    /** Starts the node if it is not running yet. */
    suspend fun start(): Node = lock.withLock {
        running.value ?: Node.start(dataDir, settings.network()).also { node ->
            node.subscribe(object : EventListener {
                override fun onEvent(event: NodeEvent) {
                    changes.tryEmit(event)
                }
            })
            running.value = node
            Housekeeping.start(app, node, events)
        }
    }

    /** For callers that cannot suspend (the documents provider): the node, or null if unpaired. */
    fun startBlocking(): Node? = if (paired) runBlocking { start() } else null

    suspend fun stop() = lock.withLock {
        Housekeeping.stop()
        running.value?.let { node ->
            running.value = null
            node.shutdown()
            node.close()
        }
    }

    fun folderName(folder: Folder): String = File(folder.path).name
}

/** User settings. */
class Settings(private val prefs: SharedPreferences) {
    /** Relay URL; empty for the default public relays. */
    var relay: String
        get() = prefs.getString("relay", "") ?: ""
        set(value) = prefs.edit().putString("relay", value.trim()).apply()

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

    fun network(): Network = Network(
        relay = if (relay.isEmpty()) Relay.Default else Relay.Custom(listOf(relay)),
        addressLookup = addressLookup,
        localDiscovery = localDiscovery,
        bind = null,
    )
}
