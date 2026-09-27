package org.tidefell.android

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.tidefell.android.folders.OnOtherDevices
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.ffi.Node
import org.tidefell.ffi.NodeEvent

/**
 * Every folder as a root in the system file picker and Files app. Listings come from the folder's
 * metadata, so files that are not on the phone appear without being stored, and opening one fetches
 * it from peers.
 *
 * - A folder in app storage lists every file, and saving records a new version (ADR 0016).
 * - A phone folder is already in the Files app where it lives, so its root lists only the files
 *   that are on other devices, read-only. Opening one downloads it into the phone folder
 *   (ADR 0028).
 *
 * Document IDs are `<folder id>/<path>`, with an empty path for the folder itself. Directories
 * are implied by file paths.
 */
class TidefellDocumentsProvider : DocumentsProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var closeHandler: Handler

    /** Documents created but not written yet: document ID to MIME type. */
    private val pending = ConcurrentHashMap<String, String>()

    override fun onCreate(): Boolean {
        Peer.init(context!!)
        closeHandler = Handler(HandlerThread("tidefell-documents").apply { start() }.looper)
        scope.launch {
            Peer.events.collect { event -> notifyChanged(event) }
        }
        return true
    }

    private fun node(): Node = Peer.startBlocking() ?: throw FileNotFoundException("not paired")

    private fun split(documentId: String): Pair<String, String> {
        val slash = documentId.indexOf('/')
        if (slash < 0) throw FileNotFoundException(documentId)
        return documentId.substring(0, slash) to documentId.substring(slash + 1)
    }

    /** True if [folder] is a phone folder, whose root is read-only and lists only other devices' files. */
    private fun isPhoneFolder(node: Node, folder: String): Boolean {
        val info = node.folders().firstOrNull { it.id == folder } ?: throw FileNotFoundException(folder)
        return !PhoneFolders.inAppStorage(info)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        val node = runCatching { node() }.getOrNull() ?: return cursor
        for (folder in node.folders()) {
            val phone = !PhoneFolders.inAppStorage(folder)
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, folder.id)
                add(Root.COLUMN_DOCUMENT_ID, "${folder.id}/")
                add(Root.COLUMN_TITLE, Peer.folderName(folder))
                add(Root.COLUMN_SUMMARY, context!!.getString(if (phone) R.string.on_other_devices else R.string.app_name))
                add(Root.COLUMN_ICON, R.drawable.ic_tidefell)
                add(Root.COLUMN_FLAGS, if (phone) Root.FLAG_SUPPORTS_IS_CHILD else Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val (folder, path) = split(documentId)
        val node = node()
        val writable = !isPhoneFolder(node, folder)
        if (path.isEmpty()) {
            val info = node.folders().first { it.id == folder }
            addDirectory(cursor, documentId, Peer.folderName(info), writable)
            return cursor
        }
        pending[documentId]?.let { mime ->
            addFile(cursor, documentId, path.substringAfterLast('/'), mime, 0, null, writable)
            return cursor
        }
        // A file stays reachable here after it is downloaded, so documents already open keep working.
        val files = node.files(folder)
        val file = files.firstOrNull { it.path == path }
        when {
            file != null -> addFile(cursor, documentId, file.path.substringAfterLast('/'), mimeOf(file.path), file.size.toLong(), file.modifiedMs, writable)
            files.any { it.path.startsWith("$path/") } -> addDirectory(cursor, documentId, path.substringAfterLast('/'), writable)
            else -> throw FileNotFoundException(documentId)
        }
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val (folder, parent) = split(parentDocumentId)
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        val node = node()
        val phone = isPhoneFolder(node, folder)
        val files = node.files(folder).let { if (phone) OnOtherDevices.files(it) else it }
        val directories = sortedSetOf<String>()
        for (file in files) {
            if (!file.path.startsWith(prefix)) continue
            val rest = file.path.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                directories.add(rest.substring(0, slash))
            } else {
                addFile(cursor, "$folder/${file.path}", rest, mimeOf(rest), file.size.toLong(), file.modifiedMs, !phone)
            }
        }
        for (name in directories) addDirectory(cursor, "$folder/$prefix$name", name, !phone)
        cursor.setNotificationUri(
            context!!.contentResolver,
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId),
        )
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (parentFolder, parent) = split(parentDocumentId)
        val (folder, path) = split(documentId)
        return parentFolder == folder && (parent.isEmpty() || path.startsWith("$parent/"))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (mimeType == Document.MIME_TYPE_DIR) {
            // Directories exist only through the files in them.
            throw UnsupportedOperationException("empty directories are not synced")
        }
        val (folder, parent) = split(parentDocumentId)
        if (isPhoneFolder(node(), folder)) throw UnsupportedOperationException("read-only: files on other devices")
        val name = displayName.replace('/', '_')
        val documentId = if (parent.isEmpty()) "$folder/$name" else "$folder/$parent/$name"
        pending[documentId] = mimeType
        return documentId
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val (folder, path) = split(documentId)
        val node = node()
        val access = ParcelFileDescriptor.parseMode(mode)
        val phone = isPhoneFolder(node, folder)
        // MODE_READ_WRITE includes the read bit, so only the write bit tells a write.
        if (access and ParcelFileDescriptor.MODE_WRITE_ONLY == 0) {
            // Read: fetch the file if it is a placeholder; it stays pinned until closed. In a phone
            // folder, fetching downloads it into the folder for good.
            if (phone) PhoneFolders.keepDownloaded(node, folder, path)
            val local = try {
                runBlocking { node.open(folder, path) }
            } catch (e: Exception) {
                throw FileNotFoundException("$path: ${e.message}")
            }
            return ParcelFileDescriptor.open(File(local), access, closeHandler) {
                runCatching { node.close(folder, path) }
            }
        }
        if (phone) throw FileNotFoundException("$path: read-only, edit it in the phone folder")
        // Write: collect the new content in a temporary file and record it when closed.
        val temp = File.createTempFile("write", null, context!!.cacheDir)
        if (access and ParcelFileDescriptor.MODE_TRUNCATE == 0) {
            runCatching { runBlocking { node.open(folder, path) } }.getOrNull()?.let { existing ->
                File(existing).copyTo(temp, overwrite = true)
                node.close(folder, path)
            }
        }
        return ParcelFileDescriptor.open(temp, access, closeHandler) { error ->
            scope.launch {
                if (error == null) {
                    runCatching { node.write(folder, path, temp.path) }
                        .onFailure { Log.e(TAG, "saving $path failed", it) }
                }
                pending.remove(documentId)
                temp.delete()
            }
        }
    }

    private fun notifyChanged(event: NodeEvent) {
        val resolver = context?.contentResolver ?: return
        val (folder, path) = when (event) {
            is NodeEvent.FileUpdated -> event.folder to event.path
            is NodeEvent.FileRemoved -> event.folder to event.path
            is NodeEvent.FolderChanged -> event.folder to ""
            is NodeEvent.FolderAdded, is NodeEvent.FolderRemoved -> {
                resolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)
                return
            }
            is NodeEvent.ConflictDetected, is NodeEvent.Crashed -> return
        }
        val parent = path.substringBeforeLast('/', "")
        resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, "$folder/$parent"), null)
        if (parent.isNotEmpty()) {
            resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, "$folder/"), null)
        }
    }

    private fun addFile(cursor: MatrixCursor, id: String, name: String, mime: String, size: Long, modified: Long?, writable: Boolean) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, size)
            add(Document.COLUMN_LAST_MODIFIED, modified)
            add(Document.COLUMN_FLAGS, if (writable) Document.FLAG_SUPPORTS_WRITE else 0)
        }
    }

    private fun addDirectory(cursor: MatrixCursor, id: String, name: String, writable: Boolean) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_SIZE, null)
            add(Document.COLUMN_LAST_MODIFIED, null)
            add(Document.COLUMN_FLAGS, if (writable) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
        }
    }

    companion object {
        const val AUTHORITY = "org.tidefell.android.documents"
        private const val TAG = "TidefellDocuments"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
    }
}
