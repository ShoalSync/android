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
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.ffi.Node
import org.tidefell.ffi.NodeEvent

/**
 * Every folder in app storage as a root in the system file picker and Files app (ADR 0016). Listings come
 * from the folder's metadata, so placeholders appear without being stored; opening one fetches it
 * from peers, and saving records a new version.
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

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        val node = runCatching { node() }.getOrNull() ?: return cursor
        // Folders on the phone are already in the Files app where they live (ADR 0025).
        for (folder in node.folders().filter(PhoneFolders::inAppStorage)) {
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, folder.id)
                add(Root.COLUMN_DOCUMENT_ID, "${folder.id}/")
                add(Root.COLUMN_TITLE, Peer.folderName(folder))
                add(Root.COLUMN_SUMMARY, context!!.getString(R.string.app_name))
                add(Root.COLUMN_ICON, R.drawable.ic_tidefell)
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val (folder, path) = split(documentId)
        val node = node()
        if (path.isEmpty()) {
            val info = node.folders().firstOrNull { it.id == folder } ?: throw FileNotFoundException(documentId)
            addDirectory(cursor, documentId, Peer.folderName(info))
            return cursor
        }
        pending[documentId]?.let { mime ->
            addFile(cursor, documentId, path.substringAfterLast('/'), mime, 0, null)
            return cursor
        }
        val files = node.files(folder)
        val file = files.firstOrNull { it.path == path }
        when {
            file != null -> addFile(cursor, documentId, file.path.substringAfterLast('/'), mimeOf(file.path), file.size.toLong(), file.modifiedMs)
            files.any { it.path.startsWith("$path/") } -> addDirectory(cursor, documentId, path.substringAfterLast('/'))
            else -> throw FileNotFoundException(documentId)
        }
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val (folder, parent) = split(parentDocumentId)
        val prefix = if (parent.isEmpty()) "" else "$parent/"
        val directories = sortedSetOf<String>()
        for (file in node().files(folder)) {
            if (!file.path.startsWith(prefix)) continue
            val rest = file.path.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                directories.add(rest.substring(0, slash))
            } else {
                addFile(cursor, "$folder/${file.path}", rest, mimeOf(rest), file.size.toLong(), file.modifiedMs)
            }
        }
        for (name in directories) addDirectory(cursor, "$folder/$prefix$name", name)
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
        val name = displayName.replace('/', '_')
        val documentId = if (parent.isEmpty()) "$folder/$name" else "$folder/$parent/$name"
        pending[documentId] = mimeType
        return documentId
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val (folder, path) = split(documentId)
        val node = node()
        val access = ParcelFileDescriptor.parseMode(mode)
        if (access and ParcelFileDescriptor.MODE_WRITE_ONLY == 0 && access and ParcelFileDescriptor.MODE_READ_WRITE == 0) {
            // Read: fetch the file if it is a placeholder; it stays pinned until closed.
            val local = try {
                runBlocking { node.open(folder, path) }
            } catch (e: Exception) {
                throw FileNotFoundException("$path: ${e.message}")
            }
            return ParcelFileDescriptor.open(File(local), access, closeHandler) {
                runCatching { node.close(folder, path) }
            }
        }
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
        }
        val parent = path.substringBeforeLast('/', "")
        resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, "$folder/$parent"), null)
        if (parent.isNotEmpty()) {
            resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, "$folder/"), null)
        }
    }

    private fun addFile(cursor: MatrixCursor, id: String, name: String, mime: String, size: Long, modified: Long?) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_SIZE, size)
            add(Document.COLUMN_LAST_MODIFIED, modified)
            add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_WRITE)
        }
    }

    private fun addDirectory(cursor: MatrixCursor, id: String, name: String) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_SIZE, null)
            add(Document.COLUMN_LAST_MODIFIED, null)
            add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
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
