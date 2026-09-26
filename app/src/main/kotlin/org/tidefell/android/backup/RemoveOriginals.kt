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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * cannot. Checking which items are eligible queries `MediaStore`, on [Dispatchers.IO].
 */
object RemoveOriginals {
    /** `MediaStore.createDeleteRequest` needs Android 11. */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Opens the system delete request from the notification. */
    const val ACTION = "org.tidefell.android.action.REMOVE_ORIGINALS"

    /**
     * Items backed up while the setting was on: `MediaStore` key to the item's [Stamp] when it
     * was backed up and its path in the folder.
     */
    class Candidates(private val prefs: SharedPreferences) {
        /** Every stored value, decoded; null for one stored before stamps were kept. */
        fun all(): Map<String, Candidate?> =
            prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to Candidate.decode(it) } }.toMap()
        fun record(key: String, candidate: Candidate) = prefs.edit().putString(key, candidate.encode()).apply()

        /** The candidate [key], if there is one, now has [stamp]: its content is unchanged. */
        fun restamp(key: String, stamp: Stamp) {
            val candidate = prefs.getString(key, null)?.let(Candidate::decode) ?: return
            record(key, candidate.copy(stamp = stamp))
        }
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
    fun eligibleKeys(candidates: Map<String, Candidate>, confirmed: Set<String>): List<String> =
        candidates.filterValues { it.path in confirmed }.keys.toList()

    /**
     * Whether an item is still the one that was backed up: it exists ([current] is its stamp
     * now) and has not been edited since. An edited original is not what the folder holds, so
     * it must not be offered for removal (ADR 0022).
     */
    fun unchanged(recorded: Stamp, current: Stamp?): Boolean = current == recorded

    /** A backup key, `image:<id>` or `video:<id>`, split into its kind and `MediaStore` ID. */
    fun parseKey(key: String): Pair<String, Long>? {
        val kind = key.substringBefore(':', "")
        val id = key.substringAfter(':', "").toLongOrNull() ?: return null
        return if (kind == "image" || kind == "video") kind to id else null
    }

    /**
     * Candidates in [folder] that a full replica has confirmed and that are still on the phone
     * as they were backed up. Candidates whose original is gone, deleted by any means, are
     * forgotten. One whose stamp has changed is not offered: the next backup run reads it again
     * and either restamps it, if its content is unchanged, or backs up the edited item, which
     * replaces the candidate.
     */
    suspend fun eligible(context: Context, node: Node, folder: String): List<Eligible> = withContext(Dispatchers.IO) {
        val store = Candidates.of(context)
        val stored = store.all()
        if (stored.isEmpty()) return@withContext emptyList()
        // Candidates stored before stamps were kept cannot be checked, so they are never offered.
        val unverifiable = stored.filterValues { it == null }.keys
        val candidates = stored.mapNotNull { (key, c) -> c?.let { key to it } }.toMap()
        val confirmed = node.files(folder).filter { it.confirmedCopies > 0u && !it.onlyHere }.map { it.path }.toSet()
        val dropped = unverifiable.toMutableList()
        val eligible = eligibleKeys(candidates, confirmed).mapNotNull { key ->
            val candidate = candidates.getValue(key)
            val current = uriOf(key)?.let { uri -> stampOf(context, uri)?.let { uri to it } }
            when {
                current == null -> {
                    dropped += key
                    null
                }
                !unchanged(candidate.stamp, current.second) -> null
                else -> Eligible(key, current.first, candidate.path)
            }
        }
        store.remove(dropped)
        eligible
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
    suspend fun notifyIfEligible(context: Context, node: Node, folder: String) {
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

    /** The item's stamp now, or null if it no longer exists. */
    private fun stampOf(context: Context, uri: Uri): Stamp? =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            Stamp(cursor.getLong(0), cursor.getLong(1))
        }

    private fun notifications(context: Context) = context.getSystemService(NotificationManager::class.java)

    private const val CHANNEL = "remove-originals"
    private const val NOTIFICATION_ID = 3
    private const val NOTIFIED = "remove-originals-notified"
}
