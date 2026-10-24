package org.shoalsync.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.shoalsync.design.ShoalIcons
import org.shoalsync.design.ShoalSpace
import org.shoalsync.ffi.Folder
import org.shoalsync.ffi.HeldWithdrawals

// What keeps a folder from syncing as usual (ADR 0037): its storage is not available, a scan
// found so many files deleted that the deletes wait for a decision, a revoked device changed so
// many files after it was lost that withdrawing them waits for one (ADR 0040), or the phone is
// low on space.

private val noticePadding = Modifier.padding(horizontal = ShoalSpace.Lg, vertical = ShoalSpace.Xs)

/** The folder's notices, above its files. */
@Composable
fun FolderNotices(model: HomeModel, folder: Folder) {
    folder.unavailable?.let { reason ->
        Banner(
            ShoalIcons.Error,
            "This folder's storage is not available, so nothing here is synced until it is back. " +
                reason.replaceFirstChar { it.uppercase() },
            modifier = noticePadding,
            error = true,
        )
    }
    if (folder.heldDeletes > 0UL) HeldDeletes(model, folder)
    val withdrawals by model.heldWithdrawals.collectAsStateWithLifecycle()
    withdrawals.forEach { Withdrawals(model, folder, it) }
    folder.lowSpace?.let { reason ->
        Banner(
            ShoalIcons.Warning,
            "Downloads to this folder are paused: $reason. Changes here are still sent.",
            modifier = noticePadding,
        )
    }
}

/** What to do with files a scan found deleted: delete them everywhere or bring them back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeldDeletes(model: HomeModel, folder: Folder) {
    val paths by model.heldDeletes.collectAsStateWithLifecycle()
    var showing by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    Banner(
        ShoalIcons.Delete,
        heldDeletesText(folder.heldDeletes),
        modifier = noticePadding,
        action = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ShoalSpace.Sm)) {
                ActionButton("Restore", onClick = { model.restoreDeleted(folder.id) }, icon = ShoalIcons.History)
                ActionButton("Delete everywhere", onClick = { confirming = true }, kind = ButtonKind.Danger)
                ActionButton("Show files", onClick = { showing = true }, kind = ButtonKind.Quiet)
            }
        },
    )
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text("Deleted here") },
            text = { HeldPaths(paths) },
            confirmButton = { ActionButton("Close", onClick = { showing = false }, kind = ButtonKind.Quiet) },
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = { Icon(ShoalIcons.Delete, contentDescription = null) },
            title = { Text("Delete from all devices?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(ShoalSpace.Md)) {
                    Text(
                        "${files(folder.heldDeletes.toLong())} ${if (folder.heldDeletes == 1UL) "is" else "are"} deleted on every " +
                            "device. Each keeps them in its recycle bin until its retention runs out.",
                    )
                    HeldPaths(paths)
                }
            },
            confirmButton = {
                ActionButton(
                    "Delete everywhere",
                    onClick = {
                        model.confirmDeletes(folder.id)
                        confirming = false
                    },
                    kind = ButtonKind.Danger,
                )
            },
            dismissButton = { ActionButton("Cancel", onClick = { confirming = false }, kind = ButtonKind.Quiet) },
        )
    }
}

/** What to do with a revoked device's changes after its loss: withdraw them or keep them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Withdrawals(model: HomeModel, folder: Folder, held: HeldWithdrawals) {
    var showing by rememberSaveable(held.device) { mutableStateOf(false) }
    var confirming by rememberSaveable(held.device) { mutableStateOf(false) }
    val count = files(held.paths.size.toLong())
    Banner(
        ShoalIcons.Warning,
        "A device you revoked changed $count here after it was lost: too many to withdraw without asking. " +
            "Keep them if they are yours.",
        modifier = noticePadding,
        action = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ShoalSpace.Sm)) {
                ActionButton("Keep", onClick = { model.decideWithdrawals(folder.id, held.device, false) })
                ActionButton("Withdraw", onClick = { confirming = true }, kind = ButtonKind.Danger)
                ActionButton("Show files", onClick = { showing = true }, kind = ButtonKind.Quiet)
            }
        },
    )
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text("Changed after the loss") },
            text = { HeldPaths(held.paths) },
            confirmButton = { ActionButton("Close", onClick = { showing = false }, kind = ButtonKind.Quiet) },
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = { Icon(ShoalIcons.Warning, contentDescription = null) },
            title = { Text("Withdraw these changes?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(ShoalSpace.Md)) {
                    Text(
                        "What each change replaced is put back where a device still has it; otherwise the file " +
                            "goes to the recycle bin, on every device.",
                    )
                    HeldPaths(held.paths)
                }
            },
            confirmButton = {
                ActionButton(
                    "Withdraw",
                    onClick = {
                        model.decideWithdrawals(folder.id, held.device, true)
                        confirming = false
                    },
                    kind = ButtonKind.Danger,
                )
            },
            dismissButton = { ActionButton("Cancel", onClick = { confirming = false }, kind = ButtonKind.Quiet) },
        )
    }
}

/** The held paths, scrolling when there are many. */
@Composable
private fun HeldPaths(paths: List<String>) {
    LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(ShoalSpace.Xs)) {
        items(paths) { path ->
            Text(path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
