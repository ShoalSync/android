package org.tidefell.android.ui

import androidx.compose.foundation.Image
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Shows a pairing code (ADR 0015) until a device holding the user key approves this phone. */
@Composable
fun PairingScreen(onPaired: () -> Unit, model: PairingViewModel = viewModel()) {
    val step by model.step.collectAsStateWithLifecycle()
    var joining by rememberSaveable { mutableStateOf<String?>(null) }
    var joined by rememberSaveable { mutableStateOf(emptyList<String>()) }

    val offer = (step as? PairingViewModel.Step.Join)?.offers?.firstOrNull { it.id == joining }
    Scaffold { padding ->
        if (offer != null) {
            // Not inside the scrolling column: the folder picker scrolls on its own.
            AddFolderScreen(
                offer = offer,
                onDone = {
                    joined = joined + offer.id
                    joining = null
                },
                onCancel = { joining = null },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add this phone to your devices", style = MaterialTheme.typography.headlineSmall)
            when (val s = step) {
                PairingViewModel.Step.Starting -> CircularProgressIndicator()
                is PairingViewModel.Step.ShowCode -> {
                    Text("On a device with your user key, scan this code or run:")
                    Text("tidefell pair <code>", fontFamily = FontFamily.Monospace)
                    Image(qrCode(s.code), contentDescription = "Pairing code", modifier = Modifier.size(280.dp))
                    SelectionContainer {
                        Text(s.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                is PairingViewModel.Step.Confirm -> {
                    Text("${s.approver} wants to add this phone.")
                    Text("Check that it shows the same code:")
                    Text("${s.code.take(3)} ${s.code.drop(3)}", style = MaterialTheme.typography.displayMedium)
                }
                is PairingViewModel.Step.Join -> {
                    Text("Paired as ${s.name}.")
                    if (s.offers.isNotEmpty()) {
                        Text("Folders you can sync:")
                        for (candidate in s.offers) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(candidate.name, Modifier.align(Alignment.CenterVertically))
                                if (candidate.id in joined) {
                                    Text("Joined", Modifier.align(Alignment.CenterVertically))
                                } else {
                                    Button(onClick = { joining = candidate.id }) { Text("Join") }
                                }
                            }
                        }
                    }
                    Button(onClick = onPaired) { Text(if (s.offers.isEmpty() || joined.isNotEmpty()) "Continue" else "Not now") }
                }
                is PairingViewModel.Step.Failed -> {
                    Text(s.message)
                    Button(onClick = model::retry) { Text("Try again") }
                }
            }
        }
    }
}
