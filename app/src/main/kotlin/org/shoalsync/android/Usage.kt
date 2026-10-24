package org.shoalsync.android

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import org.json.JSONObject

/**
 * What the app has done that costs battery, counted on the phone since [SINCE] (ADR 0039): how
 * often and how long the peer, the foreground service and background sync ran, the time spent
 * scanning, which is where new files are hashed, and the file content moved. The diagnostics
 * bundle includes it, so a report of battery drain carries numbers. Nothing leaves the phone
 * otherwise.
 */
object Usage {
    /** When counting started, in milliseconds since the Unix epoch. */
    private const val SINCE = "since_ms"

    const val NODE_STARTS = "node_starts"
    const val NODE_MS = "node_ms"
    const val SERVICE_STARTS = "service_starts"
    const val SERVICE_MS = "service_ms"
    const val WORKER_RUNS = "worker_runs"
    const val WORKER_MS = "worker_ms"
    const val SCAN_MS = "scan_ms"
    const val BYTES_SENT = "bytes_sent"
    const val BYTES_RECEIVED = "bytes_received"

    private val KEYS = listOf(
        NODE_STARTS, NODE_MS, SERVICE_STARTS, SERVICE_MS, WORKER_RUNS, WORKER_MS, SCAN_MS, BYTES_SENT, BYTES_RECEIVED,
    )
    private const val MS_PER_HOUR = 3_600_000.0

    /** A minute: rates over less are not worth a number. */
    private const val MIN_HOURS = 1 / 60.0
    private const val TENTHS = 10.0

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).also {
            if (!it.contains(SINCE)) it.edit { putLong(SINCE, System.currentTimeMillis()) }
        }

    /** Adds [amount] to [key]. */
    fun add(context: Context, key: String, amount: Long = 1) {
        if (amount <= 0) return
        val prefs = prefs(context)
        synchronized(this) { prefs.edit { putLong(key, prefs.getLong(key, 0) + amount) } }
    }

    /** Runs [block], adding the time it took to [key]. */
    inline fun <T> timed(context: Context, key: String, block: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            add(context, key, SystemClock.elapsedRealtime() - started)
        }
    }

    /**
     * The counts, each also per hour since counting started, with [live] added: what a running
     * peer has done but not yet added.
     */
    fun toJson(context: Context, live: Map<String, Long> = emptyMap()): JSONObject {
        val prefs = prefs(context)
        val since = prefs.getLong(SINCE, System.currentTimeMillis())
        val hours = ((System.currentTimeMillis() - since) / MS_PER_HOUR).coerceAtLeast(MIN_HOURS)
        val json = JSONObject().put("hours", Math.round(hours * TENTHS) / TENTHS)
        for (key in KEYS) {
            val value = prefs.getLong(key, 0) + (live[key] ?: 0)
            json.put(key, value).put("${key}_per_hour", Math.round(value / hours))
        }
        return json
    }
}
