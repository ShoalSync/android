package org.tidefell.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.PhoneStorage
import org.tidefell.android.folders.SyncChoice
import org.tidefell.ffi.Folder
import org.tidefell.ffi.FolderOffer

/**
 * Adding a folder (ADR 0025): a new one at a folder on the phone, or [offer], a folder shared
 * from another device, either on the phone or in app storage with files on demand.
 */
class AddFolderViewModel(application: Application) : AndroidViewModel(application) {
    sealed interface Step {
        /** Where the folder lives: only asked when joining, where app storage is an option. */
        data object Place : Step

        /** All files access is needed before a phone folder can be picked. */
        data object Access : Step

        /** Browsing: [dir] is null at the starting places. */
        data class Pick(val dir: File?, val places: List<PhoneStorage.Place>, val subfolders: List<File>) : Step

        data class Choose(val dir: File) : Step

        data object Working : Step

        data class Done(val folder: Folder) : Step

        data class Failed(val message: String) : Step
    }

    private val current = MutableStateFlow<Step>(Step.Access)
    private var offer: FolderOffer? = null

    val step: StateFlow<Step> = current

    private var started = false

    /**
     * Begins adding a new folder, or joining [offer]. Does nothing if already begun, so a
     * rotation keeps the step, unless [again].
     */
    fun start(offer: FolderOffer?, again: Boolean = false) {
        if (started && !again) return
        started = true
        this.offer = offer
        current.value = if (offer != null) Step.Place else pickOrAccess()
    }

    /** Forgets this visit, so the next one starts at the beginning. */
    fun reset() {
        started = false
    }

    /** Joins the offered folder in app storage, with files on demand. */
    fun useAppStorage() = finish { PhoneFolders.join(offer!!, null, SyncChoice.FILES_ON_DEMAND) }

    /** Continues to picking a folder on the phone. */
    fun usePhoneFolder() {
        current.value = pickOrAccess()
    }

    /** Called when the user comes back from the system's access screen. */
    fun accessChanged() {
        if (current.value == Step.Access && PhoneStorage.hasAccess()) current.value = pick(null)
    }

    fun open(dir: File?) {
        current.value = pick(dir)
    }

    /** Goes up one folder, or back to the starting places; false if already there. */
    fun up(): Boolean {
        val step = current.value as? Step.Pick ?: return false
        val dir = step.dir ?: return false
        val place = step.places.firstOrNull { it.dir.canonicalFile == dir.canonicalFile }
        current.value = pick(if (place != null) null else dir.parentFile)
        return true
    }

    fun choose(dir: File) {
        current.value = Step.Choose(dir)
    }

    fun add(dir: File, choice: SyncChoice) = finish {
        val offer = offer
        if (offer == null) PhoneFolders.create(dir, choice) else PhoneFolders.join(offer, dir, choice)
    }

    private fun finish(add: suspend () -> Folder) {
        current.value = Step.Working
        viewModelScope.launch {
            current.value = runCatching { add() }
                .fold({ Step.Done(it) }, { Step.Failed(it.message ?: it.toString()) })
        }
    }

    private fun pickOrAccess(): Step = if (PhoneStorage.hasAccess()) pick(null) else Step.Access

    private fun pick(dir: File?): Step.Pick {
        val context = getApplication<Application>()
        val places = PhoneStorage.places(context)
        return Step.Pick(dir, places, dir?.let(PhoneStorage::subfolders).orEmpty())
    }
}
