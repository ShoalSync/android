package org.tidefell.android.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.tidefell.android.Peer
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.SyncChoice
import org.tidefell.design.TidefellSize
import org.tidefell.design.TidefellSpace
import org.tidefell.ffi.Folder

/** When phone folders sync, and each folder's sync choice and recycle bin on this phone. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, model: SettingsModel = viewModel<SettingsViewModel>()) {
    val folders by model.folders.collectAsStateWithLifecycle()
    val errors by model.errors.collectAsStateWithLifecycle()
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = TidefellSpace.Lg, vertical = TidefellSpace.Sm),
        verticalArrangement = Arrangement.spacedBy(TidefellSpace.Md),
    ) {
        SectionCard("Background sync") {
            BackgroundSettings(model, hasPhoneFolders = folders.any { !PhoneFolders.inAppStorage(it) })
        }
        SectionCard("Network") { NetworkSettings(model) }
        SectionCard("Speed") { LimitSettings(model) }
        SectionCard("Crash reports") { ReportSettings(model) }
        folders.forEach { folder ->
            SectionCard(null) { FolderSettings(model, folder, errors[folder.id]) }
        }
    }
}

@Composable
private fun BackgroundSettings(model: SettingsModel, hasPhoneFolders: Boolean) {
    val background by model.background.collectAsStateWithLifecycle()
    Hint("Folders on this phone also sync when Tidefell is closed: soon after a new photo or video, and every hour.")
    CheckRow("Only on Wi-Fi or other unmetered networks", background.unmeteredOnly) { on ->
        model.setBackground { it.copy(unmeteredOnly = on) }
    }
    CheckRow("Only while charging", background.chargingOnly) { on -> model.setBackground { it.copy(chargingOnly = on) } }
    if (hasPhoneFolders) OutlinedButton(onClick = model::syncNow) { Text("Sync now") }
}

@Composable
private fun NetworkSettings(model: SettingsModel) {
    val localDiscovery by model.localDiscovery.collectAsStateWithLifecycle()
    CheckRow("Find my devices on this network", localDiscovery, model::setLocalDiscovery)
    Hint("Your devices on the same Wi-Fi connect directly, even without internet. This phone's device ID is visible on the network.")
}

@Composable
private fun LimitSettings(model: SettingsModel) {
    val limits by model.limits.collectAsStateWithLifecycle()
    val problem by model.limitsProblem.collectAsStateWithLifecycle()
    var upload by remember(limits) { mutableStateOf(limits.uploadKib.takeIf { it > 0 }?.toString().orEmpty()) }
    var download by remember(limits) { mutableStateOf(limits.downloadKib.takeIf { it > 0 }?.toString().orEmpty()) }
    Hint(
        "Most KiB per second this phone sends to and receives from your other devices. Leave empty for no limit; " +
            "a limit is at least 64.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Sm)) {
        OutlinedTextField(
            value = upload,
            onValueChange = { upload = it.filter(Char::isDigit).take(7) },
            label = { Text("Upload") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = download,
            onValueChange = { download = it.filter(Char::isDigit).take(7) },
            label = { Text("Download") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    CheckRow("Limit devices on this network too", limits.limitLocal) { on -> model.setLimits(limits.copy(limitLocal = on)) }
    val wanted = limits.copy(uploadKib = upload.toLongOrNull() ?: 0, downloadKib = download.toLongOrNull() ?: 0)
    TextButton(enabled = wanted != limits, onClick = { model.setLimits(wanted) }) { Text("Save") }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ReportSettings(model: SettingsModel) {
    val saved by model.reportUrl.collectAsStateWithLifecycle()
    val problem by model.reportUrlProblem.collectAsStateWithLifecycle()
    var url by remember(saved) { mutableStateOf(saved) }
    Hint(
        "Where a crash is reported: an issue tracker's issues page. Reporting opens a new issue there, filled in for " +
            "you to read and send. Leave empty to share the report instead.",
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Issue tracker") },
        placeholder = { Text("https://github.com/owner/repo/issues") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(enabled = url.trim() != saved, onClick = { model.setReportUrl(url) }) { Text("Save") }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = TidefellSpace.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Lg),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A setting's explanation, quieter than the setting. */
