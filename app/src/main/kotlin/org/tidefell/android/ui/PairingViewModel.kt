package org.tidefell.android.ui

import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tidefell.android.Peer
import org.tidefell.ffi.FolderOffer
import org.tidefell.ffi.Pairing
import org.tidefell.ffi.PairingUpdate

/**
 * Pairing this phone (ADR 0015). The pairing outlives the screen's configuration changes, so
 * rotating the phone keeps the code on screen instead of starting over.
 */
class PairingViewModel : ViewModel() {
    sealed interface Step {
        data object Starting : Step
        data class ShowCode(val code: String) : Step
        data class Confirm(val approver: String, val code: String) : Step
        data class Join(val name: String, val offers: List<FolderOffer>, val error: String? = null) : Step
        data class Failed(val message: String) : Step
    }

    private val current = MutableStateFlow<Step>(Step.Starting)
    private var pairing: Pairing? = null
    private var job: Job? = null

    val step: StateFlow<Step> = current

    init {
        start()
    }

    /** Starts over with a new code. */
    fun retry() = start()

    /** Joins [offer], then calls [onJoined]; stays on the offers with an error if joining fails. */
    fun join(offer: FolderOffer, onJoined: () -> Unit) {
        viewModelScope.launch {
            runCatching { join(offer) }
                .onSuccess { onJoined() }
                .onFailure { e ->
                    Log.w(TAG, "joining ${offer.name} failed", e)
                    val step = current.value
                    if (step is Step.Join) current.value = step.copy(error = e.message ?: e.toString())
                }
        }
    }

    private fun start() {
        job?.cancel()
        pairing?.cancel()
        pairing = null
        job = viewModelScope.launch {
            current.value = Step.Starting
            val started = runCatching { Pairing.start(Peer.dataDir, Peer.settings.network(), Build.MODEL) }
            val session = started.getOrElse {
                current.value = Step.Failed(it.message ?: "could not start pairing")
                return@launch
            }
            pairing = session
            current.value = Step.ShowCode(session.code())
            while (true) {
                when (val update = session.nextUpdate()) {
                    is PairingUpdate.Confirming -> current.value = Step.Confirm(update.approverName, update.confirmationCode)
                    is PairingUpdate.Failed -> current.value = Step.ShowCode(session.code())
                    is PairingUpdate.Paired -> {
                        current.value = Step.Join(update.name, update.folders)
                        break
                    }
                    PairingUpdate.Stopped -> {
                        current.value = Step.Failed("The code expired.")
                        break
                    }
                }
            }
        }
    }

    override fun onCleared() {
        pairing?.cancel()
    }

    private companion object {
        const val TAG = "PairingViewModel"
    }
}

/** Joins an offered folder as a partial replica within the phone's budget. */
suspend fun join(offer: FolderOffer) {
    val node = Peer.start()
    withContext(Dispatchers.IO) {
        node.joinFolder(
            offer.id,
            File(Peer.foldersDir, offer.name).path,
            Peer.settings.budgetBytes.toULong(),
            offer.members,
            offer.retentionDays,
        )
    }
}
