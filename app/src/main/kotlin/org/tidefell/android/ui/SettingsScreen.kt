package org.tidefell.android.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.tidefell.android.Peer
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.SyncChoice
import org.tidefell.ffi.Folder

/** When phone folders sync, and each folder's sync choice and recycle bin on this phone. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, model: SettingsViewModel = viewModel()) {
    val folders by model.folders.collectAsStateWithLifecycle()
    val errors by model.errors.collectAsStateWithLifecycle()
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackgroundSettings(model, hasPhoneFolders = folders.any { !PhoneFolders.inAppStorage(it) })
        folders.forEach { folder ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            FolderSettings(model, folder, errors[folder.id])
        }
    }
}

@Composable
private fun BackgroundSettings(model: SettingsViewModel, hasPhoneFolders: Boolean) {
    val background by model.background.collectAsStateWithLifecycle()
    Text("Background sync", style = MaterialTheme.typography.titleMedium)
    Text(
        "Folders on this phone also sync when Tidefell is closed: soon after a new photo or video, and every hour.",
        style = MaterialTheme.typography.bodySmall,
    )
    CheckRow("Only on Wi-Fi or other unmetered networks", background.unmeteredOnly) { on ->
        model.setBackground { it.copy(unmeteredOnly = on) }
    }
    CheckRow("Only while charging", background.chargingOnly) { on -> model.setBackground { it.copy(chargingOnly = on) } }
    if (hasPhoneFolders) OutlinedButton(onClick = model::syncNow) { Text("Sync now") }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun FolderSettings(model: SettingsViewModel, folder: Folder, error: String?) {
    val context = LocalContext.current
    var days by remember(folder.id, folder.retentionDays) { mutableStateOf(folder.retentionDays.toString()) }
    var confirmRemoving by rememberSaveable { mutableStateOf(false) }
    val choice = PhoneFolders.choice(folder)

    Text(Peer.folderName(folder), style = MaterialTheme.typography.titleMedium)
    if (choice == SyncChoice.FILES_ON_DEMAND) {
        Text("${choice.title}. ${choice.summary}", style = MaterialTheme.typography.bodySmall)
    } else {
        Text(folder.path, style = MaterialTheme.typography.bodySmall)
        SyncChoices(choice) { selected ->
            when {
                selected == choice -> {}
                selected == SyncChoice.REMOVE_AFTER_BACKUP -> confirmRemoving = true
                else -> model.setChoice(folder, selected)
            }
        }
    }

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
        "${folder.recycledFiles} files in the recycle bin, $size.$next"
    }
    Text(summary, style = MaterialTheme.typography.bodyMedium)
    if (folder.onlyHere > 0UL) {
        // ADR 0022: these have no copy on a device that keeps everything yet.
        Text(
            "${folder.onlyHere} files made on this phone are not on a device that keeps everything, such as a NAS, yet.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
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
