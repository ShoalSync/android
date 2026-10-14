package org.tidefell.android

import android.app.Application
import android.system.Os
import android.util.Log
import org.tidefell.ffi.initDiagnostics
import org.tidefell.ffi.recordCrash
import org.tidefell.ffi.setKeyVault
import java.io.File

class TidefellApp : Application() {
    override fun onCreate() {
        // The engine's allocator, mimalloc, reads these when the native library loads, so they are
        // set before anything touches the FFI: freed memory goes back to Android at once and
        // arenas are not committed up front (ADR 0035). One already set is kept.
        for ((name, value) in listOf("MIMALLOC_PURGE_DELAY" to "0", "MIMALLOC_ARENA_EAGER_COMMIT" to "0")) {
            runCatching { Os.setenv(name, value, false) }.onFailure { Log.w(TAG, "setting $name failed", it) }
        }
        super.onCreate()
        Peer.init(this)
        // Engine logs go to logcat and to files a report can include, and panics to crash files
        // (ADR 0030). The app's own uncaught exceptions are recorded the same way.
        File(Peer.dataDir).mkdirs()
        // The device key is sealed by the Android Keystore, not a plain file (ADR 0038); a plain
        // one from an earlier version moves in the first time the key is used.
        setKeyVault(KeystoreVault(File(Peer.dataDir, "device.key.sealed")))
        runCatching { initDiagnostics(Peer.dataDir, "info") }
            .onFailure { Log.w(TAG, "starting diagnostics failed", it) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { recordCrash(Peer.dataDir, error.toString(), error.stackTraceToString()) }
            previous?.uncaughtException(thread, error)
        }
    }

    private companion object {
        const val TAG = "TidefellApp"
    }
}
