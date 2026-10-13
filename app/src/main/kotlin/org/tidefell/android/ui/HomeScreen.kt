package org.tidefell.android.ui

import android.content.Intent
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.tidefell.android.Peer
import org.tidefell.android.TidefellDocumentsProvider
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.SyncChoice
import org.tidefell.design.TidefellIcons
import org.tidefell.design.TidefellSize
import org.tidefell.design.TidefellSpace
import org.tidefell.ffi.ChangeKind
import org.tidefell.ffi.File
import org.tidefell.ffi.Folder
import org.tidefell.ffi.Operation
import org.tidefell.ffi.PathProblem
import org.tidefell.ffi.ProblemKind
import org.tidefell.ffi.Recycled

/**
 * Folders, their files and recycle bins, settings, and adding folders. The settings, status and
 * add folder pages are slots, so screenshot tests can give them their own models.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    model: HomeModel = viewModel<HomeViewModel>(),
    settings: @Composable () -> Unit = { SettingsScreen() },
    status: @Composable () -> Unit = { StatusPage() },
    addFolder: @Composable (onDone: (Folder) -> Unit, onCancel: () -> Unit) -> Unit = { onDone, onCancel ->
        AddFolderScreen(offer = null, onDone = onDone, onCancel = onCancel)
    },
) {
    val started by model.started.collectAsStateWithLifecycle()
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
                title = {
                    if (page == Page.Folders) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Md),
                        ) {
                            BrandMark()
                            Text(title)
                        }
                    } else {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    // Adding a folder backs out step by step, with its own back handling.
                    if (page.parent != null && page != Page.AddFolder) {
                        IconAction(TidefellIcons.Back, "Back") { model.back() }
                    }
                },
                actions = {
                    when (page) {
                        Page.Folders -> {
                            IconAction(TidefellIcons.Devices, "Status") { model.go(Page.Status) }
                            IconAction(TidefellIcons.Settings, "Settings") { model.go(Page.Settings) }
                        }
                        is Page.Files -> if (folder != null) {
                            IconAction(
                                if (folder.paused) TidefellIcons.Play else TidefellIcons.Pause,
                                if (folder.paused) "Resume" else "Pause",
                            ) { if (folder.paused) model.resume(folder.id) else model.pause(folder.id) }
                        }
                        else -> {}
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
        bottomBar = {
            // The home page's main action, across the bottom where a thumb reaches it.
            if (page == Page.Folders && started) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(vertical = TidefellSpace.Sm),
                    contentAlignment = Alignment.Center,
                ) {
                    ActionButton(
                        "Add folder",
                        onClick = { model.go(Page.AddFolder) },
                        kind = ButtonKind.Primary,
                        icon = TidefellIcons.Add,
                        // As wide as the cards above it: the page's width less its margins.
                        modifier = Modifier
                            .widthIn(max = TidefellSize.ContentMax)
                            .fillMaxWidth()
                            .padding(horizontal = TidefellSpace.Lg),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).pageWidth()) {
            when {
                !started -> CircularProgressIndicator(Modifier.padding(TidefellSpace.Xxxl).align(Alignment.CenterHorizontally))
                page == Page.Settings -> settings()
                page == Page.Status -> status()
                page is Page.Activity && folder != null -> ActivityPage(model)
                page == Page.AddFolder -> addFolder({ model.go(Page.Files(it.id)) }, { model.back() })
                page is Page.Files && folder != null -> FileList(model, folder) { cleaningUp = true }
                page is Page.RecycleBin && folder != null -> RecycleBin(model, folder)
                page is Page.History && folder != null -> HistoryPage(model, folder, (page as Page.History).path)
                page is Page.Conflict && folder != null -> ConflictPage(model, folder, (page as Page.Conflict).path) { model.back() }
                else -> FolderList(model, folders) { model.go(Page.Files(it.id)) }
            }
        }
    }
    if (cleaningUp && folder != null) CleanUpDialog(model, folder) { cleaningUp = false }
}

/** The look of a file: on the phone, only on other devices, or waiting for a decision. */
@Composable
private fun FileTile(file: File) {
    val colors = MaterialTheme.colorScheme
    when {
        file.conflicts > 0u -> IconTile(TidefellIcons.Warning, colors.tertiaryContainer, colors.onTertiaryContainer)
        !file.onDisk -> IconTile(TidefellIcons.Cloud, colors.surfaceContainerHigh, colors.onSurfaceVariant)
        else -> IconTile(TidefellIcons.File)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(model: HomeModel, folder: Folder, onCleanUp: () -> Unit) {
    val context = LocalContext.current
    val files by model.files.collectAsStateWithLifecycle()
    val problems by model.problems.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    var acting by remember { mutableStateOf<File?>(null) }
    var showProblems by rememberSaveable { mutableStateOf(false) }
    val inAppStorage = PhoneFolders.inAppStorage(folder)
    FolderHeader(model, folder, onCleanUp) {
        if (problems.isNotEmpty()) {
            Chip(
                "${files(problems.size)} with problems",
                onClick = { showProblems = !showProblems },
                icon = TidefellIcons.Error,
                selected = showProblems,
            )
        }
    }
    if (showProblems) ProblemList(model, folder, problems)
    if (files.isEmpty()) EmptyState(TidefellIcons.File, "No files yet.")
    LazyColumn {
        items(files, key = { it.entry }) { file ->
            val tone = if (file.conflicts > 0u) MaterialTheme.statusColors.attention else MaterialTheme.colorScheme.onSurfaceVariant
            ListItem(
                leadingContent = { FileTile(file) },
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
                        color = tone,
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

/** What a folder is doing, and the ways into its other pages. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderHeader(model: HomeModel, folder: Folder, onCleanUp: () -> Unit, problems: @Composable () -> Unit) {
    if (folder.paused) {
        Banner(
            TidefellIcons.Pause,
            "Syncing of this folder is paused. Changes here are recorded and sent when it resumes.",
            modifier = Modifier.padding(horizontal = TidefellSpace.Lg, vertical = TidefellSpace.Xs),
        )
    } else {
        val look = folderLook(folder)
        StatusLine(
            look.icon,
            folderStateText(folder),
            look.color,
            Modifier.padding(horizontal = TidefellSpace.Lg, vertical = TidefellSpace.Xs),
        )
    }
    FolderNotices(model, folder)
    FlowRow(Modifier.padding(horizontal = TidefellSpace.Lg), horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Sm)) {
        Chip("Activity", onClick = { model.go(Page.Activity(folder.id)) }, icon = TidefellIcons.History)
        Chip("Recycle bin", onClick = { model.go(Page.RecycleBin(folder.id)) }, icon = TidefellIcons.Delete)
        if (PhoneFolders.choice(folder).let { it == SyncChoice.BACKUP_ONLY || it == SyncChoice.TWO_WAY }) {
            Chip("Clean up", onClick = onCleanUp, icon = TidefellIcons.Phone)
        }
        problems()
    }
}

/** The files that failed to sync, and why; a name this phone cannot store can be renamed. */
@Composable
private fun ProblemList(model: HomeModel, folder: Folder, problems: List<PathProblem>) {
    var renaming by remember { mutableStateOf<PathProblem?>(null) }
    // A write or delete that failed in a lasting way, such as permission denied, is not tried
    // again until the file changes or this asks for it (ADR 0037).
    if (problems.any { it.operation == Operation.WRITE || it.operation == Operation.DELETE }) {
        ActionButton(
            "Try again",
            onClick = { model.retryProblems(folder.id) },
            modifier = Modifier.padding(horizontal = TidefellSpace.Lg, vertical = TidefellSpace.Xs),
            icon = TidefellIcons.Sync,
        )
    }
    problems.forEach { problem ->
        ListItem(
            leadingContent = {
                IconTile(TidefellIcons.Error, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            },
            headlineContent = { Text(problem.path) },
            supportingContent = { Text("${problemText(problem)}\n${problem.message}", color = MaterialTheme.colorScheme.error) },
            trailingContent = {
                // A name this phone cannot store can be renamed on all devices (ADR 0033).
                if (problem.kind == ProblemKind.NAME_NOT_ALLOWED && problem.operation == Operation.WRITE) {
                    ActionButton("Rename", onClick = { renaming = problem }, kind = ButtonKind.Quiet)
                }
            },
        )
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
}

/**
 * What can be done to one file: remove it from this phone or download it to the phone, and
 * delete it everywhere.
 */
@Composable
private fun FileActions(model: HomeModel, folder: Folder, file: File, onDismiss: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(TidefellIcons.Delete, contentDescription = null) },
            title = { Text("Delete from all devices?") },
            text = {
                Text("${file.path} is deleted on every device. Each keeps it in its recycle bin until its retention runs out.")
            },
            confirmButton = {
                ActionButton(
                    "Delete everywhere",
                    onClick = {
                        model.deleteEverywhere(folder.id, file.path)
                        onDismiss()
                    },
                    kind = ButtonKind.Danger,
                )
            },
            dismissButton = { ActionButton("Cancel", onClick = onDismiss, kind = ButtonKind.Quiet) },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.path) },
        text = {
            Column {
                if (file.onDisk) {
                    ActionRow(TidefellIcons.Cloud, "Remove from this phone", onClick = {
                        model.removeHere(folder.id, file.path)
                        onDismiss()
                    })
                    if (file.onlyHere) {
                        Text(
                            "No device that keeps everything holds it yet; it stays in this phone's recycle bin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = ActionRowTextStart, bottom = TidefellSpace.Sm),
                        )
                    }
                } else if (!PhoneFolders.inAppStorage(folder)) {
                    ActionRow(TidefellIcons.Phone, "Download to this phone", onClick = {
                        model.download(folder.id, file.path)
                        onDismiss()
                    })
                    if (PhoneFolders.choice(folder) != SyncChoice.TWO_WAY) {
                        Text(
                            "This folder only backs up, so later changes from your other devices will not replace this copy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = ActionRowTextStart, bottom = TidefellSpace.Sm),
                        )
                    }
                }
                if (PhoneFolders.inAppStorage(folder)) {
                    // A pinned file is fetched and never removed to save space (ADR 0029).
                    ActionRow(TidefellIcons.Phone, if (file.pinned) "Stop keeping on this phone" else "Keep on this phone", onClick = {
                        if (file.pinned) model.unpin(folder.id, file.path) else model.pin(folder.id, file.path)
                        onDismiss()
                    })
                }
                ActionRow(TidefellIcons.History, "Earlier versions", onClick = {
                    model.go(Page.History(folder.id, file.path))
                    onDismiss()
                })
                ActionRow(
                    TidefellIcons.Delete,
                    "Delete from all devices",
                    onClick = { confirmDelete = true },
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = { ActionButton("Close", onClick = onDismiss, kind = ButtonKind.Quiet) },
    )
}

/** What this phone applied to a folder, newest first, and who made each change (ADR 0030). */
@Composable
private fun ActivityPage(model: HomeModel) {
    val activity by model.activity.collectAsStateWithLifecycle()
    if (activity.isEmpty()) {
        EmptyState(TidefellIcons.History, "Nothing has changed yet.")
        return
    }
    LazyColumn {
        items(activity) { change ->
            ListItem(
                leadingContent = {
                    when (change.kind) {
                        ChangeKind.ADDED -> IconTile(TidefellIcons.Add)
                        ChangeKind.DELETED -> IconTile(TidefellIcons.Delete)
                        ChangeKind.RESTORED -> IconTile(TidefellIcons.History)
                        ChangeKind.CONFLICT ->
                            IconTile(
                                TidefellIcons.Warning,
                                MaterialTheme.colorScheme.tertiaryContainer,
                                MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        ChangeKind.CHANGED, ChangeKind.RENAMED -> IconTile(TidefellIcons.File)
                    }
                },
                headlineContent = { Text(activityText(change)) },
                supportingContent = {
                    Text(
                        DateUtils.getRelativeTimeSpanString(
                            change.timeMs,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS
                        ).toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

/** Moves the files a full replica holds to this phone's recycle bin, after asking. */
@Composable
private fun CleanUpDialog(model: HomeModel, folder: Folder, onDismiss: () -> Unit) {
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
                    "${files(candidates.size)}, $size, ${if (candidates.size == 1) "is" else "are"} held by a device that " +
                        "keeps everything. ${if (candidates.size == 1) "It moves" else "They move"} to this " +
                        "phone's recycle bin and ${if (candidates.size == 1) "stays" else "stay"} on your other devices."
                },
            )
        },
        confirmButton = {
            if (candidates.isNotEmpty()) {
                ActionButton(
                    "Clean up",
                    onClick = {
                        model.cleanUp(folder.id, candidates.map { it.path })
                        onDismiss()
                    },
                    kind = ButtonKind.Quiet,
                )
            }
        },
        dismissButton = {
            ActionButton(if (candidates.isEmpty()) "Close" else "Cancel", onClick = onDismiss, kind = ButtonKind.Quiet)
        },
    )
}

@Composable
private fun RecycleBin(model: HomeModel, folder: Folder) {
    val context = LocalContext.current
    val items by model.recycleBin.collectAsStateWithLifecycle()
    var acting by remember { mutableStateOf<Recycled?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    if (items.isEmpty()) {
        EmptyState(TidefellIcons.Delete, "The recycle bin is empty.")
        return
    }
    ActionButton(
        "Empty recycle bin",
        onClick = {
            confirmEmpty = true
        },
        icon = TidefellIcons.Delete,
        modifier = Modifier.padding(horizontal = TidefellSpace.Lg, vertical = TidefellSpace.Sm),
    )
    LazyColumn {
        items(items, key = { it.entry }) { item ->
            val size = Formatter.formatShortFileSize(context, item.size.toLong())
            val removed = if (item.hereOnly) "Removed from this phone" else "Deleted"
            val `when` = DateUtils.getRelativeTimeSpanString(item.deletedAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            ListItem(
                leadingContent = {
                    IconTile(
                        TidefellIcons.File,
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                headlineContent = { Text(item.path) },
                supportingContent = { Text("$removed $`when` · $size", color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
                ActionButton(
                    "Restore",
                    onClick = {
                        model.restore(folder.id, item)
                        acting = null
                    },
                    kind = ButtonKind.Quiet,
                )
            },
            dismissButton = {
                ActionButton(
                    "Delete for good",
                    onClick = {
                        model.discard(folder.id, item)
                        acting = null
                    },
                    kind = ButtonKind.Danger,
                )
            },
        )
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty the recycle bin?") },
            text = {
                Text(
                    (if (items.size == 1) "The file is" else "The ${items.size} files are") +
                        " deleted from this phone for good. Your other devices keep their own copies.",
                )
            },
            confirmButton = {
                ActionButton(
                    "Empty",
                    onClick = {
                        model.emptyRecycleBin(folder.id)
                        confirmEmpty = false
                    },
                    kind = ButtonKind.Danger,
                )
            },
            dismissButton = { ActionButton("Cancel", onClick = { confirmEmpty = false }, kind = ButtonKind.Quiet) },
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
            Column(verticalArrangement = Arrangement.spacedBy(TidefellSpace.Lg)) {
                Text("This phone cannot store this name. Choose one every device can keep.")
                Field(value = to, onValueChange = { to = it })
            }
        },
        confirmButton = {
            ActionButton(
                "Rename",
                onClick = { onRename(to.trim()) },
                kind = ButtonKind.Quiet,
                enabled = to.isNotBlank() && to.trim() != path,
            )
        },
        dismissButton = { ActionButton("Cancel", onClick = onDismiss, kind = ButtonKind.Quiet) },
    )
}
