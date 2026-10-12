package org.tidefell.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.tidefell.android.Peer
import org.tidefell.android.TidefellDocumentsProvider
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.ffi.Crash
import org.tidefell.ffi.Folder
import org.tidefell.ffi.Version
import org.tidefell.ffi.VersionKind
import org.tidefell.ffi.crashReport
import java.io.File

/** The earlier versions of a file kept on this phone, any of which can be restored (ADR 0029). */
@Composable
fun HistoryPage(model: HomeModel, folder: Folder, path: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val versions by model.versions.collectAsStateWithLifecycle()
    var restoring by remember { mutableStateOf<Version?>(null) }
    val earlier = versions.filter { it.kind == VersionKind.PREVIOUS }
    Text(path, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    if (earlier.isEmpty()) {
        EmptyState(TidefellIcons.History, "No earlier versions are kept on this phone.")
        return
    }
    Text(
        "Versions this phone kept when the file was replaced. Restoring one makes it the file again on all your devices.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    LazyColumn {
        items(earlier, key = { it.id }) { version ->
            ListItem(
                leadingContent = { IconTile(TidefellIcons.History) },
                headlineContent = { Text("Modified ${relative(version.modifiedMs)}") },
                supportingContent = {
                    Text(
                        "${size(context, version.size)} · replaced ${relative(version.keptMs ?: 0)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.clickable { restoring = version },
            )
        }
    }
    restoring?.let { version ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("Restore this version?") },
            text = {
                Text(
                    "$path changes back to the version modified ${relative(
                        version.modifiedMs
                    )} on all your devices. The current one is kept as an earlier version."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    model.restoreVersion(folder.id, path, version)
                    restoring = null
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch { open(context, model, folder, path, version) }
                }) { Text("Open") }
            },
        )
    }
}

/**
 * A file changed on two devices while they were apart (ADR 0029): each version with its device,
 * time and size, to open and compare, keep one, keep both, or decide later.
 */
@Composable
fun ConflictPage(model: HomeModel, folder: Folder, path: String, onLater: () -> Unit) {
    val versions by model.versions.collectAsStateWithLifecycle()
    val waiting = versions.filter { it.kind != VersionKind.PREVIOUS }
    LazyColumn {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(path, style = MaterialTheme.typography.titleMedium)
                if (waiting.size < 2) {
                    Text("There is nothing to decide: this file has one version now.")
                    OutlinedButton(onClick = onLater) { Text("Back") }
                } else {
                    Banner(
                        TidefellIcons.Warning,
                        "This file was changed on more than one device while they could not reach each other. Your devices " +
                            "show the first version below until you decide. Nothing is lost in the meantime.",
                    )
                }
            }
        }
        if (waiting.size >= 2) {
            items(waiting, key = { it.id }) { version ->
                ConflictVersion(model, folder, path, version)
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Keeping one sends the others to every device's recycle bin, where they can be restored for a while. " +
                            "Keeping both leaves the version shown now as the file and puts each other one beside it, named " +
                            "after its device and day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { model.resolve(folder.id, path, "both") }) { Text("Keep both") }
                        TextButton(onClick = onLater) { Text("Decide later") }
                    }
                }
            }
        }
    }
}

/** One version of a file in conflict: whose it is, when it changed, and keeping it. */
@Composable
private fun ConflictVersion(model: HomeModel, folder: Folder, path: String, version: Version) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val label = if (version.kind == VersionKind.CURRENT) "Shown now" else "Other version"
    SectionCard(label, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(TidefellIcons.Devices)
            Column {
                Text(version.authorName ?: "Another device", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Modified ${relative(version.modifiedMs)} · ${size(context, version.size)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { open(context, model, folder, path, version) } }) { Text("Open") }
            Button(onClick = { model.resolve(folder.id, path, version.id) }) { Text("Keep this one") }
        }
    }
}

/**
 * Tidefell stopped unexpectedly (ADR 0030): after an error in the engine while running, with
 * the option to start syncing again, or before the last start, with the option to report it.
 */
@Composable
fun CrashNotice(model: HomeModel) {
    val context = LocalContext.current
    val crashed by model.crashed.collectAsStateWithLifecycle()
    val crashes by model.crashes.collectAsStateWithLifecycle()
    val newest = crashes.maxByOrNull { it.timeMs }
    if (crashed == null && newest == null) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(TidefellIcons.Error, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Text(
                    if (crashed != null) "Syncing stopped because of an error" else "Tidefell stopped unexpectedly",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(crashed ?: newest!!.message, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (crashed != null) OutlinedButton(onClick = model::restart) { Text("Start syncing again") }
                if (newest != null) {
                    TextButton(onClick = { report(context, newest) }) { Text("Report") }
                    TextButton(onClick = model::dismissCrashes) { Text("Dismiss") }
                }
            }
        }
    }
}

/**
 * Reports a crash (ADR 0030): opens a new issue on the tracker, filled in with the report for the
 * user to read and send, or, without a tracker, shares the report. Home directories are taken out.
 */
private fun report(context: Context, crash: Crash) {
    val report = runCatching {
        crashReport(Peer.dataDir, crash.id, Peer.settings.reportUrl.ifEmpty { null })
    }.getOrNull()
    val url = report?.url
    if (url != null) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (runCatching { context.startActivity(view) }.isSuccess) return
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, report?.title ?: "Tidefell crash report")
        .putExtra(Intent.EXTRA_TEXT, report?.body ?: crash.message)
    runCatching { context.startActivity(Intent.createChooser(send, "Report the crash")) }
}

/** Opens [version] of [path] read-only in another app. */
private suspend fun open(context: Context, model: HomeModel, folder: Folder, path: String, version: Version) {
    val uri = runCatching {
        when {
            version.kind != VersionKind.CURRENT ->
                FileProvider.getUriForFile(context, FILES, File(model.openVersion(folder.id, path, version)))
            PhoneFolders.inAppStorage(folder) ->
                android.provider.DocumentsContract.buildDocumentUri(TidefellDocumentsProvider.AUTHORITY, "${folder.id}/$path")
            else -> FileProvider.getUriForFile(context, FILES, File(folder.path, path))
        }
    }.getOrElse {
        model.show(it.message ?: "This version is not available right now.")
        return
    }
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, TidefellDocumentsProvider.mimeOf(path))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(view, path.substringAfterLast('/'))) }
}

/** The authority of the app's `FileProvider`, in the manifest. */
private const val FILES = "org.tidefell.android.files"

private fun relative(ms: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

private fun size(context: Context, bytes: ULong): String = Formatter.formatShortFileSize(context, bytes.toLong())
