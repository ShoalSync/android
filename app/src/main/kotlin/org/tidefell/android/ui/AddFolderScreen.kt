package org.tidefell.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.tidefell.android.folders.PhoneStorage
import org.tidefell.android.folders.SyncChoice
import org.tidefell.design.TidefellSize
import org.tidefell.design.TidefellSpace
import org.tidefell.ffi.Folder
import org.tidefell.ffi.FolderOffer
import java.io.File

/**
 * Adds a new folder at a folder on the phone, or joins [offer] from another device. [onDone] is
 * called with the added folder; [onCancel] when the user backs out of the first step.
 */
@Composable
fun AddFolderScreen(
    offer: FolderOffer?,
    onDone: (Folder) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    model: AddFolderModel = viewModel<AddFolderViewModel>(key = "add-${offer?.id ?: "new"}"),
) {
    val finished: (Folder) -> Unit = {
        model.reset()
        onDone(it)
    }
    val cancelled = {
        model.reset()
        onCancel()
    }
    val context = LocalContext.current
    val step by model.step.collectAsStateWithLifecycle()
    LaunchedEffect(offer?.id) { model.start(offer) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.accessChanged() }
    BackHandler {
        when (val s = step) {
            is AddFolderViewModel.Step.Choose -> model.open(s.dir.parentFile)
            is AddFolderViewModel.Step.Pick -> if (!model.up()) cancelled()
            else -> cancelled()
        }
    }

    Column(modifier.fillMaxSize().padding(TidefellSpace.Lg), verticalArrangement = Arrangement.spacedBy(TidefellSpace.Md)) {
        // A new folder is added from the home screen, whose top bar says so; joining happens
        // while pairing, where there is none.
        offer?.let { Text("Join ${it.name}", style = MaterialTheme.typography.titleLarge) }
        when (val s = step) {
            AddFolderViewModel.Step.Place -> PlaceStep(model)
            AddFolderViewModel.Step.Access -> {
                Text(
                    "To sync a folder on this phone, Tidefell needs access to all files. It reads and " +
                        "writes only the folders you add.",
                )
                Button(onClick = { context.startActivity(PhoneStorage.accessIntent(context)) }) {
                    Text("Allow access to all files")
                }
                OutlinedButton(onClick = cancelled) { Text("Cancel") }
            }
            is AddFolderViewModel.Step.Pick -> PickStep(s, model)
            is AddFolderViewModel.Step.Choose -> ChooseStep(s.dir, model)
            AddFolderViewModel.Step.Working -> CircularProgressIndicator()
            is AddFolderViewModel.Step.Done -> DoneStep(s.folder, joined = offer != null, onDone = finished)
            is AddFolderViewModel.Step.Failed -> {
                Banner(
                    TidefellIcons.Error,
                    s.message,
                    error = true,
                )
                Button(onClick = { model.start(offer, again = true) }) { Text("Try again") }
                OutlinedButton(onClick = cancelled) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun PlaceStep(model: AddFolderModel) {
    Text("Where should its files live on this phone?", style = MaterialTheme.typography.titleMedium)
    PlaceCard(
        TidefellIcons.Folder,
        "In a folder on this phone",
        "Such as the camera folder, Documents or Download. Choose how it syncs next.",
        model::usePhoneFolder,
    )
    PlaceCard(
        TidefellIcons.Cloud,
        SyncChoice.FILES_ON_DEMAND.title,
        "Inside Tidefell, opened from the Files app. ${SyncChoice.FILES_ON_DEMAND.summary}",
        model::useAppStorage,
    )
}

/** One place a folder's files can live, to choose. */
@Composable
private fun PlaceCard(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.padding(TidefellSpace.Lg),
            horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon,
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
                size = TidefellSize.TileLg,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TidefellSpace.Xs)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(TidefellIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PickStep(step: AddFolderViewModel.Step.Pick, model: AddFolderModel) {
    val dir = step.dir
    if (dir == null) {
        Text("Choose a folder", style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(step.places, key = { it.dir.path }) { place ->
                ListItem(
                    leadingContent = { IconTile(TidefellIcons.Folder) },
                    headlineContent = { Text(place.label) },
                    supportingContent = { Text(place.dir.path) },
                    modifier = Modifier.clickable { model.open(place.dir) },
                )
            }
        }
        return
    }
    Text(dir.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Sm)) {
        Button(onClick = { model.choose(dir) }) { Text("Use this folder") }
        OutlinedButton(onClick = { model.up() }) { Text("Up") }
    }
    HorizontalDivider()
    if (step.subfolders.isEmpty()) {
        Text("No folders inside.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LazyColumn {
        items(step.subfolders, key = { it.path }) { child ->
            ListItem(
                leadingContent = { Icon(TidefellIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(child.name) },
                modifier = Modifier.clickable { model.open(child) },
            )
        }
    }
}

@Composable
private fun ChooseStep(dir: File, model: AddFolderModel) {
    var choice by rememberSaveable { mutableStateOf(SyncChoice.BACKUP_ONLY) }
    var confirmRemoving by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(TidefellSpace.Sm)) {
        Text(dir.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("How should it sync?", style = MaterialTheme.typography.titleMedium)
        SyncChoices(choice) { choice = it }
        Button(onClick = {
            if (choice == SyncChoice.REMOVE_AFTER_BACKUP) confirmRemoving = true else model.add(dir, choice)
        }) { Text("Add folder") }
    }
    if (confirmRemoving) {
        ConfirmRemoveAfterBackup(
            onConfirm = {
                confirmRemoving = false
                model.add(dir, choice)
            },
            onDismiss = { confirmRemoving = false },
        )
    }
}

/** The sync choices for a phone folder, in order, as radio buttons. */
@Composable
fun SyncChoices(selected: SyncChoice, onSelect: (SyncChoice) -> Unit) {
    SyncChoice.forPhoneFolders.forEach { choice ->
        val chosen = choice == selected
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = TidefellSpace.Xs),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().selectable(selected = chosen, onClick = { onSelect(choice) }).padding(TidefellSpace.Md),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = chosen, onClick = null)
                Column(Modifier.padding(start = TidefellSpace.Md), verticalArrangement = Arrangement.spacedBy(TidefellSpace.Xxs)) {
                    Text(choice.title, style = MaterialTheme.typography.titleSmall)
                    Text(choice.summary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Asks once before remove after backup is turned on (ADR 0025). */
@Composable
fun ConfirmRemoveAfterBackup(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove files from the phone after backup?") },
        text = {
            Text(
                "Files added to this folder from now on are moved to this phone's recycle bin once a " +
                    "device that keeps everything, such as a NAS, holds them. They are deleted from the " +
                    "phone for good when the recycle bin's retention runs out. Your other devices keep them. " +
                    "Files already in the folder are not affected.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DoneStep(folder: Folder, joined: Boolean, onDone: (Folder) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Md)) {
        Icon(
            TidefellIcons.Ok,
            contentDescription = null,
            tint = MaterialTheme.statusColors.ok,
            modifier = Modifier.size(TidefellSize.IconLg),
        )
        Text("Added ${File(folder.path).name}.", style = MaterialTheme.typography.titleMedium)
    }
    if (!joined) {
        Text("To sync it with another device, join it there with this folder ID:")
        SelectionContainer { Text(folder.id, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Text("tidefell folder join ${folder.id} <path>", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Folder ID", folder.id))
        }) { Text("Copy folder ID") }
    }
    Button(onClick = { onDone(folder) }) { Text("Done") }
}
