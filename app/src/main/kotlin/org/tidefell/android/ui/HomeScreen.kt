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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.tidefell.android.Peer
import org.tidefell.android.TidefellDocumentsProvider
import org.tidefell.ffi.File
import org.tidefell.ffi.Folder

/** Folders and their files. Files open through the documents provider, fetching placeholders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: HomeViewModel = viewModel()) {
    val node by model.node.collectAsStateWithLifecycle()
    val open by model.open.collectAsStateWithLifecycle()
    val folders by model.folders.collectAsStateWithLifecycle()
    val files by model.files.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = open != null || settings) {
        if (settings) settings = false else model.open(null)
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
        val folder = open
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                node == null -> CircularProgressIndicator(Modifier.padding(24.dp))
                settings -> SettingsScreen()
                folder == null -> {
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
                                modifier = Modifier.clickable { model.open(folder) },
                            )
                        }
                    }
                }
                else -> FileList(folder, files)
            }
        }
    }
}

@Composable
private fun FileList(folder: Folder, files: List<File>) {
    val context = LocalContext.current
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
