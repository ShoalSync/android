package org.tidefell.android.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.tidefell.design.TidefellIcons
import org.tidefell.design.TidefellSpace
import org.tidefell.ffi.Folder
import org.tidefell.ffi.Priorities
import org.tidefell.ffi.StorageDropResult
import org.tidefell.ffi.StoragePeerReport

/** A file or directory priority no rule sets (ADR 0023). */
const val DEFAULT_PRIORITY = 128

/** The highest priority. */
const val MAX_PRIORITY = 255
private const val LOW_BELOW = 64
private const val HIGH_FROM = 192
private const val PRESET_LOW = 50
private const val PRESET_HIGH = 200

/** How many characters of a storage peer's device ID name it on the page. */
private const val DEVICE_SHOWN = 12

/** How many files with no other copy are listed before "and N more". */
private const val AT_RISK_SHOWN = 15

/** An encrypted folder's priorities and what its storage peers keep, as the Storage page shows them. */
data class FolderStorage(val rows: List<StorageRow>, val peers: List<StoragePeerReport>)

/** A line of the folder's tree: a file or directory, after its parent's line. */
data class StorageRow(
    val path: String,
    val name: String,
    val depth: Int,
    val directory: Boolean,
    val priority: Int,
    /** Whether a rule for exactly this path sets it, which "Use the default" removes. */
    val setHere: Boolean,
    /** What gives it its priority, in words. */
    val source: String,
)

/** Whether this phone ranks what [folder]'s storage peers keep: it is encrypted, and has some. */
fun ranksStorage(folder: Folder): Boolean = folder.encryption?.storagePeers?.isNotEmpty() == true

/**
 * The tree's lines: directories before files, each by name, every line after its parent's. The
 * engine lists every directory a file is in; one it does not is shown at the default.
 */
fun storageRows(priorities: Priorities): List<StorageRow> {
    class Dir {
        var entry: org.tidefell.ffi.PriorityEntry? = null
        val dirs = sortedMapOf<String, Dir>()
        val files = sortedMapOf<String, org.tidefell.ffi.PriorityEntry>()
    }
    val top = Dir()
    for (entry in priorities.entries) {
        val parts = entry.path.split('/')
        var dir = top
        for (part in parts.dropLast(1)) dir = dir.dirs.getOrPut(part) { Dir() }
        if (entry.directory) dir.dirs.getOrPut(parts.last()) { Dir() }.entry = entry else dir.files[parts.last()] = entry
    }
    val rows = mutableListOf<StorageRow>()
    fun row(path: String, name: String, depth: Int, directory: Boolean, priority: Int, rule: UInt?): StorageRow {
        val pattern = rule?.let { priorities.rules.getOrNull(it.toInt())?.pattern }
        val setHere = pattern == patternFor(path, directory)
        val source = when {
            pattern == null -> "the default"
            setHere -> "set here"
            else -> "from the rule for $pattern"
        }
        return StorageRow(path, name, depth, directory, priority, setHere, source)
    }
    fun add(dir: Dir, at: String, depth: Int) {
        for ((name, sub) in dir.dirs) {
            val path = if (at.isEmpty()) name else "$at/$name"
            val entry = sub.entry
            rows += row(path, name, depth, true, entry?.priority?.toInt() ?: DEFAULT_PRIORITY, entry?.rule)
            add(sub, path, depth + 1)
        }
        for ((name, entry) in dir.files) rows += row(entry.path, name, depth, false, entry.priority.toInt(), entry.rule)
    }
    add(top, "", 0)
    return rows
}

/** The rule pattern for exactly the file or directory at [path]: anchored, special characters escaped. */
fun patternFor(path: String, directory: Boolean): String {
    val trailing = path.length - path.trimEnd(' ').length
    val out = StringBuilder("/")
    path.forEachIndexed { i, c ->
        if (c in "\\*?[]" || (c == ' ' && i >= path.length - trailing)) out.append('\\')
        out.append(c)
    }
    if (directory) out.append('/')
    return out.toString()
}

