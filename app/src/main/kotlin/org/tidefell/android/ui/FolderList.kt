package org.tidefell.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.tidefell.android.Peer
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.SyncChoice
import org.tidefell.design.TidefellSize
import org.tidefell.design.TidefellSpace
import org.tidefell.ffi.Folder

/** Room after the last folder for the add folder button: its 56dp, and its margins. */
private val AddButtonRoom = 96.dp

/** Pausing syncing on the whole phone (ADR 0029). */
@Composable
private fun DevicePause(model: HomeModel) {
    val status by model.status.collectAsStateWithLifecycle()
    val paused = status?.paused ?: return
    if (paused) {
        Banner(
            TidefellIcons.Pause,
            "Syncing is paused on this phone. Changes here are recorded and sent when it resumes.",
            modifier = Modifier.padding(horizontal = TidefellSpace.Lg, vertical = TidefellSpace.Sm),
            action = {
                FilledTonalButton(onClick = { model.resume(null) }) {
                    Icon(TidefellIcons.Play, contentDescription = null, modifier = Modifier.size(TidefellSize.IconMd))
                    Text("Resume syncing", Modifier.padding(start = TidefellSpace.Sm))
                }
            },
        )
    } else {
        TextButton(onClick = { model.pause(null) }, modifier = Modifier.padding(horizontal = TidefellSpace.Sm)) {
            Icon(TidefellIcons.Pause, contentDescription = null, modifier = Modifier.size(TidefellSize.IconMd))
            Text("Pause syncing on this phone", Modifier.padding(start = TidefellSpace.Sm))
        }
    }
}

/** The home page: every folder, with notices about the app and the phone above them. */
@Composable
fun FolderList(model: HomeModel, folders: List<Folder>, onOpen: (Folder) -> Unit) {
    // Room at the end, so the add folder button does not cover the last folder.
    LazyColumn(contentPadding = PaddingValues(bottom = AddButtonRoom)) {
        item { CrashNotice(model) }
        item { DevicePause(model) }
        if (folders.isEmpty()) {
            item { EmptyState(TidefellIcons.Folder, "No folders yet. Add one, such as the camera folder, to back it up.") }
        }
        items(folders, key = { it.id }) { folder -> FolderCard(folder) { onOpen(folder) } }
    }
}

/** A folder: its name, where it is, how it syncs and what it is doing. */
@Composable
private fun FolderCard(folder: Folder, onClick: () -> Unit) {
    val choice = PhoneFolders.choice(folder)
    val look = folderLook(folder)
    Card(
        onClick = onClick,
        // Half the space between cards above and below each, so they are TidefellSpace.Md apart.
        modifier = Modifier.fillMaxWidth().padding(horizontal = TidefellSpace.Lg, vertical = TidefellSpace.Md / 2),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(TidefellSpace.Lg), horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Lg)) {
            IconTile(
                if (choice == SyncChoice.FILES_ON_DEMAND) TidefellIcons.Cloud else TidefellIcons.Folder,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                size = TidefellSize.TileLg,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TidefellSpace.Sm)) {
                Text(Peer.folderName(folder), style = MaterialTheme.typography.titleMedium)
                if (choice != SyncChoice.FILES_ON_DEMAND) {
                    Text(
                        folder.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                Pill(choice.title)
                StatusLine(look.icon, folderStateText(folder), look.color)
                if (folder.conflicts > 0UL) {
                    StatusLine(
                        TidefellIcons.Warning,
                        "${files(folder.conflicts.toLong())} ${if (folder.conflicts == 1UL) "needs" else "need"} a decision",
                        MaterialTheme.statusColors.attention,
                    )
                }
            }
            Icon(
                TidefellIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}
