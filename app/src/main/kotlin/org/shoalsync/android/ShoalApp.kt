package org.shoalsync.android

import android.app.Application
import android.os.Build
import android.system.Os
import org.shoalsync.ffi.initDiagnostics
import org.shoalsync.ffi.recordCrash
import org.shoalsync.ffi.setKeyVault
import java.io.File

class ShoalApp : Application() {
    override fun onCreate() {
        // The engine's allocator, mimalloc, reads these when the native library loads, so they are
        // set before anything touches the FFI: freed memory goes back to Android at once and
        // arenas are not committed up front (ADR 0035). One already set is kept.
        for ((name, value) in listOf("MIMALLOC_PURGE_DELAY" to "0", "MIMALLOC_ARENA_EAGER_COMMIT" to "0")) {
            runCatching { Os.setenv(name, value, false) }.onFailure { AppLog.w(TAG, "setting $name failed", it) }
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
            .onSuccess { AppLog.engineReady = true }
            .onFailure { AppLog.w(TAG, "starting diagnostics failed", it) }
        val paired = runCatching { Peer.paired }.getOrDefault(false)
        AppLog.i(TAG, "started: version ${appVersion()}, Android ${Build.VERSION.SDK_INT}, paired $paired")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.e(TAG, "uncaught on ${thread.name}", error)
            runCatching { recordCrash(Peer.dataDir, error.toString(), error.stackTraceToString()) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "unknown"

    private companion object {
        const val TAG = "ShoalApp"
    }
}
