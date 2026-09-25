package org.tidefell.android.ui

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.tidefell.android.Peer
import org.tidefell.android.TidefellDocumentsProvider
import org.tidefell.ffi.Folder

/** Folders and their files. Files open through the documents provider, fetching placeholders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val node by Peer.node.collectAsState()
    var open by remember { mutableStateOf<Folder?>(null) }
    var settings by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        Peer.start()
        Peer.events.collect { revision++ }
    }
    BackHandler(enabled = open != null || settings) {
        if (settings) settings = false else open = null
    }

    val title = when {
        settings -> "Settings"
        else -> open?.let(Peer::folderName) ?: "Tidefell"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = {
                    if (!settings && open == null) TextButton(onClick = { settings = true }) { Text("Settings") }
                },
            )
        },
    ) { padding ->
        val current = node
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                current == null -> CircularProgressIndicator(Modifier.padding(24.dp))
                settings -> SettingsScreen(current, revision)
                open == null -> {
                    val folders = remember(current, revision) { current.folders() }
                    if (folders.isEmpty()) {
                        Text("No folders yet.", Modifier.padding(24.dp))
                    }
                    LazyColumn {
                        items(folders, key = { it.id }) { folder ->
                            ListItem(
                                headlineContent = { Text(Peer.folderName(folder)) },
                                supportingContent = {
                                    Text(if (folder.partialBudget != null) "Files on demand" else "Everything on this phone")
                                },
                                modifier = Modifier.clickable { open = folder },
                            )
                        }
                    }
                }
                else -> FileList(current, open!!, revision)
            }
        }
    }
}

@Composable
private fun FileList(node: org.tidefell.ffi.Node, folder: Folder, revision: Int) {
    val context = LocalContext.current
    val files = remember(folder.id, revision) { runCatching { node.files(folder.id) }.getOrDefault(emptyList()) }
    LazyColumn {
        items(files, key = { it.entry }) { file ->
            ListItem(
                headlineContent = { Text(file.path) },
                supportingContent = {
                    Text(
                        when {
                            file.onlyHere -> "Only on this phone"
                            file.onDisk -> "On this phone"
                            else -> "On your other devices"
                        },
                    )
                },
                modifier = Modifier.clickable {
                    val uri = DocumentsContract.buildDocumentUri(TidefellDocumentsProvider.AUTHORITY, "${folder.id}/${file.path}")
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, TidefellDocumentsProvider.mimeOf(file.path))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(Intent.createChooser(intent, file.path)) }
                },
            )
        }
    }
}
