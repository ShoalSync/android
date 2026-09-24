package org.tidefell.android.backup

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import org.tidefell.ffi.Node

/**
 * Copies new photos and videos from the phone's `MediaStore` into a folder (ADR 0020).
 *
 * Upload only: nothing on the phone is changed or deleted, and deleting a backed-up file from the
 * folder never touches the phone. Each item is recorded by its `MediaStore` ID and content hash,
 * so running again, renaming a photo or re-adding the same one does not upload a duplicate. After
 * a reinstall, when that record is gone, an item is skipped if a file of the same name and size
 * is already in its month's directory.
 */
object CameraRollBackup {
    private data class Item(val key: String, val uri: Uri, val name: String, val takenMs: Long, val size: Long, val album: Album)

    /** A camera-roll album: `MediaStore`'s bucket. */
    data class Album(val id: String, val name: String)

    /** What has been backed up: `MediaStore` keys and content hashes. */
    class Ledger(private val prefs: SharedPreferences) {
        fun hasItem(key: String) = key in prefs.getStringSet(ITEMS, emptySet())!!
        fun hasContent(hash: String) = hash in prefs.getStringSet(HASHES, emptySet())!!
        fun record(key: String, hash: String) {
            prefs.edit()
                .putStringSet(ITEMS, prefs.getStringSet(ITEMS, emptySet())!! + key)
                .putStringSet(HASHES, prefs.getStringSet(HASHES, emptySet())!! + hash)
                .apply()
        }
        fun clear() = prefs.edit().clear().apply()

        companion object {
            private const val ITEMS = "items"
            private const val HASHES = "hashes"
            fun of(context: Context) = Ledger(context.getSharedPreferences("backup-ledger", Context.MODE_PRIVATE))
        }
    }

    /**
     * Backs up every photo (and, with [includeVideos], video) not yet recorded, oldest first,
     * into [folder]. With [albums], only items in those albums (bucket IDs); empty means all.
     * Returns how many were written. [progress] gets (done, total).
     */
    suspend fun run(
        context: Context,
        node: Node,
        folder: String,
        includeVideos: Boolean,
        albums: Set<String> = emptySet(),
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Int {
        val ledger = Ledger.of(context)
        val pending = items(context, includeVideos)
            .filter { albums.isEmpty() || it.album.id in albums }
            .filterNot { ledger.hasItem(it.key) }
        if (pending.isEmpty()) return 0
        val sizes = node.files(folder).associate { it.path to it.size.toLong() }
        val taken = sizes.keys.toMutableSet()
        val staging = File(context.cacheDir, "backup").apply { mkdirs() }
        val zone = ZoneId.systemDefault()
        var written = 0
        pending.forEachIndexed { index, item ->
            progress(index, pending.size)
            val zoned = BackupPlan.targetPath(item.name, item.takenMs, zone, emptySet())
            if (BackupPlan.variants(zoned, taken).any { sizes[it] == item.size }) {
                // Backed up before a reinstall cleared the ledger.
                ledger.record(item.key, "")
                return@forEachIndexed
            }
            val copy = File(staging, "item-${item.key.replace(':', '-')}")
            val hash = try {
                copyAndHash(context, item.uri, copy)
            } catch (e: java.io.IOException) {
                // Gone or unreadable since it was listed; try again next run.
                copy.delete()
                return@forEachIndexed
            }
            if (ledger.hasContent(hash)) {
                copy.delete()
            } else {
                val path = BackupPlan.targetPath(item.name, item.takenMs, zone, taken)
                try {
                    node.write(folder, path, copy.path)
                } finally {
                    copy.delete()
                }
                taken += path
                written++
            }
            ledger.record(item.key, hash)
        }
        progress(pending.size, pending.size)
        return written
    }

    /** Albums that hold photos or, with [includeVideos], videos, by name. */
    fun albums(context: Context, includeVideos: Boolean): List<Album> =
        items(context, includeVideos).map { it.album }.distinct().sortedBy { it.name.lowercase() }

    private fun items(context: Context, includeVideos: Boolean): List<Item> {
        val collections = buildList {
            add("image" to MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
            if (includeVideos) add("video" to MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        return collections.flatMap { (kind, collection) ->
            val items = mutableListOf<Item>()
            context.contentResolver.query(
                collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} ASC",
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val added = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val bucket = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                val bucketName = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val itemId = cursor.getLong(id)
                    // DATE_TAKEN is in milliseconds and may be missing; DATE_ADDED is in seconds.
                    val takenMs = if (cursor.isNull(takenCol)) cursor.getLong(added) * 1000 else cursor.getLong(takenCol)
                    items += Item(
                        key = "$kind:$itemId",
                        uri = ContentUris.withAppendedId(collection, itemId),
                        name = cursor.getString(name) ?: "$kind-$itemId",
                        takenMs = takenMs,
                        size = cursor.getLong(size),
                        album = (cursor.getString(bucket) ?: "").let { album ->
                            Album(album, cursor.getString(bucketName) ?: album)
                        },
                    )
                }
            }
            items
        }.sortedBy { it.takenMs }
    }

    private fun copyAndHash(context: Context, uri: Uri, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw java.io.FileNotFoundException(uri.toString())
        input.use { source ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