@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FolderSettings(model: SettingsModel, folder: Folder, error: String?) {
    val context = LocalContext.current
    var days by remember(folder.id, folder.retentionDays) { mutableStateOf(folder.retentionDays.toString()) }
    var confirmRemoving by rememberSaveable { mutableStateOf(false) }
    val choice = PhoneFolders.choice(folder)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Md)) {
        IconTile(
            if (choice == SyncChoice.FILES_ON_DEMAND) TidefellIcons.Cloud else TidefellIcons.Folder,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(Peer.folderName(folder), style = MaterialTheme.typography.titleMedium)
    }
    if (choice == SyncChoice.FILES_ON_DEMAND) {
        Hint("${choice.title}. ${choice.summary}")
    } else {
        Hint(folder.path)
        SyncChoices(choice) { selected ->
            when {
                selected == choice -> {}
                selected == SyncChoice.REMOVE_AFTER_BACKUP -> confirmRemoving = true
                else -> model.setChoice(folder, selected)
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Sm)) {
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
            onClick = { model.setRetention(folder.id, days.toUInt()) },
        ) { Text("Save") }
    }

    val summary = if (folder.recycledFiles == 0UL) {
        "The recycle bin is empty."
    } else {
        val size = Formatter.formatShortFileSize(context, folder.recycledBytes.toLong())
        val next = folder.nextPurgeMs?.let {
            " The next is removed " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) + "."
        }.orEmpty()
        "${files(folder.recycledFiles.toLong())} in the recycle bin, $size.$next"
    }
    Text(summary, style = MaterialTheme.typography.bodyMedium)
    if (folder.onlyHere > 0UL) {
        // ADR 0022: these have no copy on a device that keeps everything yet.
        Text(
            "${files(folder.onlyHere.toLong())} made on this phone ${if (folder.onlyHere == 1UL) "is" else "are"} not on a " +
                "device that keeps everything, such as a NAS, yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    RebuildSettings(model, folder)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    if (confirmRemoving) {
        ConfirmRemoveAfterBackup(
            onConfirm = {
                confirmRemoving = false
                model.setChoice(folder, SyncChoice.REMOVE_AFTER_BACKUP)
            },
            onDismiss = { confirmRemoving = false },
        )
    }
}

/** When and why the folder's metadata was last started over, and starting it over (ADR 0034). */
@Composable
private fun RebuildSettings(model: SettingsModel, folder: Folder) {
    var confirmRebuilding by rememberSaveable { mutableStateOf(false) }
    folder.rebuild?.let { rebuild ->
        val at = DateUtils.getRelativeTimeSpanString(
            rebuild.finishedMs ?: rebuild.startedMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )
        val done = if (rebuild.finishedMs == null) "Rebuilding since $at" else "Rebuilt $at"
        Hint("$done, as ${rebuild.reason}.")
    }
    TextButton(onClick = { confirmRebuilding = true }) {
        Icon(TidefellIcons.Sync, contentDescription = null, modifier = Modifier.size(TidefellSize.IconMd))
        Text("Rebuild this folder", Modifier.padding(start = TidefellSpace.Sm))
    }
    if (confirmRebuilding) {
        AlertDialog(
            onDismissRequest = { confirmRebuilding = false },
            title = { Text("Rebuild this folder?") },
            text = {
                Text(
                    "Tidefell starts this folder's records over: the files on this phone are matched to what your " +
                        "other devices send. Files, the recycle bin and earlier versions are kept. Only needed if " +
                        "the folder seems wrong; a damaged record is rebuilt by itself.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRebuilding = false
                    model.rebuild(folder.id)
                }) { Text("Rebuild") }
            },
            dismissButton = { TextButton(onClick = { confirmRebuilding = false }) { Text("Cancel") } },
        )
    }
}
