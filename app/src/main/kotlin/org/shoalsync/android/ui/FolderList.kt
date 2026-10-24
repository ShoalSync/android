package org.shoalsync.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.shoalsync.android.Peer
import org.shoalsync.android.folders.PhoneFolders
import org.shoalsync.android.folders.SyncChoice
import org.shoalsync.design.ShoalIcons
import org.shoalsync.design.ShoalSize
import org.shoalsync.design.ShoalSpace
import org.shoalsync.ffi.Folder

/** Pausing syncing on the whole phone (ADR 0029). */
@Composable
private fun DevicePause(model: HomeModel) {
    val status by model.status.collectAsStateWithLifecycle()
    val current = status ?: return
    if (current.revoked) {
        // Nothing to pause: it syncs with no one (ADR 0040).
        Banner(
            ShoalIcons.Error,
            "This phone was revoked from another of your devices, so it syncs with no one. " +
                "To use it again, clear the app's storage and pair it again.",
            modifier = Modifier.padding(horizontal = ShoalSpace.Lg, vertical = ShoalSpace.Sm),
        )
        return
    }
    if (current.paused) {
        Banner(
            ShoalIcons.Pause,
            "Syncing is paused on this phone. Changes here are recorded and sent when it resumes.",
            modifier = Modifier.padding(horizontal = ShoalSpace.Lg, vertical = ShoalSpace.Sm),
            action = {
                ActionButton("Resume syncing", onClick = { model.resume(null) }, icon = ShoalIcons.Play)
            },
        )
    } else {
        ActionButton(
            "Pause syncing on this phone",
            onClick = { model.pause(null) },
            kind = ButtonKind.Quiet,
            icon = ShoalIcons.Pause,
            modifier = Modifier.padding(horizontal = ShoalSpace.Sm),
        )
    }
}

/** The home page: every folder, with notices about the app and the phone above them. */
@Composable
fun FolderList(model: HomeModel, folders: List<Folder>, onOpen: (Folder) -> Unit) {
    LazyColumn {
        item { CrashNotice(model) }
        item { DevicePause(model) }
        if (folders.isEmpty()) {
            item { EmptyState(ShoalIcons.Folder, "No folders yet. Add one, such as the camera folder, to back it up.") }
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
        // Half the space between cards above and below each, so they are ShoalSpace.Md apart.
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ShoalSpace.Lg, vertical = ShoalSpace.Md / 2)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(ShoalSpace.Lg), horizontalArrangement = Arrangement.spacedBy(ShoalSpace.Lg)) {
            IconTile(
                if (choice == SyncChoice.FILES_ON_DEMAND) ShoalIcons.Cloud else ShoalIcons.Folder,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
                size = ShoalSize.TileLg,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ShoalSpace.Sm)) {
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
                        ShoalIcons.Warning,
                        "${files(folder.conflicts.toLong())} ${if (folder.conflicts == 1UL) "needs" else "need"} a decision",
                        MaterialTheme.statusColors.attention,
                    )
                }
                if (folder.heldDeletes > 0UL) {
                    StatusLine(ShoalIcons.Delete, heldDeletesLine(folder.heldDeletes), MaterialTheme.statusColors.attention)
                }
            }
            Icon(
                ShoalIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}
