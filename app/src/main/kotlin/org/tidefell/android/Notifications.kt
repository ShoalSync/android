package org.tidefell.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.tidefell.android.ui.MainActivity
import org.tidefell.ffi.Node
import org.tidefell.ffi.VersionKind

/** Notifications that ask the user to decide something (ADR 0029). */
object Notifications {
    private const val CONFLICTS = "conflicts"
    const val EXTRA_FOLDER = "org.tidefell.android.FOLDER"
    const val EXTRA_PATH = "org.tidefell.android.PATH"

    /**
     * A file in [folder] was changed on two devices while they were apart: "notes.txt was changed
     * on Laptop and on this phone". Opening it shows the file's conflict screen.
     */
    fun conflict(context: Context, node: Node, folder: String, path: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CONFLICTS, context.getString(R.string.conflicts_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val me = node.status().device
        val devices = node.versions(folder, path)
            .filter { it.kind != VersionKind.PREVIOUS }
            .map { if (it.author == me) "this phone" else it.authorName ?: "another device" }
            .distinct()
        val name = path.substringAfterLast('/')
        val text = when (devices.size) {
            0, 1 -> "$name was changed on two devices at once."
            else -> "$name was changed on ${devices.dropLast(1).joinToString(", ")} and on ${devices.last()}."
        }
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_FOLDER, folder)
            .putExtra(EXTRA_PATH, path)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            "$folder/$path".hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, CONFLICTS)
            .setSmallIcon(R.drawable.ic_tidefell)
            .setContentTitle("Choose which version to keep")
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify("conflict", "$folder/$path".hashCode(), notification)
    }
}
