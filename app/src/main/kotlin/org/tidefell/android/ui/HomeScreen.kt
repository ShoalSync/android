package org.tidefell.android.ui

import android.content.Intent
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.tidefell.android.Peer
import org.tidefell.android.TidefellDocumentsProvider
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.SyncChoice
import org.tidefell.ffi.File
import org.tidefell.ffi.Folder
import org.tidefell.ffi.Operation
import org.tidefell.ffi.PathProblem
import org.tidefell.ffi.ProblemKind
import org.tidefell.ffi.Recycled

/** Folders, their files and recycle bins, settings, and adding folders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: HomeViewModel = viewModel()) {
    val node by model.node.collectAsStateWithLifecycle()
    val page by model.page.collectAsStateWithLifecycle()
    val folders by model.folders.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            model.messageShown()
        }
    }
    BackHandler(enabled = page.parent != null && page != Page.AddFolder) { model.back() }

    val folderId = when (val p = page) {
        is Page.Files -> p.folder
        is Page.RecycleBin -> p.folder
        is Page.History -> p.folder
        is Page.Conflict -> p.folder
        is Page.Activity -> p.folder
        else -> null
    }
    val folder = folders.firstOrNull { it.id == folderId }
    var cleaningUp by rememberSaveable { mutableStateOf(false) }
    val title = when (page) {
        Page.Folders -> "Tidefell"
        Page.Settings -> "Settings"
        Page.AddFolder -> "Add a folder"
        Page.Status -> "Status"
        is Page.Activity -> "Activity"
        is Page.Files -> folder?.let(Peer::folderName) ?: ""
        is Page.RecycleBin -> "Recycle bin"
        is Page.History -> "Earlier versions"
        is Page.Conflict -> "Choose a version"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = {
                    when (page) {
                        Page.Folders -> {
                            TextButton(onClick = { model.go(Page.Status) }) { Text("Status") }
                            TextButton(onClick = { model.go(Page.Settings) }) { Text("Settings") }
                        }
                        is Page.Files -> {
                            if (folder != null) {
                                TextButton(onClick = { if (folder.paused) model.resume(folder.id) else model.pause(folder.id) }) {
                                    Text(if (folder.paused) "Resume" else "Pause")
                                }
                            }
                            if (folder != null && PhoneFolders.choice(folder).let { it == SyncChoice.BACKUP_ONLY || it == SyncChoice.TWO_WAY }) {
                                TextButton(onClick = { cleaningUp = true }) { Text("Clean up") }
                            }
                            TextButton(onClick = { model.go(Page.RecycleBin(folderId!!)) }) { Text("Recycle bin") }
                        }
                        else -> {}
                    }
                },
            )
        },
        floatingActionButton = {
            if (page == Page.Folders && node != null) {
                ExtendedFloatingActionButton(onClick = { model.go(Page.AddFolder) }) { Text("Add folder") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                node == null -> CircularProgressIndicator(Modifier.padding(24.dp))
                page == Page.Settings -> SettingsScreen()
                page == Page.Status -> StatusPage()
                page is Page.Activity && folder != null -> ActivityPage(model)
                page == Page.AddFolder -> AddFolderScreen(
                    offer = null,
                    onDone = { model.go(Page.Files(it.id)) },
                    onCancel = { model.back() },
                )
                page is Page.Files && folder != null -> FileList(model, folder)
                page is Page.RecycleBin && folder != null -> RecycleBin(model, folder)
                page is Page.History && folder != null -> HistoryPage(model, folder, (page as Page.History).path)
                page is Page.Conflict && folder != null -> ConflictPage(model, folder, (page as Page.Conflict).path) { model.back() }
                else -> {
                    CrashNotice(model)
                    DevicePause(model)
                    FolderList(folders) { model.go(Page.Files(it.id)) }
                }
            }
        }
    }
    if (cleaningUp && folder != null) CleanUpDialog(model, folder) { cleaningUp = false }
}

/** Pausing syncing on the whole phone (ADR 0029). */
@Composable
private fun DevicePause(model: HomeViewModel) {
    val status by model.status.collectAsStateWithLifecycle()
    val paused = status?.paused ?: return
    if (paused) {
        Text(
            "Syncing is paused on this phone. Changes here are recorded and sent when it resumes.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    TextButton(onClick = { if (paused) model.resume(null) else model.pause(null) }, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(if (paused) "Resume syncing" else "Pause syncing on this phone")
    }
}

@Composable
private fun FolderList(folders: List<Folder>, onOpen: (Folder) -> Unit) {
    if (folders.isEmpty()) {
        Text("No folders yet. Add one, such as the camera folder, to back it up.", Modifier.padding(24.dp))
    }
    LazyColumn {
        items(folders, key = { it.id }) { folder ->
            val choice = PhoneFolders.choice(folder)
            ListItem(
                headlineContent = { Text(Peer.folderName(folder)) },
                supportingContent = {
                    val notes = listOfNotNull(
                        folderStateText(folder),
                        "${folder.conflicts} files need a decision".takeIf { folder.conflicts > 0UL },
                    ).joinToString(" · ")
                    val summary = if (choice == SyncChoice.FILES_ON_DEMAND) choice.title else "${folder.path}\n${choice.title}"
                    Text(if (notes.isEmpty()) summary else "$summary\n$notes")
                },
                modifier = Modifier.clickable { onOpen(folder) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(model: HomeViewModel, folder: Folder) {
    val context = LocalContext.current
    val files by model.files.collectAsStateWithLifecycle()
    val problems by model.problems.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    var acting by remember { mutableStateOf<File?>(null) }
    var showProblems by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PathProblem?>(null) }
    val inAppStorage = PhoneFolders.inAppStorage(folder)
    if (folder.paused) {
        Text(
            "Syncing of this folder is paused. Changes here are recorded and sent when it resumes.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    } else {
        Text(folderStateText(folder), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
    }
    Row(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { model.go(Page.Activity(folder.id)) }) { Text("Activity") }
        if (problems.isNotEmpty()) {
            TextButton(onClick = { showProblems = !showProblems }) {
                Text(if (showProblems) "Hide problems" else "${problems.size} files with problems")
            }
        }
    }
    if (showProblems) {
        problems.forEach { problem ->
            ListItem(
                headlineContent = { Text(problem.path) },
                supportingContent = { Text("${problemText(problem)}\n${problem.message}", color = MaterialTheme.colorScheme.error) },
                trailingContent = {
                    // A name this phone cannot store can be renamed on all devices (ADR 0033).
                    if (problem.kind == ProblemKind.NAME_NOT_ALLOWED && problem.operation == Operation.WRITE) {
                        TextButton(onClick = { renaming = problem }) { Text("Rename") }
                    }
                },
            )
        }
    }
    renaming?.let { problem ->
        RenameDialog(
            path = problem.path,
            onRename = { to ->
                model.rename(folder.id, problem.path, to)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    if (files.isEmpty()) Text("No files yet.", Modifier.padding(24.dp))
    LazyColumn {
        items(files, key = { it.entry }) { file ->
            ListItem(
                headlineContent = { Text(file.path) },
                supportingContent = {
                    Text(
                        when {
                            file.conflicts > 0u -> "Changed on two devices: tap to choose a version"
                            file.onlyHere -> "Only on this phone"
                            file.pinned -> "Kept on this phone"
                            file.onDisk -> "On this phone"
                            else -> "On your other devices"
                        } + if (file.author == status?.device) "" else " · by ${file.authorName}",
                        color = if (file.conflicts > 0u) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                    )
                },
                modifier = Modifier.combinedClickable(
                    onClick = {
                        if (file.conflicts > 0u) {
                            model.go(Page.Conflict(folder.id, file.path))
                        } else if (inAppStorage) {
                            val uri = DocumentsContract.buildDocumentUri(TidefellDocumentsProvider.AUTHORITY, "${folder.id}/${file.path}")
                            val intent = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, TidefellDocumentsProvider.mimeOf(file.path))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(Intent.createChooser(intent, file.path)) }
                        } else {
                            acting = file
                        }
                    },
                    onLongClick = { acting = file },
                ),
            )
        }
    }
    acting?.let { file -> FileActions(model, folder, file) { acting = null } }
}

/**
 * What can be done to one file: remove it from this phone or download it to the phone, and
 * delete it everywhere.
 */
@Composable
private fun FileActions(model: HomeViewModel, folder: Folder, file: File, onDismiss: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete from all devices?") },
            text = { Text("${file.path} is deleted on every device. Each keeps it in its recycle bin until its retention runs out.") },
            confirmButton = {
                TextButton(onClick = {
                    model.deleteEverywhere(folder.id, file.path)
                    onDismiss()
                }) { Text("Delete everywhere") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.path) },
        text = {
            Column {
                if (file.onDisk) {
                    TextButton(onClick = {
                        model.removeHere(folder.id, file.path)
                        onDismiss()
                    }) { Text("Remove from this phone") }
                    if (file.onlyHere) {
                        Text(
                            "No device that keeps everything holds it yet; it stays in this phone's recycle bin.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else if (!PhoneFolders.inAppStorage(folder)) {
                    TextButton(onClick = {
                        model.download(folder.id, file.path)
                        onDismiss()
                    }) { Text("Download to this phone") }
                    if (PhoneFolders.choice(folder) != SyncChoice.TWO_WAY) {
                        Text(
                            "This folder only backs up, so later changes from your other devices will not replace this copy.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (PhoneFolders.inAppStorage(folder)) {
                    // A pinned file is fetched and never removed to save space (ADR 0029).
                    TextButton(onClick = {
                        if (file.pinned) model.unpin(folder.id, file.path) else model.pin(folder.id, file.path)
                        onDismiss()
                    }) { Text(if (file.pinned) "Stop keeping on this phone" else "Keep on this phone") }
                }
                TextButton(onClick = {
                    model.go(Page.History(folder.id, file.path))
                    onDismiss()
                }) { Text("Earlier versions") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete from all devices") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** What this phone applied to a folder, newest first, and who made each change (ADR 0030). */
@Composable
private fun ActivityPage(model: HomeViewModel) {
    val activity by model.activity.collectAsStateWithLifecycle()
    if (activity.isEmpty()) {
        Text("Nothing has changed yet.", Modifier.padding(24.dp))
        return
    }
    LazyColumn {
        items(activity) { change ->
            ListItem(
                headlineContent = { Text(activityText(change)) },
                supportingContent = {
                    Text(DateUtils.getRelativeTimeSpanString(change.timeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
                },
            )
        }
    }
}

/** Moves the files a full replica holds to this phone's recycle bin, after asking. */
@Composable
private fun CleanUpDialog(model: HomeViewModel, folder: Folder, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val candidates = remember { model.cleanUpCandidates() }
    val size = Formatter.formatShortFileSize(context, candidates.sumOf { it.size.toLong() })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clean up this folder?") },
        text = {
            Text(
                if (candidates.isEmpty()) {
                    "Nothing to clean up yet: no file made on this phone is held by a device that keeps everything."
                } else {
                    "${candidates.size} files, $size, are held by a device that keeps everything. They move to this " +
                        "phone's recycle bin and stay on your other devices."
                },
            )
        },
        confirmButton = {
            if (candidates.isNotEmpty()) {
                TextButton(onClick = {
                    model.cleanUp(folder.id, candidates.map { it.path })
                    onDismiss()
                }) { Text("Clean up") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (candidates.isEmpty()) "Close" else "Cancel") } },
    )
}

@Composable
private fun RecycleBin(model: HomeViewModel, folder: Folder) {
    val context = LocalContext.current
    val items by model.recycleBin.collectAsStateWithLifecycle()
    var acting by remember { mutableStateOf<Recycled?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    if (items.isEmpty()) {
        Text("The recycle bin is empty.", Modifier.padding(24.dp))
        return
    }
    TextButton(onClick = { confirmEmpty = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Empty recycle bin") }
    LazyColumn {
        items(items, key = { it.entry }) { item ->
            val size = Formatter.formatShortFileSize(context, item.size.toLong())
            val removed = if (item.hereOnly) "Removed from this phone" else "Deleted"
            val `when` = DateUtils.getRelativeTimeSpanString(item.deletedAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            ListItem(
                headlineContent = { Text(item.path) },
                supportingContent = { Text("$removed $`when` · $size") },
                modifier = Modifier.clickable { acting = item },
            )
        }
    }
    acting?.let { item ->
        AlertDialog(
            onDismissRequest = { acting = null },
            title = { Text(item.path) },
            text = {
                Text(
                    if (item.hereOnly) {
                        "Restoring puts it back in the folder on this phone. Deleting it for good frees its space here; your other devices keep it."
                    } else {
                        "Restoring brings it back on all your devices. Deleting it for good removes it from this phone's recycle bin only."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    model.restore(folder.id, item)
                    acting = null
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = {
                    model.discard(folder.id, item)
                    acting = null
                }) { Text("Delete for good") }
            },
        )
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty the recycle bin?") },
            text = { Text("The ${items.size} files are deleted from this phone for good. Your other devices keep their own copies.") },
            confirmButton = {
                TextButton(onClick = {
                    model.emptyRecycleBin(folder.id)
                    confirmEmpty = false
                }) { Text("Empty") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
}

/** Asks for a new name for [path], which this phone cannot store, to use on all devices (ADR 0033). */
@Composable
private fun RenameDialog(path: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var to by remember { mutableStateOf(path) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename on all devices") },
        text = {
            Column {
                Text("This phone cannot store this name. Choose one every device can keep.")
                OutlinedTextField(value = to, onValueChange = { to = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(to.trim()) }, enabled = to.isNotBlank() && to.trim() != path) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
