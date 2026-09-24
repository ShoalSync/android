package org.tidefell.android.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.tidefell.android.Peer
import org.tidefell.android.backup.Backups
import org.tidefell.android.backup.CameraRollBackup
import org.tidefell.ffi.Folder
import org.tidefell.ffi.Node

/** Camera-roll backup, and each folder's recycle bin on this phone. */
@Composable
fun SettingsScreen(node: Node, revision: Int, modifier: Modifier = Modifier) {
    var changes by remember { mutableIntStateOf(0) }
    val folders = remember(node, revision, changes) { runCatching { node.folders() }.getOrDefault(emptyList()) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackupSettings(folders)
        folders.forEach { folder ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            FolderSettings(node, folder, onChanged = { changes++ })
        }
    }
}

@Composable
private fun BackupSettings(folders: List<Folder>) {
    val context = LocalContext.current
    val settings = Peer.settings
    var folder by remember { mutableStateOf(settings.backupFolder) }
    var videos by remember { mutableStateOf(settings.backupVideos) }
    var unmetered by remember { mutableStateOf(settings.backupUnmeteredOnly) }
    var charging by remember { mutableStateOf(settings.backupChargingOnly) }
    var albums by remember { mutableStateOf(settings.backupAlbums) }
    fun apply() {
        settings.backupFolder = folder
        settings.backupVideos = videos
        settings.backupAlbums = albums
        settings.backupUnmeteredOnly = unmetered
        settings.backupChargingOnly = charging
        Backups.schedule(context)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Backups.hasPermission(context)) {
            folder = folders.firstOrNull()?.id.orEmpty()
            apply()
        }
    }

    Text("Camera backup", style = MaterialTheme.typography.titleMedium)
    Text(
        "Copies new photos and videos into a folder, under Camera/<year>/<month>. Nothing on the " +
            "phone is changed or deleted, and deleting a copy from the folder leaves the phone's photo alone.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Back up the camera roll", Modifier.weight(1f))
        Switch(
            checked = folder.isNotEmpty(),
            enabled = folders.isNotEmpty(),
            onCheckedChange = { on ->
                if (!on) {
                    folder = ""
                    apply()
                } else if (Backups.hasPermission(context)) {
                    folder = folders.first().id
                    apply()
                } else {
                    askPermission.launch(Backups.permissions())
                }
            },
        )
    }
    if (folders.isEmpty()) Text("Join a folder first.", style = MaterialTheme.typography.bodySmall)
    if (folder.isEmpty()) return

    Text("Into", style = MaterialTheme.typography.labelLarge)
    folders.forEach { candidate ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = candidate.id == folder, onClick = {
                folder = candidate.id
                apply()
            }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = candidate.id == folder, onClick = null)
            Text(Peer.folderName(candidate), Modifier.padding(start = 8.dp))
        }
    }
    CheckRow("Include videos", videos) { videos = it; apply() }
    Albums(videos, albums) { albums = it; apply() }
    CheckRow("Only on Wi-Fi or other unmetered networks", unmetered) { unmetered = it; apply() }
    CheckRow("Only while charging", charging) { charging = it; apply() }
    OutlinedButton(onClick = { Backups.now(context) }) { Text("Back up now") }
}

/** All albums, or only the chosen ones. */
@Composable
private fun Albums(includeVideos: Boolean, chosen: Set<String>, onChange: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val albums = remember(includeVideos) {
        runCatching { CameraRollBackup.albums(context, includeVideos) }.getOrDefault(emptyList())
    }
    CheckRow("All albums", chosen.isEmpty()) { all ->
        onChange(if (all) emptySet() else albums.map { it.id }.toSet())
    }
    if (chosen.isEmpty()) return
    albums.forEach { album ->
        Row(Modifier.padding(start = 24.dp)) {
            CheckRow(album.name, album.id in chosen) { on ->
                // Unchecking the last album means all of them again, never none.
                onChange(if (on) chosen + album.id else chosen - album.id)
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun FolderSettings(node: Node, folder: Folder, onChanged: () -> Unit) {
    val context = LocalContext.current
    var days by remember(folder.id, folder.retentionDays) { mutableStateOf(folder.retentionDays.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    Text(Peer.folderName(folder), style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = days,
            onValueChange = { days = it.filter(Char::isDigit).take(5) },
            label = { Text("Keep deleted files (days)") },
            supportingText = { Text("On this phone only. 0 keeps them forever.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = days.toUIntOrNull()?.let { it != folder.retentionDays } == true,
            onClick = {
                error = runCatching { node.setRetention(folder.id, days.toUInt()) }.exceptionOrNull()?.message
                onChanged()
            },
        ) { Text("Save") }
    }

    val summary = if (folder.recycledFiles == 0UL) {
        "The recycle bin is empty."
    } else {
        val size = Formatter.formatShortFileSize(context, folder.recycledBytes.toLong())
        val next = folder.nextPurgeMs?.let {
            " The next is removed " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) + "."
        }.orEmpty()
        "${folder.recycledFiles} deleted files in the recycle bin, $size.$next"
    }
    Text(summary, style = MaterialTheme.typography.bodyMedium)
    if (folder.recycledFiles > 0UL) {
        OutlinedButton(onClick = { confirmEmpty = true }) { Text("Empty recycle bin") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty the recycle bin?") },
            text = {
                Text(
                    "The ${folder.recycledFiles} deleted files are removed from this phone for good. " +
                        "Other devices keep their own copies until their recycle bins are emptied.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    error = runCatching { node.emptyRecycleBin(folder.id) }.exceptionOrNull()?.message
                    onChanged()
                }) { Text("Empty") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
}
