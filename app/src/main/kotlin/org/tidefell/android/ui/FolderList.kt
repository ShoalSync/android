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
import org.tidefell.ffi.Folder

/** Pausing syncing on the whole phone (ADR 0029). */
@Composable
private fun DevicePause(model: HomeModel) {
    val status by model.status.collectAsStateWithLifecycle()
    val paused = status?.paused ?: return
    if (paused) {
        Banner(
            TidefellIcons.Pause,
            "Syncing is paused on this phone. Changes here are recorded and sent when it resumes.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            action = {
                FilledTonalButton(onClick = { model.resume(null) }) {
                    Icon(TidefellIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Resume syncing", Modifier.padding(start = 8.dp))
                }
            },
        )
    } else {
        TextButton(onClick = { model.pause(null) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(TidefellIcons.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Pause syncing on this phone", Modifier.padding(start = 8.dp))
        }
    }
}

/** The home page: every folder, with notices about the app and the phone above them. */
@Composable
fun FolderList(model: HomeModel, folders: List<Folder>, onOpen: (Folder) -> Unit) {
    // Room at the end, so the add folder button does not cover the last folder.
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconTile(
                if (choice == SyncChoice.FILES_ON_DEMAND) TidefellIcons.Cloud else TidefellIcons.Folder,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                size = 44.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
