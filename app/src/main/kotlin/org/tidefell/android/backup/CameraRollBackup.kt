package org.tidefell.android.backup

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tidefell.ffi.Node

/**
 * Copies new photos and videos from the phone's `MediaStore` into a folder (ADR 0020).
 *
 * Upload only: nothing on the phone is changed or deleted, and deleting a backed-up file from the
 * folder never touches the phone. With "remove from phone after backup" on, what is written is
 * recorded for [RemoveOriginals], which deletes originals only through the system's request. Each item is recorded by its `MediaStore` ID and content hash,
 * so running again, renaming a photo or re-adding the same one does not upload a duplicate. After
 * a reinstall, when that record is gone, an item is skipped if a file of the same name and size
 * is already in its month's directory. An item edited in place after it was backed up (its size
 * or modification time changed) is backed up again, as a new file beside the first.
 *
 * Only one run happens at a time in a process, whatever started it.
 */
object CameraRollBackup {
    private data class Item(
        val key: String,
        val uri: Uri,
        val name: String,
        val takenMs: Long,
        val size: Long,
        val album: Album,
        val stamp: Stamp,
    )

    private val running = Mutex()

    /** A camera-roll album: `MediaStore`'s bucket. */
    data class Album(val id: String, val name: String)

    /**
     * What has been backed up: content hashes, and `MediaStore` keys with the [Stamp] each item
     * had. Items recorded before stamps were kept are in the legacy key set and count as backed
     * up whatever their stamp.
     */
    class Ledger(private val prefs: SharedPreferences, private val stamps: SharedPreferences) {
        /** True if the item [key] was backed up as it is now, [stamp]. */
        fun isBackedUp(key: String, stamp: Stamp): Boolean =
            when (val recorded = stamps.getString(key, null)) {
                null -> key in prefs.getStringSet(ITEMS, emptySet())!!
                else -> Stamp.decode(recorded) == stamp
            }

        fun hasContent(hash: String) = hash in prefs.getStringSet(HASHES, emptySet())!!

        fun record(key: String, hash: String, stamp: Stamp) {
            stamps.edit().putString(key, stamp.encode()).apply()
            if (hash.isNotEmpty()) {
                prefs.edit()
                    .putStringSet(HASHES, prefs.getStringSet(HASHES, emptySet())!! + hash)
                    .apply()
            }
        }

        fun clear() {
            prefs.edit().clear().apply()
            stamps.edit().clear().apply()
        }

        companion object {
            private const val ITEMS = "items"
            private const val HASHES = "hashes"
            fun of(context: Context) = Ledger(
                context.getSharedPreferences("backup-ledger", Context.MODE_PRIVATE),
                context.getSharedPreferences("backup-stamps", Context.MODE_PRIVATE),
            )
        }
    }

    /**
     * Backs up every photo (and, with [includeVideos], video) not yet recorded, oldest first,
     * into [folder]. With [albums], only items in those albums (bucket IDs); empty means all.
     * Returns how many were written. [progress] gets (done, total). Each item written is recorded
     * in [candidates] if given.
     */
    suspend fun run(
        context: Context,
        node: Node,
        folder: String,
        includeVideos: Boolean,
        albums: Set<String> = emptySet(),
        candidates: RemoveOriginals.Candidates? = null,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Int = running.withLock {
        val ledger = Ledger.of(context)
        val pending = items(context, includeVideos)
            .filter { albums.isEmpty() || it.album.id in albums }
            .filterNot { ledger.isBackedUp(it.key, it.stamp) }
        if (pending.isEmpty()) return@withLock 0
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
                ledger.record(item.key, "", item.stamp)
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
                candidates?.record(item.key, Candidate(item.stamp, path))
                written++
            }
            ledger.record(item.key, hash, item.stamp)
        }
        progress(pending.size, pending.size)
        written
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
            MediaStore.MediaColumns.DATE_MODIFIED,
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
                val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
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
                        stamp = Stamp(cursor.getLong(size), cursor.getLong(modified)),
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
