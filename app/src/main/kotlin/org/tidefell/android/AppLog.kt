package org.tidefell.android

import android.util.Log
import org.tidefell.ffi.LogLevel
import org.tidefell.ffi.log

/**
 * The app's log (ADR 0030). Messages go through the engine's logger, so they reach logcat under
 * the tag `tidefell`, the log files and diagnostics bundles alongside the engine's own, each with
 * the [tag] of the part of the app that wrote it. Until the engine's logger has started, or if it
 * cannot, they go to logcat only, under their own tag.
 *
 * What to log: what the app does in the background (the service, background sync, removing
 * backed-up files), what the user asked for (pairing, joining and leaving folders, changing how
 * they sync), and every failure, with its exception. Paths a bundle hides by default may appear in
 * messages; the bundle replaces them. Never log keys, pairing codes or file contents.
 */
object AppLog {
    /** Set once the engine's logger has started; before that, messages go to logcat directly. */
    @Volatile
    var engineReady = false

    fun d(tag: String, message: String) = write(LogLevel.DEBUG, Log.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = write(LogLevel.INFO, Log.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = write(LogLevel.WARN, Log.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = write(LogLevel.ERROR, Log.ERROR, tag, message, error)

    private fun write(level: LogLevel, priority: Int, tag: String, message: String, error: Throwable?) {
        val text = if (error == null) message else "$message: ${error.stackTraceToString()}"
        if (engineReady && runCatching { log(level, tag, text) }.isSuccess) return
        // Logcat itself may be missing, as in unit tests; a message is never worth a crash.
        runCatching { Log.println(priority, tag, text) }
    }
}
