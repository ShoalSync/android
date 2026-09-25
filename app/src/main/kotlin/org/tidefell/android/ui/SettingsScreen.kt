package org.tidefell.android.ui

import android.app.Activity
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import org.tidefell.android.backup.RemoveOriginals
import org.tidefell.ffi.Folder
import org.tidefell.ffi.Node

/** Camera-roll backup, and each folder's recycle bin on this phone. */
@Composable
fun SettingsScreen(node: Node, revision: Int, modifier: Modifier = Modifier) {
    var changes by remember { mutableIntStateOf(0) }
    val folders = remember(node, revision, changes) { runCatching { node.folders() }.getOrDefault(emptyList()) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackupSettings(node, folders, revision)
        folders.forEach { folder ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            FolderSettings(node, folder, onChanged = { changes++ })
        }
    }
}

@Composable
private fun BackupSettings(node: Node, folders: List<Folder>, revision: Int) {
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
            "phone is changed or deleted unless you turn on removing photos below, and deleting a copy " +
            "from the folder leaves the phone's photo alone.",
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
    RemoveOriginalsSettings(node, folder, revision)
}

/** "Remove from phone after backup" (ADR 0020, 0022): off by default, asks before turning on. */
@Composable
private fun RemoveOriginalsSettings(node: Node, folder: String, revision: Int) {
    val context = LocalContext.current
    val settings = Peer.settings
    var on by remember { mutableStateOf(settings.removeOriginals) }
    var confirmOn by remember { mutableStateOf(false) }
    var removed by remember { mutableIntStateOf(0) }
    var removing by remember { mutableStateOf<List<RemoveOriginals.Eligible>>(emptyList()) }
    val eligible = remember(node, folder, on, revision, removed) {
        if (on) runCatching { RemoveOriginals.eligible(context, node, folder) }.getOrDefault(emptyList()) else emptyList()
    }
    val deleteRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) RemoveOriginals.removed(context, removing)
        removing = emptyList()
        removed++
    }

    Text("Remove from phone after backup", style = MaterialTheme.typography.labelLarge)
    if (!RemoveOriginals.supported) {
        Text("Needs Android 11 or later.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Offer to delete photos and videos from the phone once an always-on device that keeps " +
                "everything, such as a NAS or server, holds their copies.",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        Switch(
            checked = on,
            onCheckedChange = { turnOn ->
                if (turnOn) {
                    confirmOn = true
                } else {
                    on = false
                    settings.removeOriginals = false
                    RemoveOriginals.turnedOff(context)
                }
            },
        )
    }
    if (on) {
        if (eligible.isEmpty()) {
            Text(
                "Nothing to remove yet. Photos backed up from now on can be removed once an always-on device holds them.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text("${eligible.size} photos and videos are safe on an always-on device.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = {
                removing = eligible
                deleteRequest.launch(IntentSenderRequest.Builder(RemoveOriginals.deleteRequest(context, eligible)).build())
            }) { Text("Remove from phone") }
        }
    }

    if (confirmOn) {
        AlertDialog(
            onDismissRequest = { confirmOn = false },
            title = { Text("Remove photos from the phone after backup?") },
            text = {
                Text(
                    "Photos and videos backed up from now on can be deleted from this phone once an " +
                        "always-on device that keeps everything holds them. Android asks you to confirm " +
                        "each time. Photos already backed up are not affected. If that device later loses " +
                        "its disk or leaves the folder, the deleted photos are gone from it too.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOn = false
                    on = true
                    settings.removeOriginals = true
                }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { confirmOn = false }) { Text("Cancel") } },
        )
    }
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
    if (folder.onlyHere > 0UL) {
        // ADR 0022: these are never evicted, so the folder can grow past its budget.
        Text(
            "${folder.onlyHere} files made on this phone are not on an always-on device yet. They stay on " +
                "the phone, even past its space limit, until a device that keeps everything, such as a " +
                "NAS or server, holds them.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
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
