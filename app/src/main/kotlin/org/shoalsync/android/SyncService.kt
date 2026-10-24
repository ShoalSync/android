package org.shoalsync.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the peer running while the app is in the background (ADR 0004). */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lets multicast through Wi-Fi power saving, so local discovery (ADR 0029) works. */
    private var multicast: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** When the service started, by the elapsed-time clock, or 0 before it has. */
    private var startedAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (startedAt == 0L) {
            startedAt = SystemClock.elapsedRealtime()
            Usage.add(this, Usage.SERVICE_STARTS)
            AppLog.i(TAG, "started; local discovery ${Peer.settings.localDiscovery}")
        }
        if (!Peer.settings.localDiscovery) {
            multicast?.release()
            multicast = null
        } else if (multicast == null) {
            multicast = getSystemService(WifiManager::class.java)
                ?.createMulticastLock("shoal")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        scope.launch {
            runCatching { Peer.start() }.onFailure { AppLog.e(TAG, "starting the peer failed", it) }
        }
        return START_STICKY
    }

    /**
     * Android 15 limits `dataSync` foreground services to six hours a day and calls this when the
     * limit is reached; a service that does not stop is killed. Stopping keeps the peer's state
     * consistent. The service starts again the next time the app is opened.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.i(TAG, "foreground time limit reached; stopping")
        stopSelf()
    }

    override fun onDestroy() {
        if (startedAt != 0L) {
            val ms = SystemClock.elapsedRealtime() - startedAt
            Usage.add(this, Usage.SERVICE_MS, ms)
            AppLog.i(TAG, "stopped after ${ms / MS_PER_S} s")
        }
        multicast?.release()
        multicast = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.sync_channel), NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shoal)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.sync_notification))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SyncService"
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 1
        private const val MS_PER_S = 1000

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java))
        }
    }
}