/** A priority in a word. */
fun priorityWord(priority: Int): String = when {
    priority == 0 -> "never stored"
    priority < LOW_BELOW -> "low"
    priority < DEFAULT_PRIORITY -> "below normal"
    priority == DEFAULT_PRIORITY -> "normal"
    priority < HIGH_FROM -> "above normal"
    else -> "high"
}

/** A storage peer's name on the page: the start of its device ID. */
fun shortDevice(device: String): String = device.take(DEVICE_SHOWN)

/** What a storage peer holds, and how much of each priority, in lines. */
fun storageLines(context: Context, report: StoragePeerReport): List<String> {
    fun size(bytes: ULong) = Formatter.formatShortFileSize(context, bytes.toLong())
    val head = when {
        report.error != null -> "Did not say: ${report.error}"
        !report.connected -> "Not connected"
        report.heldBytes != null -> "Holds ${size(report.heldBytes!!)}, room for ${size(report.roomBytes ?: 0u)} more"
        else -> "Did not say"
    }
    return listOf(head) + report.levels.map { level ->
        val state = when {
            level.priority.toInt() == 0 -> "never stored there"
            level.heldBytes >= level.bytes -> "complete"
            else -> "${level.heldBytes * 100u / maxOf(level.bytes, 1u)}%, ${size(level.bytes - level.heldBytes)} not stored"
        }
        "${level.priority}: ${files(level.files.toInt())}, ${size(level.bytes)}: $state"
    }
}

/**
 * An encrypted folder's storage page (ADR 0023): what each storage peer keeps, with dropping
 * what is ranked low from one, and the folder's tree with each file and directory's priority.
 * [focus] opens the priority of that path at once, as a file's actions ask.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoragePage(model: HomeModel, folder: Folder, focus: String?) {
    val context = LocalContext.current
    val storage by model.storage.collectAsStateWithLifecycle()
    var ranking by remember { mutableStateOf<StorageRow?>(null) }
    var dropping by remember { mutableStateOf<StoragePeerReport?>(null) }
    var focused by remember { mutableStateOf(false) }
    val shown = storage
    LaunchedEffect(shown, focus) {
        if (!focused && focus != null && shown != null) {
            focused = true
            ranking = shown.rows.firstOrNull { it.path == focus }
        }
    }
    if (shown == null) {
        EmptyState(TidefellIcons.Cloud, "Asking the storage peers…")
        return
    }
    LazyColumn(Modifier.padding(horizontal = TidefellSpace.Lg)) {
        item {
            Text(
                "When a storage peer runs short of room it keeps what is ranked higher first, " +
                    "and never stores what is ranked 0. Nothing is dropped unless you ask.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = TidefellSpace.Sm),
            )
        }
        items(shown.peers, key = { "peer-" + it.device }) { peer ->
            SectionCard("Storage peer ${shortDevice(peer.device)}", Modifier.padding(vertical = TidefellSpace.Xs)) {
                storageLines(context, peer).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (peer.connected) {
                    FlowRow { ActionButton("Drop below…", onClick = { dropping = peer }, kind = ButtonKind.Quiet) }
                }
            }
        }
        items(shown.rows, key = { "row-" + it.path }) { row ->
            ListItem(
                leadingContent = { IconTile(if (row.directory) TidefellIcons.Folder else TidefellIcons.File) },
                headlineContent = { Text(row.name) },
                supportingContent = {
                    Text(
                        "${row.priority} · ${priorityWord(row.priority)} · ${row.source}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier
                    .padding(start = (16 * row.depth).dp)
                    .clickable { ranking = row },
            )
        }
    }
    ranking?.let { row ->
        PriorityDialog(row, onSet = { model.setPriority(folder.id, row.path, row.directory, it) }) { ranking = null }
    }
    dropping?.let { peer -> DropDialog(model, folder, peer) { dropping = null } }
}

/** Sets a file or directory's priority, or puts it back to the default. */
@Composable
private fun PriorityDialog(row: StorageRow, onSet: (Int?) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(row.priority.toFloat()) }
    val priority = value.toInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.path) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TidefellSpace.Sm)) {
                Text("Now ${row.priority} (${priorityWord(row.priority)}), ${row.source}.")
                Text("$priority · ${priorityWord(priority)}", style = MaterialTheme.typography.titleMedium)
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..MAX_PRIORITY.toFloat(), steps = MAX_PRIORITY - 1)
                FlowRowOfPresets { value = it.toFloat() }
                if (row.directory) {
                    Text(
                        "Covers what is in it, unless something there has its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            ActionButton("Set", onClick = {
                onSet(priority)
                onDismiss()
            }, kind = ButtonKind.Primary)
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                ActionButton("Cancel", onClick = onDismiss, kind = ButtonKind.Quiet)
                if (row.setHere) {
                    ActionButton("Use the default", onClick = {
                        onSet(null)
                        onDismiss()
                    }, kind = ButtonKind.Quiet)
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowOfPresets(choose: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Sm)) {
        listOf(
            "Never" to 0,
            "Low" to PRESET_LOW,
            "Normal" to DEFAULT_PRIORITY,
            "High" to PRESET_HIGH,
            "Highest" to MAX_PRIORITY,
        ).forEach { (label, p) ->
            Chip(label, onClick = { choose(p) })
        }
    }
}

