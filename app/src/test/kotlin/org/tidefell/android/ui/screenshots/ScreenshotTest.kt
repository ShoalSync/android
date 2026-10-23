package org.tidefell.android.ui.screenshots

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tidefell.android.Peer
import org.tidefell.android.folders.PhoneStorage
import org.tidefell.android.ui.AddFolderScreen
import org.tidefell.android.ui.HomeScreen
import org.tidefell.android.ui.Page
import org.tidefell.android.ui.PairingScreen
import org.tidefell.android.ui.SettingsScreen
import org.tidefell.android.ui.StatusPage
import org.tidefell.android.ui.TidefellTheme
import java.io.File
import org.tidefell.android.ui.AddFolderViewModel.Step as AddStep
import org.tidefell.android.ui.PairingViewModel.Step as PairStep

/**
 * Every screen of the app, in each state worth seeing, drawn on the JVM and compared with the
 * reference images in `src/test/screenshots/`. `./gradlew :app:recordRoborazziDebug` writes them
 * again after a deliberate change; look at what changed before committing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain application: the app's own starts the native engine, which the JVM cannot load.
@Config(sdk = [35], application = Application::class, qualifiers = RobolectricDeviceQualifiers.Pixel7)
@OptIn(ExperimentalRoborazziApi::class)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        Peer.init(ApplicationProvider.getApplicationContext())
    }

    /** Shows the app's home screen with [home], and [settings], [status] or [add] for those pages. */
    private fun home(
        home: FakeHome,
        settings: FakeSettings = FakeSettings(),
        status: FakeStatus = FakeStatus(),
        add: FakeAddFolder = FakeAddFolder(AddStep.Place),
        dark: Boolean = false,
    ) = show(dark) {
        HomeScreen(
            model = home,
            settings = { SettingsScreen(model = settings) },
            status = { StatusPage(model = status) },
            addFolder = { onDone, onCancel -> AddFolderScreen(offer = null, onDone = onDone, onCancel = onCancel, model = add) },
        )
    }

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { TidefellTheme(dark = dark, content = content) }
    }

    /** A page that scrolls is drawn on a taller screen, so all of it shows. */
    private fun tall() = RuntimeEnvironment.setQualifiers("+h1600dp")

    private fun click(text: String) {
        compose.onNodeWithText(text).performClick()
    }

    private fun longClick(text: String) {
        compose.onNodeWithText(text).performTouchInput { longClick() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/$name.png")
    }

    // Folders.

    @Test
    fun folders() {
        home(FakeHome())
        capture("01-folders")
    }

    @Test
    fun foldersDark() {
        home(FakeHome(), dark = true)
        capture("01-folders-dark")
    }

    @Test
    fun foldersEmpty() {
        home(FakeHome().apply { folders.value = emptyList() })
        capture("02-folders-empty")
    }

    @Test
    fun starting() {
        home(FakeHome().apply { started.value = false })
        capture("03-starting")
    }

    @Test
    fun revoked() {
        home(FakeHome().apply { status.value = Sample.status.copy(revoked = true) })
        capture("04-folders-revoked")
    }

    @Test
    fun pausedWithAnEarlierCrash() {
        home(
            FakeHome().apply {
                status.value = Sample.status.copy(paused = true)
                crashes.value = listOf(Sample.crash)
            },
        )
        capture("04-folders-paused-earlier-crash")
    }

    @Test
    fun crashed() {
        home(FakeHome().apply { crashed.value = "the materialise task panicked: disk I/O error" })
        capture("05-folders-crashed")
    }

    @Test
    fun message() {
        home(FakeHome().apply { message.value = "Syncing is paused on this phone." })
        capture("06-folders-message")
    }

    @Test
    fun rebuilding() {
        home(
            FakeHome().apply {
                folders.value = listOf(
                    Sample.camera,
                    Sample.documents.copy(
                        state = org.tidefell.ffi.FolderState.REBUILDING,
                        rebuild = org.tidefell.ffi.Rebuild(
                            Sample.minutesAgo(10),
                            "its metadata database was damaged",
                            null,
                            emptyList(),
                        ),
                    ),
                )
            },
        )
        capture("07-folders-rebuilding")
    }

    @Test
    fun foldersNeedingAttention() {
        home(
            FakeHome().apply {
                folders.value = listOf(
                    Sample.camera.copy(state = org.tidefell.ffi.FolderState.IDLE, pendingFiles = 0u, heldDeletes = 240u),
                    Sample.documents.copy(conflicts = 0u, problems = 0u, lowSpace = LOW_SPACE),
                    Sample.shared().copy(state = org.tidefell.ffi.FolderState.ERROR, error = UNAVAILABLE, unavailable = UNAVAILABLE),
                )
            },
        )
        capture("08-folders-attention")
    }

    // A folder's files.

    private fun documents() = FakeHome(Page.Files(Sample.documents.id))

    @Test
    fun files() {
        home(documents())
        capture("10-files")
    }

    @Test
    fun filesDark() {
        home(documents(), dark = true)
        capture("10-files-dark")
    }

    @Test
    fun filesWithProblems() {
        home(documents())
        click("1 file with problems")
        capture("11-files-problems")
    }

    @Test
    fun renameForThisPhone() {
        home(documents())
        click("1 file with problems")
        // The text field's cursor blinks for ever, so the clock is moved by hand.
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        click("Rename")
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        captureScreenRoboImage("src/test/screenshots/12-files-rename.png")
    }

    @Test
    fun fileActions() {
        home(documents())
        click("Notes.md")
        capture("13-file-actions")
    }

    @Test
    fun fileActionsNotOnThePhone() {
        home(documents())
        click("Recipes/Bread.pdf")
        capture("13-file-actions-not-here")
    }

    @Test
    fun deleteEverywhere() {
        home(documents())
        click("Notes.md")
        click("Delete from all devices")
        capture("14-file-delete-everywhere")
    }

    @Test
    fun filesOnDemand() {
        home(FakeHome(Page.Files(Sample.shared().id)))
        capture("15-files-on-demand")
    }

    @Test
    fun filesOnDemandActions() {
        home(FakeHome(Page.Files(Sample.shared().id)))
        longClick("Recipes/Bread.pdf")
        capture("15-files-on-demand-actions")
    }

    @Test
    fun cleanUp() {
        home(FakeHome(Page.Files(Sample.camera.id)))
        click("Clean up")
        capture("16-files-clean-up")
    }

    @Test
    fun filesPaused() {
        home(
            documents().apply {
                folders.value = listOf(Sample.documents.copy(paused = true, state = org.tidefell.ffi.FolderState.PAUSED))
                problems.value = emptyList()
            },
        )
        capture("17-files-paused")
    }

    @Test
    fun filesEmpty() {
        home(
            documents().apply {
                files.value = emptyList()
                problems.value = emptyList()
            },
        )
        capture("18-files-empty")
    }

    @Test
    fun folderError() {
        home(
            documents().apply {
                folders.value = listOf(
                    Sample.documents.copy(state = org.tidefell.ffi.FolderState.ERROR, error = "the folder's drive is not mounted"),
                )
            },
        )
        capture("19-files-folder-error")
    }

    // What keeps a folder from syncing as usual (ADR 0037).

    private fun documentsWith(folder: org.tidefell.ffi.Folder) = documents().apply {
        folders.value = listOf(folder)
        problems.value = emptyList()
    }

    private val heldDeletes = Sample.documents.copy(conflicts = 0u, problems = 0u, heldDeletes = 240u)

    @Test
    fun folderUnavailable() {
        home(
            documentsWith(
                Sample.documents.copy(
                    state = org.tidefell.ffi.FolderState.ERROR,
                    error = UNAVAILABLE,
                    unavailable = UNAVAILABLE,
                    conflicts = 0u,
                    problems = 0u,
                ),
            ),
        )
        capture("19-files-unavailable")
    }

    @Test
    fun folderHeldDeletes() {
        home(documentsWith(heldDeletes))
        capture("19-files-held-deletes")
    }

    @Test
    fun folderHeldDeletesList() {
        home(documentsWith(heldDeletes))
        click("Show files")
        capture("19-files-held-deletes-list")
    }

    private fun documentsWithdrawing() = documentsWith(Sample.documents.copy(conflicts = 0u, problems = 0u)).apply {
        heldWithdrawals.value = listOf(org.tidefell.ffi.HeldWithdrawals("d".repeat(64), Sample.heldDeletes))
    }

    @Test
    fun folderHeldWithdrawals() {
        home(documentsWithdrawing())
        capture("19-files-held-withdrawals")
    }

    @Test
    fun folderHeldWithdrawalsConfirm() {
        home(documentsWithdrawing())
        click("Withdraw")
        capture("19-files-held-withdrawals-confirm")
    }

    @Test
    fun folderHeldDeletesConfirm() {
        home(documentsWith(heldDeletes))
        click("Delete everywhere")
        capture("19-files-held-deletes-confirm")
    }

    @Test
    fun folderLowSpace() {
        home(documentsWith(Sample.documents.copy(conflicts = 0u, problems = 0u, lowSpace = LOW_SPACE)))
        capture("19-files-low-space")
    }

    @Test
    fun filesWithLastingProblems() {
        home(
            documents().apply {
                folders.value = listOf(Sample.documents.copy(problems = 2u))
                problems.value = Sample.problems + org.tidefell.ffi.PathProblem(
                    path = "Letters/To the council.odt",
                    operation = org.tidefell.ffi.Operation.DELETE,
                    kind = org.tidefell.ffi.ProblemKind.PERMISSION_DENIED,
                    message = "Permission denied (os error 13)",
                    sinceMs = Sample.minutesAgo(45),
                    attempts = 0u,
                )
            },
        )
        click("2 files with problems")
        capture("11-files-problems-lasting")
    }

    // Activity.

    @Test
    fun activity() {
        home(FakeHome(Page.Activity(Sample.documents.id)))
        capture("20-activity")
    }

    @Test
    fun activityEmpty() {
        home(FakeHome(Page.Activity(Sample.documents.id)).apply { activity.value = emptyList() })
        capture("21-activity-empty")
    }

    // Recycle bin.

    private fun recycleBin() = FakeHome(Page.RecycleBin(Sample.documents.id))

    @Test
    fun recycleBinItems() {
        home(recycleBin())
        capture("30-recycle-bin")
    }

    @Test
    fun recycledItem() {
        home(recycleBin())
        click("Old notes.md")
        capture("31-recycle-bin-item")
    }

    @Test
    fun removedHereItem() {
        home(recycleBin())
        click("Scans/Receipt.jpg")
        capture("31-recycle-bin-item-here-only")
    }

    @Test
    fun emptyRecycleBin() {
        home(recycleBin())
        click("Empty recycle bin")
        capture("32-recycle-bin-empty-confirm")
    }

    @Test
    fun recycleBinEmpty() {
        home(recycleBin().apply { recycleBin.value = emptyList() })
        capture("33-recycle-bin-nothing")
    }

    // Earlier versions and conflicts.

    @Test
    fun history() {
        home(FakeHome(Page.History(Sample.documents.id, "Budget 2026.ods")))
        capture("40-history")
    }

    @Test
    fun restoreVersion() {
        home(FakeHome(Page.History(Sample.documents.id, "Budget 2026.ods")))
        compose.onAllNodesWithText("Modified", substring = true)[0].performClick()
        capture("41-history-restore")
    }

    @Test
    fun historyEmpty() {
        home(FakeHome(Page.History(Sample.documents.id, "Notes.md")).apply { versions.value = Sample.history.take(1) })
        capture("42-history-nothing")
    }

    @Test
    fun conflict() {
        home(FakeHome(Page.Conflict(Sample.documents.id, "Budget 2026.ods")).apply { versions.value = Sample.conflict })
        capture("50-conflict")
    }

    @Test
    fun conflictDecided() {
        home(FakeHome(Page.Conflict(Sample.documents.id, "Budget 2026.ods")).apply { versions.value = Sample.conflict.take(1) })
        capture("51-conflict-decided")
    }

    // What an encrypted folder's storage peers keep (ADR 0023).

    private fun vault(page: Page) = FakeHome(page).apply { folders.value = Sample.folders + Sample.vault }

    @Test
    fun storage() {
        tall()
        home(vault(Page.Storage(Sample.vault.id)))
        capture("45-storage")
    }

    @Test
    fun storagePriority() {
        home(vault(Page.Storage(Sample.vault.id)))
        click("Photos")
        capture("46-storage-priority")
    }

    @Test
    fun storagePriorityFromAFile() {
        home(vault(Page.Storage(Sample.vault.id, "Photos/cat.jpg")))
        capture("46-storage-priority-file")
    }

    @Test
    fun storageDrop() {
        home(vault(Page.Storage(Sample.vault.id)))
        click("Drop below…")
        capture("47-storage-drop")
    }

    @Test
    fun storageDropAtRisk() {
        home(vault(Page.Storage(Sample.vault.id)))
        click("Drop below…")
        click("Drop")
        capture("48-storage-drop-at-risk")
    }

    // Settings.

    @Test
    fun settings() {
        tall()
        home(FakeHome(Page.Settings))
        capture("60-settings")
    }

    @Test
    fun settingsProblems() {
        tall()
        home(
            FakeHome(Page.Settings),
            settings = FakeSettings().apply {
                limitsProblem.value = "the limit is at least 64 KiB/s"
                reportUrlProblem.value = "Enter the tracker's issues page, such as https://github.com/owner/repo/issues"
                errors.value = mapOf(Sample.camera.id to "permission denied")
            },
        )
        capture("61-settings-problems")
    }

    @Test
    fun settingsRemoveAfterBackup() {
        tall()
        home(FakeHome(Page.Settings), settings = FakeSettings().apply { folders.value = listOf(Sample.camera) })
        click("Remove after backup")
        capture("62-settings-remove-after-backup")
    }

    @Test
    fun settingsRebuild() {
        tall()
        val rebuilt = Sample.documents.copy(
            rebuild = org.tidefell.ffi.Rebuild(Sample.minutesAgo(300), "it was asked for", Sample.minutesAgo(240), emptyList()),
        )
        home(FakeHome(Page.Settings), settings = FakeSettings().apply { folders.value = listOf(rebuilt) })
        click("Rebuild this folder")
        capture("63-settings-rebuild")
    }

    // Status.

    @Test
    fun status() {
        tall()
        home(FakeHome(Page.Status))
        capture("70-status")
    }

    @Test
    fun statusChecks() {
        tall()
        home(FakeHome(Page.Status))
        click("Check this phone")
        capture("71-status-checks")
    }

    @Test
    fun statusDiagnostics() {
        home(FakeHome(Page.Status))
        click("Share diagnostics")
        capture("72-status-diagnostics")
    }

    @Test
    fun statusAlone() {
        home(
            FakeHome(Page.Status),
            status = FakeStatus().apply {
                peers.value = emptyList()
                error.value = "the node is not running"
            },
        )
        capture("73-status-no-devices")
    }

    // Adding a folder.

    private val storage = File("/storage/emulated/0")
    private val places = listOf(
        PhoneStorage.Place("Camera", File(storage, "DCIM/Camera")),
        PhoneStorage.Place("Internal storage", storage),
        PhoneStorage.Place("SD card", File("/storage/1A2B-3C4D")),
    )

    private fun addFolder(step: AddStep) = home(FakeHome(Page.AddFolder), add = FakeAddFolder(step))

    @Test
    fun addPlace() {
        addFolder(AddStep.Place)
        capture("80-add-place")
    }

    @Test
    fun addAccess() {
        addFolder(AddStep.Access)
        capture("81-add-access")
    }

    @Test
    fun addPickPlace() {
        addFolder(AddStep.Pick(null, places, emptyList()))
        capture("82-add-pick-place")
    }

    @Test
    fun addPickFolder() {
        val dir = File(storage, "Documents")
        addFolder(AddStep.Pick(dir, places, listOf("Letters", "Recipes", "Scans", "Tax").map { File(dir, it) }))
        capture("83-add-pick-folder")
    }

    @Test
    fun addChoose() {
        addFolder(AddStep.Choose(File(storage, "Documents")))
        capture("84-add-choose")
    }

    @Test
    fun addChooseRemoveAfterBackup() {
        addFolder(AddStep.Choose(File(storage, "DCIM/Camera")))
        click("Remove after backup")
        click("Add folder")
        capture("85-add-remove-after-backup")
    }

    @Test
    fun addWorking() {
        addFolder(AddStep.Working)
        capture("86-add-working")
    }

    @Test
    fun addDone() {
        addFolder(AddStep.Done(Sample.documents))
        capture("87-add-done")
    }

    @Test
    fun addFailed() {
        addFolder(AddStep.Failed("permission denied: /storage/emulated/0/Documents"))
        capture("88-add-failed")
    }

    // Pairing.

    private fun pairing(step: PairStep) = show {
        PairingScreen(
            onPaired = {},
            model = FakePairing(step),
            joinFolder = { offer, onDone, onCancel, modifier ->
                AddFolderScreen(offer, onDone, onCancel, modifier, model = FakeAddFolder(AddStep.Place))
            },
        )
    }

    @Test
    fun pairingStarting() {
        pairing(PairStep.Starting)
        capture("90-pairing-starting")
    }

    @Test
    fun pairingCode() {
        pairing(PairStep.ShowCode("tidefell1qy3x7k2m9w4p8r6t5v0n2c8z7h4j6f3d1s9a5g2l"))
        capture("91-pairing-code")
    }

    @Test
    fun pairingConfirm() {
        pairing(PairStep.Confirm("Laptop", "482913"))
        capture("92-pairing-confirm")
    }

    @Test
    fun pairingJoin() {
        pairing(PairStep.Join("Pixel 8", Sample.offers))
        capture("93-pairing-join")
    }

    @Test
    fun pairingJoinNothing() {
        pairing(PairStep.Join("Pixel 8", emptyList()))
        capture("93-pairing-join-nothing")
    }

    @Test
    fun pairingJoining() {
        pairing(PairStep.Join("Pixel 8", Sample.offers))
        compose.onAllNodesWithText("Join", useUnmergedTree = true)[0].performClick()
        capture("94-pairing-joining")
    }

    @Test
    fun pairingFailed() {
        pairing(PairStep.Failed("The code expired."))
        capture("95-pairing-failed")
    }

    private companion object {
        const val UNAVAILABLE = "/storage/1A2B-3C4D/Music is missing; is its drive mounted?"
        const val LOW_SPACE = "the folder's drive is low on space (waiting for space: 640.0 MiB free, and 1.0 GiB more " +
            "must stay free); this folder's recycle bin and earlier versions hold 1.2 GiB here"
    }
}
