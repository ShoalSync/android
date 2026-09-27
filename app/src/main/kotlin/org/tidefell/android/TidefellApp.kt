package org.tidefell.android

import android.app.Application
import android.util.Log
import java.io.File
import org.tidefell.ffi.initDiagnostics
import org.tidefell.ffi.recordCrash

class TidefellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Peer.init(this)
        // Engine logs go to logcat and to files a report can include, and panics to crash files
        // (ADR 0030). The app's own uncaught exceptions are recorded the same way.
        File(Peer.dataDir).mkdirs()
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