/** Asks a storage peer to forget what is ranked below a priority: asked first, and again for files with no other copy. */
@Composable
private fun DropDialog(model: HomeModel, folder: Folder, peer: StoragePeerReport, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var below by remember { mutableFloatStateOf(DEFAULT_PRIORITY.toFloat()) }
    var atRisk by remember { mutableStateOf<StorageDropResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun drop(force: Boolean) {
        busy = true
        scope.launch {
            val result = runCatching { model.storageDrop(folder.id, peer.device, below.toInt(), force) }
            busy = false
            result.onSuccess {
                if (it.done) {
                    model.show("The storage peer dropped ${Formatter.formatShortFileSize(context, it.droppedBytes.toLong())}")
                    onDismiss()
                } else {
                    atRisk = it
                }
            }.onFailure {
                model.show("Not dropped: ${it.message}")
                onDismiss()
            }
        }
    }
    val risky = atRisk
    if (risky != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { androidx.compose.material3.Icon(TidefellIcons.Warning, contentDescription = null) },
            title = { Text("No other copy") },
            text = {
                val shown = risky.atRisk.take(AT_RISK_SHOWN)
                val more = risky.atRisk.size - shown.size
                Text(
                    "Nothing was dropped. No copy is known of these files other than on that storage peer, " +
                        "so dropping them may lose them:\n\n" +
                        shown.joinToString("\n") + (if (more > 0) "\nand $more more" else ""),
                )
            },
            confirmButton = { ActionButton("Drop anyway", onClick = { drop(force = true) }, kind = ButtonKind.Danger) },
            dismissButton = { ActionButton("Keep them", onClick = onDismiss, kind = ButtonKind.Quiet) },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Drop from ${shortDevice(peer.device)}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TidefellSpace.Sm)) {
                Text("Files ranked below ${below.toInt()}")
                Slider(value = below, onValueChange = { below = it }, valueRange = 1f..MAX_PRIORITY.toFloat(), steps = MAX_PRIORITY - 2)
                Text(
                    "The storage peer forgets what it holds of them, for room for what is ranked higher. " +
                        "It fetches them again, highest first, only when it has room.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { ActionButton("Drop", onClick = { if (!busy) drop(force = false) }, kind = ButtonKind.Danger) },
        dismissButton = { ActionButton("Cancel", onClick = onDismiss, kind = ButtonKind.Quiet) },
    )
}
