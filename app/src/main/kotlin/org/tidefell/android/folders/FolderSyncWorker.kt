package org.tidefell.android.folders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import org.tidefell.android.Peer
import org.tidefell.android.R
import org.tidefell.android.Usage
import java.util.concurrent.TimeUnit

/**
 * Syncs phone folders while the app is closed: scans them, then keeps the node running until a
 * full replica has confirmed the new files, or for [MAX_WAIT_MS], so photos leave the phone soon
 * after they are taken. Remove after backup runs as confirmations arrive.
 */
class FolderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Peer.init(applicationContext)
        if (!Peer.paired) return Result.success()
        Usage.add(applicationContext, Usage.WORKER_RUNS)
        return Usage.timed(applicationContext, Usage.WORKER_MS) { sync() }
    }

    private suspend fun sync(): Result {
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Log.w(TAG, "running without a foreground notification", it) }
        return try {
            val node = Peer.start()
            val phoneFolders = node.folders().filterNot(PhoneFolders::inAppStorage)
            Usage.timed(applicationContext, Usage.SCAN_MS) {
                for (folder in phoneFolders) node.scan(folder.id)
            }
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < MAX_WAIT_MS) {
                PhoneFolders.removeBackedUp(node)
                val waiting = node.folders().filterNot(PhoneFolders::inAppStorage).sumOf { it.onlyHere.toLong() }
                if (waiting == 0L) break
                // Nothing can be confirmed without a peer; give up early rather than hold the phone awake.
                if (node.peers().isEmpty() && System.currentTimeMillis() - started > NO_PEER_MS) break
                delay(POLL_MS)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "folder sync failed", e)
            Result.retry()
        } finally {
            // A content-triggered run is one-shot; watch for the next new photo.
            if (inputData.getBoolean(KEY_WATCH, false)) FolderSync.watch(applicationContext, fromWatch = true)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.folders_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_tidefell)
            .setContentTitle(applicationContext.getString(R.string.folders_notification))
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "FolderSyncWorker"
        private const val CHANNEL = "folders"
        private const val NOTIFICATION_ID = 2
        private const val MAX_WAIT_MS = 10 * 60_000L
        private const val NO_PEER_MS = 60_000L
        private const val POLL_MS = 15_000L
        internal const val KEY_WATCH = "watch"
    }
}

/** Schedules [FolderSyncWorker]: hourly, and soon after a new photo or video appears. */
object FolderSync {
    private const val PERIODIC = "folders-periodic"
    private const val WATCH = "folders-watch"
    private const val NOW = "folders-now"

    /** Schedules background sync if the phone has folders outside app storage, cancels it if not. */
    fun schedule(context: Context, hasPhoneFolders: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!hasPhoneFolders) {
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(WATCH)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<FolderSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints().build())
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        watch(context)
    }

    /**
     * Runs once as soon as a photo or video is added, within the settings' conditions. [fromWatch]
     * is set by the watching run itself, which queues its successor behind it.
     */
    fun watch(context: Context, fromWatch: Boolean = false) {
        val constraints = constraints()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .build()
        val request = OneTimeWorkRequestBuilder<FolderSyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(FolderSyncWorker.KEY_WATCH to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WATCH,
            if (fromWatch) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Syncs now, whatever the network and battery conditions. */
    fun now(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<FolderSyncWorker>().build())
    }

    private fun constraints(): Constraints.Builder {
        val settings = Peer.settings
        return Constraints.Builder()
            .setRequiredNetworkType(if (settings.syncUnmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(settings.syncChargingOnly)
    }
}
