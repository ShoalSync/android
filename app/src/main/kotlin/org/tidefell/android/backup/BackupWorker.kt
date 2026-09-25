package org.tidefell.android.backup

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
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
import java.util.concurrent.TimeUnit
import org.tidefell.android.Peer
import org.tidefell.android.R

/** Runs [CameraRollBackup] into the folder chosen in settings. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Peer.init(applicationContext)
        val settings = Peer.settings
        val folder = settings.backupFolder
        if (folder.isEmpty() || !Peer.paired || !Backups.hasPermission(applicationContext)) return Result.success()
        runCatching { setForeground(foregroundInfo(0, 0)) }
            .onFailure { Log.w(TAG, "running without a foreground notification", it) }
        return try {
            val node = Peer.start()
            val removing = settings.removeOriginals && RemoveOriginals.supported
            val candidates = if (removing) RemoveOriginals.Candidates.of(applicationContext) else null
            val written = CameraRollBackup.run(
                applicationContext, node, folder, settings.backupVideos, settings.backupAlbums, candidates,
            ) { done, total ->
                runCatching { setForegroundAsync(foregroundInfo(done, total)) }
            }
            Log.i(TAG, "backed up $written new photos and videos")
            if (removing) {
                RemoveOriginals.notifyIfEligible(applicationContext, node, folder)
                // A full replica confirms new copies within minutes; look again then, not in an hour.
                if (written > 0) Backups.later(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "backup failed", e)
            Result.retry()
        } finally {
            // A content-triggered run is one-shot; watch for the next new photo.
            if (inputData.getBoolean(KEY_WATCH, false)) Backups.watch(applicationContext, fromWatch = true)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.backup_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_tidefell)
            .setContentTitle(applicationContext.getString(R.string.backup_notification))
            .setOngoing(true)
            .apply { if (total > 0) setProgress(total, done, false).setContentText("$done of $total") }
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "BackupWorker"
        private const val CHANNEL = "backup"
        private const val NOTIFICATION_ID = 2
        internal const val KEY_WATCH = "watch"
    }
}

/** Schedules camera-roll backup: hourly, and soon after a new photo or video appears. */
object Backups {
    private const val PERIODIC = "backup-periodic"
    private const val WATCH = "backup-watch"
    private const val NOW = "backup-now"
    private const val LATER = "backup-later"

    /** The permissions backup needs on this Android version. */
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasPermission(context: Context): Boolean =
        permissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Applies the settings: schedules backup if a folder is chosen, cancels it otherwise. */
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (Peer.settings.backupFolder.isEmpty()) {
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(WATCH)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints().build())
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        watch(context)
    }

    /**
     * Runs once as soon as a new photo or video is added, within the settings' conditions.
     * [fromWatch] is set by the watching run itself, which queues its successor behind it.
     */
    fun watch(context: Context, fromWatch: Boolean = false) {
        if (Peer.settings.backupFolder.isEmpty()) return
        val constraints = constraints()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .apply { if (Peer.settings.backupVideos) addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true) }
            .build()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(BackupWorker.KEY_WATCH to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WATCH, if (fromWatch) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
    }

    /** Backs up now, whatever the network and battery conditions. */
    fun now(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BackupWorker>().build())
    }

    /**
     * Runs once in a few minutes, so that newly confirmed copies are offered for removal soon.
     * A run with nothing new to back up only looks at what is eligible.
     */
    fun later(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(5, TimeUnit.MINUTES)
            .setConstraints(constraints().build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(LATER, ExistingWorkPolicy.REPLACE, request)
    }

    private fun constraints(): Constraints.Builder {
        val settings = Peer.settings
        return Constraints.Builder()
            .setRequiredNetworkType(if (settings.backupUnmeteredOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .setRequiresCharging(settings.backupChargingOnly)
    }
}
