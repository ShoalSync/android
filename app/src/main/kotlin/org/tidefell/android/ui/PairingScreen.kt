package org.tidefell.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.tidefell.ffi.Folder
import org.tidefell.ffi.FolderOffer

/**
 * Shows a pairing code (ADR 0015) until a device holding the user key approves this phone, then
 * offers its folders. Joining one is a slot, so screenshot tests can give it its own model.
 */
@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    model: PairingModel = viewModel<PairingViewModel>(),
    joinFolder: @Composable (
        offer: FolderOffer,
        onDone: (Folder) -> Unit,
        onCancel: () -> Unit,
        modifier: Modifier
    ) -> Unit =
        { offer, onDone, onCancel, modifier -> AddFolderScreen(offer, onDone, onCancel, modifier) },
) {
    val step by model.step.collectAsStateWithLifecycle()
    var joining by rememberSaveable { mutableStateOf<String?>(null) }
    var joined by rememberSaveable { mutableStateOf(emptyList<String>()) }

    val offer = (step as? PairingViewModel.Step.Join)?.offers?.firstOrNull { it.id == joining }
    Scaffold { padding ->
        if (offer != null) {
            // Not inside the scrolling column: the folder picker scrolls on its own.
            joinFolder(
                offer,
                {
                    joined = joined + offer.id
                    joining = null
                },
                { joining = null },
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandMark(64.dp)
            Text("Add this phone to your devices", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            when (val s = step) {
                PairingViewModel.Step.Starting -> CircularProgressIndicator()
                is PairingViewModel.Step.ShowCode -> {
                    Text(
                        "On a device with your user key, scan this code or run:",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Code("tidefell pair <code>")
                    // The code stays dark on white in the dark theme too, for scanners.
                    Image(
                        qrCode(s.code),
                        contentDescription = "Pairing code",
                        modifier = Modifier.clip(MaterialTheme.shapes.large).background(Color.White).padding(12.dp).size(256.dp),
                    )
                    SelectionContainer {
                        Text(
                            s.code,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                is PairingViewModel.Step.Confirm -> {
                    Text("${s.approver} wants to add this phone.", style = MaterialTheme.typography.titleMedium)
                    Text("Check that it shows the same code:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Text(
                            "${s.code.take(3)} ${s.code.drop(3)}",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                        )
                    }
                }
                is PairingViewModel.Step.Join -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(TidefellIcons.Ok, contentDescription = null, tint = MaterialTheme.statusColors.ok)
                        Text("Paired as ${s.name}.", style = MaterialTheme.typography.titleMedium)
                    }
                    if (s.offers.isNotEmpty()) {
                        SectionCard("Folders you can sync") {
                            for (candidate in s.offers) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    IconTile(
                                        TidefellIcons.Folder,
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(candidate.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    if (candidate.id in joined) {
                                        StatusLine(TidefellIcons.Ok, "Joined", MaterialTheme.statusColors.ok)
                                    } else {
                                        Button(onClick = { joining = candidate.id }) { Text("Join") }
                                    }
                                }
                            }
                        }
                    }
                    Button(onClick = onPaired) { Text(if (s.offers.isEmpty() || joined.isNotEmpty()) "Continue" else "Not now") }
                }
                is PairingViewModel.Step.Failed -> {
                    Banner(
                        TidefellIcons.Error,
                        s.message,
                        error = true,
                    )
                    Button(onClick = model::retry) { Text("Try again") }
                }
            }
        }
    }
}

/** A command to type, set apart from the text around it. */
@Composable
private fun Code(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
