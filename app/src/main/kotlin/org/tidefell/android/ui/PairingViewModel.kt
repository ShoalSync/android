package org.tidefell.android.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
        data class Join(val name: String, val offers: List<FolderOffer>) : Step
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
}
