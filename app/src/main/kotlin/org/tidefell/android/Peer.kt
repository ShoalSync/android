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
        }
    }

    /** For callers that cannot suspend (the documents provider): the node, or null if unpaired. */
    fun startBlocking(): Node? = if (paired) runBlocking { start() } else null

    suspend fun stop() = lock.withLock {
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

    /** Bytes of other devices' files kept on the phone per folder. */
    var budgetBytes: Long
        get() = prefs.getLong("budget_bytes", 2L shl 30)
        set(value) = prefs.edit().putLong("budget_bytes", value).apply()

    /** Folder ID that the camera roll is backed up into; empty when backup is off (ADR 0020). */
    var backupFolder: String
        get() = prefs.getString("backup_folder", "") ?: ""
        set(value) = prefs.edit().putString("backup_folder", value).apply()

    /** Back up videos as well as photos. */
    var backupVideos: Boolean
        get() = prefs.getBoolean("backup_videos", true)
        set(value) = prefs.edit().putBoolean("backup_videos", value).apply()

    /** Albums (`MediaStore` bucket IDs) to back up; empty for all of them. */
    var backupAlbums: Set<String>
        get() = prefs.getStringSet("backup_albums", emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet("backup_albums", value).apply()

    /** Back up only on unmetered networks such as Wi-Fi. */
    var backupUnmeteredOnly: Boolean
        get() = prefs.getBoolean("backup_unmetered_only", true)
        set(value) = prefs.edit().putBoolean("backup_unmetered_only", value).apply()

    /** Back up only while charging. */
    var backupChargingOnly: Boolean
        get() = prefs.getBoolean("backup_charging_only", true)
        set(value) = prefs.edit().putBoolean("backup_charging_only", value).apply()

    /**
     * Delete originals from the camera roll once a full replica holds their backed-up copies
     * (ADR 0020, 0022). Off by default; Android 11 and later.
     */
    var removeOriginals: Boolean
        get() = prefs.getBoolean("remove_originals", false)
        set(value) = prefs.edit().putBoolean("remove_originals", value).apply()

    fun network(): Network = Network(
        relay = if (relay.isEmpty()) Relay.Default else Relay.Custom(listOf(relay)),
        addressLookup = addressLookup,
        bind = null,
    )
}
