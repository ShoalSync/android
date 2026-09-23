package org.tidefell.android.ui

import android.os.Build
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.launch
import org.tidefell.android.Peer
import org.tidefell.ffi.FolderOffer
import org.tidefell.ffi.Pairing
import org.tidefell.ffi.PairingUpdate

private sealed interface Step {
    data object Starting : Step
    data class ShowCode(val code: String) : Step
    data class Confirm(val approver: String, val code: String) : Step
    data class Join(val name: String, val offers: List<FolderOffer>) : Step
    data class Failed(val message: String) : Step
}

/** Shows a pairing code (ADR 0015) until a device holding the user key approves this phone. */
@Composable
fun PairingScreen(onPaired: () -> Unit) {
    var step by remember { mutableStateOf<Step>(Step.Starting) }
    var pairing by remember { mutableStateOf<Pairing?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(attempt) {
        step = Step.Starting
        val started = runCatching { Pairing.start(Peer.dataDir, Peer.settings.network(), Build.MODEL) }
        val current = started.getOrElse {
            step = Step.Failed(it.message ?: "could not start pairing")
            return@LaunchedEffect
        }
        pairing = current
        step = Step.ShowCode(current.code())
        while (true) {
            when (val update = current.nextUpdate()) {
                is PairingUpdate.Confirming -> step = Step.Confirm(update.approverName, update.confirmationCode)
                is PairingUpdate.Failed -> step = Step.ShowCode(current.code())
                is PairingUpdate.Paired -> {
                    step = Step.Join(update.name, update.folders)
                    break
                }
                PairingUpdate.Stopped -> {
                    step = Step.Failed("The code expired.")
                    break
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { pairing?.cancel() } }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add this phone to your devices", style = MaterialTheme.typography.headlineSmall)
            when (val s = step) {
                Step.Starting -> CircularProgressIndicator()
                is Step.ShowCode -> {
                    Text("On a device with your user key, scan this code or run:")
                    Text("tidefell pair <code>", fontFamily = FontFamily.Monospace)
                    Image(qrCode(s.code), contentDescription = "Pairing code", modifier = Modifier.size(280.dp))
                    SelectionContainer {
                        Text(s.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                is Step.Confirm -> {
                    Text("${s.approver} wants to add this phone.")
                    Text("Check that it shows the same code:")
                    Text("${s.code.take(3)} ${s.code.drop(3)}", style = MaterialTheme.typography.displayMedium)
                }
                is Step.Join -> {
                    Text("Paired as ${s.name}.")
                    if (s.offers.isEmpty()) {
                        Button(onClick = onPaired) { Text("Continue") }
                    } else {
                        Text("Folders you can sync:")
                        for (offer in s.offers) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(offer.name, Modifier.align(Alignment.CenterVertically))
                                Button(onClick = {
                                    scope.launch {
                                        join(offer)
                                        onPaired()
                                    }
                                }) { Text("Join") }
                            }
                        }
                        Button(onClick = onPaired) { Text("Not now") }
                    }
                }
                is Step.Failed -> {
                    Text(s.message)
                    Button(onClick = { attempt++ }) { Text("Try again") }
                }
            }
        }
    }
}

/** Joins an offered folder as a partial replica within the phone's budget. */
suspend fun join(offer: FolderOffer) {
    val node = Peer.start()
    node.joinFolder(
        offer.id,
        File(Peer.foldersDir, offer.name).path,
        Peer.settings.budgetBytes.toULong(),
        offer.members,
        offer.retentionDays,
    )
}
