package org.tidefell.android.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import org.tidefell.android.R
import org.tidefell.android.ui.MainActivity
import org.tidefell.ffi.Node

/**
 * "Remove from phone after backup" (ADR 0020, 0022), on Android 11 and later.
 *
 * While the setting is on, each photo or video that backup writes is recorded as a candidate:
 * its `MediaStore` key and its path in the folder. Items backed up before the setting was turned
 * on are never candidates. A candidate is eligible once its file in the folder has a confirmed
 * copy on a full replica. Eligible items are deleted only through the system's delete request,
 * which Android shows as its own confirmation; a notification opens it, because background work
 * cannot.
 */
object RemoveOriginals {
    /** `MediaStore.createDeleteRequest` needs Android 11. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Opens the system delete request from the notification. */
    const val ACTION = "org.tidefell.android.action.REMOVE_ORIGINALS"

    /** Items backed up while the setting was on: `MediaStore` key to path in the folder. */
    class Candidates(private val prefs: SharedPreferences) {
        fun all(): Map<String, String> = prefs.all.mapNotNull { (key, path) -> (path as? String)?.let { key to it } }.toMap()
        fun record(key: String, path: String) = prefs.edit().putString(key, path).apply()
        fun remove(keys: Collection<String>) = prefs.edit().apply { keys.forEach { remove(it) } }.apply()
        fun clear() = prefs.edit().clear().apply()

        companion object {
            fun of(context: Context) = Candidates(context.getSharedPreferences("remove-originals", Context.MODE_PRIVATE))
        }
    }

    /** An item whose backed-up copy a full replica holds. */
    data class Eligible(val key: String, val uri: Uri, val path: String)

    /**
     * The keys in [candidates] whose path is in [confirmed], the folder paths that have a
     * confirmed copy, in the order they were given.
     */
    fun eligibleKeys(candidates: Map<String, String>, confirmed: Set<String>): List<String> =
        candidates.filterValues { it in confirmed }.keys.toList()

    /** A backup key, `image:<id>` or `video:<id>`, split into its kind and `MediaStore` ID. */
    fun parseKey(key: String): Pair<String, Long>? {
        val kind = key.substringBefore(':', "")
        val id = key.substringAfter(':', "").toLongOrNull() ?: return null
        return if (kind == "image" || kind == "video") kind to id else null
    }

    /**
     * Candidates in [folder] that a full replica has confirmed and that are still on the phone.
     * Candidates whose original is gone, deleted by any means, are forgotten.
     */
    fun eligible(context: Context, node: Node, folder: String): List<Eligible> {
        val store = Candidates.of(context)
        val candidates = store.all()
        if (candidates.isEmpty()) return emptyList()
        val confirmed = node.files(folder).filter { it.confirmedCopies > 0u && !it.onlyHere }.map { it.path }.toSet()
        val gone = mutableListOf<String>()
        val eligible = eligibleKeys(candidates, confirmed).mapNotNull { key ->
            val uri = uriOf(key)
            if (uri == null || !exists(context, uri)) {
                gone += key
                null
            } else {
                Eligible(key, uri, candidates.getValue(key))
            }
        }
        store.remove(gone)
        return eligible
    }

    /** The system's delete request for [items]; Android asks before anything is deleted. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun deleteRequest(context: Context, items: List<Eligible>): IntentSender =
        MediaStore.createDeleteRequest(context.contentResolver, items.map { it.uri }).intentSender

    /** After the system deleted [items], stop tracking them. */
    fun removed(context: Context, items: List<Eligible>) {
        Candidates.of(context).remove(items.map { it.key })
        notifications(context).cancel(NOTIFICATION_ID)
    }

    /**
     * When the setting is turned off: forgets the candidates, so turning it on again applies only
     * to what is backed up from then on, and clears the notification.
     */
    fun turnedOff(context: Context) {
        Candidates.of(context).clear()
        context.getSharedPreferences(NOTIFIED, Context.MODE_PRIVATE).edit().clear().apply()
        notifications(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Posts or updates the notification that opens the delete request when more items are
     * eligible than it last said; clears it when none are.
     */
    fun notifyIfEligible(context: Context, node: Node, folder: String) {
        val count = eligible(context, node, folder).size
        val prefs = context.getSharedPreferences(NOTIFIED, Context.MODE_PRIVATE)
        val notified = prefs.getInt("count", 0)
        prefs.edit().putInt("count", count).apply()
        val manager = notifications(context)
        if (count == 0) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (count <= notified) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.remove_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tidefell)
            .setContentTitle(context.resources.getQuantityString(R.plurals.remove_notification_title, count, count))
            .setContentText(context.getString(R.string.remove_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun uriOf(key: String): Uri? {
        val (kind, id) = parseKey(key) ?: return null
        val collection = if (kind == "image") {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        return ContentUris.withAppendedId(collection, id)
    }

    private fun exists(context: Context, uri: Uri): Boolean =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false

    private fun notifications(context: Context) = context.getSystemService(NotificationManager::class.java)

    private const val CHANNEL = "remove-originals"
    private const val NOTIFICATION_ID = 3
    private const val NOTIFIED = "remove-originals-notified"
}
