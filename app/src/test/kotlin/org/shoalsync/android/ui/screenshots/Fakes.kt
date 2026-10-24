package org.shoalsync.android.ui.screenshots

import kotlinx.coroutines.flow.MutableStateFlow
import org.shoalsync.android.Peer
import org.shoalsync.android.folders.SyncChoice
import org.shoalsync.android.ui.AddFolderModel
import org.shoalsync.android.ui.AddFolderViewModel
import org.shoalsync.android.ui.FolderStorage
import org.shoalsync.android.ui.HomeModel
import org.shoalsync.android.ui.Page
import org.shoalsync.android.ui.PairingModel
import org.shoalsync.android.ui.PairingViewModel
import org.shoalsync.android.ui.SettingsModel
import org.shoalsync.android.ui.SettingsViewModel
import org.shoalsync.android.ui.StatusModel
import org.shoalsync.android.ui.storageRows
import org.shoalsync.ffi.Activity
import org.shoalsync.ffi.ChangeKind
import org.shoalsync.ffi.Check
import org.shoalsync.ffi.CheckLevel
import org.shoalsync.ffi.Crash
import org.shoalsync.ffi.DeviceStatus
import org.shoalsync.ffi.DiagnosticsBundle
import org.shoalsync.ffi.DiagnosticsEntry
import org.shoalsync.ffi.Folder
import org.shoalsync.ffi.FolderEncryption
import org.shoalsync.ffi.FolderOffer
import org.shoalsync.ffi.FolderState
import org.shoalsync.ffi.HeldWithdrawals
import org.shoalsync.ffi.Operation
import org.shoalsync.ffi.PathProblem
import org.shoalsync.ffi.PeerState
import org.shoalsync.ffi.Priorities
import org.shoalsync.ffi.PriorityEntry
import org.shoalsync.ffi.PriorityRule
import org.shoalsync.ffi.ProblemKind
import org.shoalsync.ffi.Rebuild
import org.shoalsync.ffi.Recycled
import org.shoalsync.ffi.StorageDropResult
import org.shoalsync.ffi.StorageLevel
import org.shoalsync.ffi.StoragePeerReport
import org.shoalsync.ffi.Transfer
import org.shoalsync.ffi.Version
import org.shoalsync.ffi.VersionKind
import java.io.File
import org.shoalsync.ffi.File as SyncedFile

/**
 * Fixed data for screenshots. Times are relative to when a test builds its data, moments before
 * it draws, so "3 minutes ago" stays the same however long the tests take.
 */
object Sample {
    // Read for each test as it builds its data, so a long run of tests does not age the times.
    private val now get() = System.currentTimeMillis()
    const val PHONE = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    const val LAPTOP = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
    const val NAS = "9a8b7c6d5e4f30211203f4e5d6c7b8a99a8b7c6d5e4f30211203f4e5d6c7b8a9"

    fun minutesAgo(minutes: Long) = now - minutes * 60_000
    fun daysAhead(days: Long) = now + days * 86_400_000

    // Shown times stay under a day: beyond that Android counts calendar days, which would
    // change the text with the time of day the tests run.
    fun hoursAhead(hours: Long) = now + hours * 3_600_000

    private val twoWay = SyncChoice.TWO_WAY.options
    private val backupOnly = SyncChoice.BACKUP_ONLY.options

    @Suppress("LongParameterList") // Sample data: every argument has a default.
    fun folder(
        id: String,
        path: String,
        sync: org.shoalsync.ffi.SyncOptions = twoWay,
        state: FolderState = FolderState.IDLE,
        partialBudget: ULong? = null,
        paused: Boolean = false,
        conflicts: ULong = 0u,
        pendingFiles: ULong = 0u,
        problems: ULong = 0u,
        error: String? = null,
        recycledFiles: ULong = 0u,
        onlyHere: ULong = 0u,
        rebuild: Rebuild? = null,
        unavailable: String? = null,
        heldDeletes: ULong = 0u,
        lowSpace: String? = null,
        encryption: FolderEncryption? = null,
    ) = Folder(
        id = id,
        path = path,
        partialBudget = partialBudget,
        members = listOf(PHONE),
        retentionDays = 30u,
        encryption = encryption,
        sync = sync,
        recycledFiles = recycledFiles,
        recycledBytes = recycledFiles * 2_400_000u,
        nextPurgeMs = if (recycledFiles > 0u) hoursAhead(20) else null,
        onlyHere = onlyHere,
        paused = paused,
        pinnedBytes = 0u,
        conflicts = conflicts,
        state = state,
        error = error,
        pendingFiles = pendingFiles,
        pendingBytes = pendingFiles * 3_100_000u,
        lastScanMs = minutesAgo(2),
        peers = emptyList(),
        problems = problems,
        rebuild = rebuild,
        unavailable = unavailable,
        heldDeletes = heldDeletes,
        lowSpace = lowSpace,
    )

    /** The camera folder, backed up only. */
    val camera get() = folder(
        id = "c".repeat(64),
        path = "/storage/emulated/0/DCIM/Camera",
        sync = backupOnly,
        state = FolderState.SYNCING,
        pendingFiles = 4u,
        onlyHere = 3u,
        recycledFiles = 2u,
    )

    /** Documents, synced both ways, with a conflict and a problem. */
    val documents get() = folder(
        id = "d".repeat(64),
        path = "/storage/emulated/0/Documents",
        conflicts = 1u,
        problems = 1u,
    )

    /** A folder in app storage with files on demand; its path is only known once [Peer] is set up. */
    fun shared() = folder(
        id = "e".repeat(64),
        path = File(Peer.foldersDir, "Family photos").path,
        sync = SyncChoice.FILES_ON_DEMAND.options,
        partialBudget = 2_147_483_648u,
    )

    val folders get() = listOf(camera, documents, shared())

    /** An encrypted folder the NAS keeps without reading it (ADR 0023). */
    val vault get() = folder(
        id = "f".repeat(64),
        path = File(Peer.foldersDir, "Vault").path,
        sync = SyncChoice.FILES_ON_DEMAND.options,
        partialBudget = 2_147_483_648u,
        encryption = FolderEncryption(storagePeers = listOf(NAS, LAPTOP), keyGeneration = 1u),
    )

    /** The vault's priorities: photos first, disc images never. */
    val priorities get() = Priorities(
        rules = listOf(PriorityRule(200u, "/Photos/"), PriorityRule(0u, "*.iso")),
        entries = listOf(
            PriorityEntry("Photos", true, 200u, 0u),
            PriorityEntry("Photos/2019", true, 200u, 0u),
            PriorityEntry("Photos/2019/beach.jpg", false, 200u, 0u),
            PriorityEntry("Photos/cat.jpg", false, 200u, 0u),
            PriorityEntry("backup.iso", false, 0u, 1u),
            PriorityEntry("notes.md", false, 128u, null),
        ),
    )

    val storage get() = FolderStorage(
        storageRows(priorities),
        listOf(
            StoragePeerReport(
                device = NAS,
                connected = true,
                quotaBytes = 8_000_000_000u,
                heldBytes = 6_200_000_000u,
                roomBytes = 0u,
                levels = listOf(
                    StorageLevel(200u, 2u, 4_100_000_000u, 4_100_000_000u),
                    StorageLevel(128u, 1u, 3_000_000_000u, 2_100_000_000u),
                    StorageLevel(0u, 1u, 4_700_000_000u, 0u),
                ),
                error = null,
            ),
            StoragePeerReport(LAPTOP, false, null, null, null, emptyList(), null),
        ),
    )

    @Suppress("LongParameterList") // Sample data: every argument has a default.
    fun file(
        path: String,
        onDisk: Boolean = true,
        conflicts: UInt = 0u,
        onlyHere: Boolean = false,
        pinned: Boolean = false,
        author: String = PHONE,
        authorName: String = "Pixel 8",
    ) = SyncedFile(
        entry = path.hashCode().toString(16).padStart(32, '0'),
        path = path,
        size = 2_400_000u,
        modifiedMs = minutesAgo(90),
        onDisk = onDisk,
        conflicts = conflicts,
        confirmedCopies = if (onlyHere) 0u else 1u,
        onlyHere = onlyHere,
        pinned = pinned,
        author = author,
        authorName = authorName,
    )

    val files get() = listOf(
        file("Budget 2026.ods", conflicts = 1u, author = LAPTOP, authorName = "Laptop"),
        file("Letters/To the council.odt"),
        file("Notes.md", onlyHere = true),
        file("Recipes/Bread.pdf", onDisk = false, author = NAS, authorName = "NAS"),
        file("Tickets.pdf", pinned = true),
        file("名前.txt", onDisk = false, author = LAPTOP, authorName = "Laptop"),
    )

    val problems get() = listOf(
        PathProblem(
            path = "Quarterly: Q3?.xlsx",
            operation = Operation.WRITE,
            kind = ProblemKind.NAME_NOT_ALLOWED,
            message = "The file system here does not allow : and ? in names",
            sinceMs = minutesAgo(30),
            attempts = 3u,
        ),
    )

    /** A scan's held deletes (ADR 0037). */
    val heldDeletes =
        listOf("Scans/2026-01-04.pdf", "Scans/2026-02-11.pdf", "Scans/2026-03-19.pdf", "Scans/Receipt.jpg")

    val activity get() = listOf(
        Activity(minutesAgo(3), ChangeKind.ADDED, "Tickets.pdf", null, PHONE, "Pixel 8", true),
        Activity(minutesAgo(25), ChangeKind.CHANGED, "Budget 2026.ods", null, LAPTOP, "Laptop", false),
        Activity(minutesAgo(70), ChangeKind.RENAMED, "Letters/To the council.odt", "Letter.odt", LAPTOP, "Laptop", false),
        Activity(minutesAgo(200), ChangeKind.DELETED, "Old notes.md", null, PHONE, "Pixel 8", true),
        Activity(minutesAgo(300), ChangeKind.CONFLICT, "Budget 2026.ods", null, LAPTOP, "Laptop", false),
        Activity(minutesAgo(1000), ChangeKind.RESTORED, "Recipes/Bread.pdf", null, NAS, "NAS", false),
    )

    val recycled get() = listOf(
        Recycled("1".repeat(32), "Old notes.md", 12_000u, minutesAgo(200), daysAhead(29), false, false),
        Recycled("2".repeat(32), "Scans/Receipt.jpg", 2_100_000u, minutesAgo(1300), daysAhead(27), true, false),
    )

    val history get() = listOf(
        Version("current", VersionKind.CURRENT, 48_000u, minutesAgo(25), LAPTOP, "Laptop", null, null),
        Version("previous:2", VersionKind.PREVIOUS, 46_500u, minutesAgo(400), null, null, minutesAgo(25), daysAhead(29)),
        Version("previous:1", VersionKind.PREVIOUS, 41_000u, minutesAgo(1300), null, null, minutesAgo(400), daysAhead(29)),
    )

    val conflict get() = listOf(
        Version("current", VersionKind.CURRENT, 48_000u, minutesAgo(25), LAPTOP, "Laptop", null, null),
        Version("conflict:1", VersionKind.CONFLICT, 47_200u, minutesAgo(40), PHONE, "Pixel 8", null, null),
    )

    val status get() = DeviceStatus(PHONE, "f".repeat(64), "Pixel 8", false, false)

    val crash get() = Crash("crash-1", "crash-1.txt", minutesAgo(60), "the scan task panicked: index out of bounds", false)

    val peers get() = listOf(
        PeerState(LAPTOP, "Laptop", true, false, 12u, null, null, 40),
        PeerState(NAS, "NAS", true, true, 85u, null, null, -1_200),
        PeerState("7".repeat(64), "Old phone", false, null, null, minutesAgo(900), "timed out", null),
    )

    val transfer get() = Transfer(1_288_490_188u, 5_368_709_120u, 0u, 2_400_000u)

    val checks get() = listOf(
        Check("clock", CheckLevel.PASS, "Laptop's clock agrees with this device's", null),
        Check("space", CheckLevel.WARNING, "Only 800 MB free on the phone", "Free some space; files cannot be written when it runs out."),
        Check("notifications", CheckLevel.PASS, "Notifications are allowed", null),
        Check(
            "battery",
            CheckLevel.WARNING,
            "Battery optimisation may delay syncing",
            "Set Shoal's battery use to unrestricted for syncing to keep up while the phone is idle.",
        ),
        Check(
            "storage",
            CheckLevel.FAILURE,
            "All files access is off, so folders on the phone cannot sync",
            "Allow All files access for Shoal in Settings."
        ),
    )

    val bundle get() = DiagnosticsBundle(
        "/data/diagnostics.zip",
        listOf(
            DiagnosticsEntry("status.json", "{\n  \"device\": \"a1b2c3d4\",\n  \"folders\": 3,\n  \"paused\": false\n}"),
            DiagnosticsEntry("logs/shoal.log", "2026-10-11T20:00:00Z INFO engine started\n2026-10-11T20:00:01Z INFO folder available"),
        ),
    )

    val offers get() = listOf(
        FolderOffer("d".repeat(64), "Documents", listOf(PHONE), 30u, null),
        FolderOffer("e".repeat(64), "Family photos", listOf(PHONE), 30u, null),
    )
}

class FakeHome(page: Page = Page.Folders) : HomeModel {
    override val started = MutableStateFlow(true)
    override val page = MutableStateFlow(page)
    override val message = MutableStateFlow<String?>(null)
    override val folders = MutableStateFlow(Sample.folders)
    override val files = MutableStateFlow(Sample.files)
    override val problems = MutableStateFlow(Sample.problems)
    override val heldDeletes = MutableStateFlow(Sample.heldDeletes)
    override val heldWithdrawals = MutableStateFlow<List<HeldWithdrawals>>(emptyList())
    override val activity = MutableStateFlow(Sample.activity)
    override val recycleBin = MutableStateFlow(Sample.recycled)
    override val versions = MutableStateFlow(Sample.history)
    override val status = MutableStateFlow<DeviceStatus?>(Sample.status)
    override val crashed = MutableStateFlow<String?>(null)
    override val crashes = MutableStateFlow<List<Crash>>(emptyList())

    override fun go(page: Page) {
        this.page.value = page
    }

    override fun back(): Boolean {
        val parent = page.value.parent ?: return false
        page.value = parent
        return true
    }

    override fun show(message: String) {
        this.message.value = message
    }

    override fun messageShown() {
        message.value = null
    }

    override fun cleanUpCandidates(): List<SyncedFile> = files.value.filter { it.onDisk && !it.onlyHere }
    override fun cleanUp(folder: String, paths: List<String>) = Unit
    override fun removeHere(folder: String, path: String) = Unit
    override fun download(folder: String, path: String) = Unit
    override fun deleteEverywhere(folder: String, path: String) = Unit
    override fun restore(folder: String, item: Recycled) = Unit
    override fun discard(folder: String, item: Recycled) = Unit
    override fun emptyRecycleBin(folder: String) = Unit
    override fun pause(folder: String?) = Unit
    override fun resume(folder: String?) = Unit
    override fun rename(folder: String, from: String, to: String) = Unit
    override fun pin(folder: String, path: String) = Unit
    override fun unpin(folder: String, path: String) = Unit
    override fun restoreVersion(folder: String, path: String, version: Version) = Unit
    override fun resolve(folder: String, path: String, keep: String) = Unit
    override fun confirmDeletes(folder: String) = Unit
    override fun restoreDeleted(folder: String) = Unit
    override fun decideWithdrawals(folder: String, device: String, withdraw: Boolean) = Unit
    override fun retryProblems(folder: String) = Unit
    override suspend fun openVersion(folder: String, path: String, version: Version): String = ""
    override fun dismissCrashes() = Unit
    override fun restart() = Unit
    override val storage = MutableStateFlow<FolderStorage?>(Sample.storage)
    override fun setPriority(folder: String, path: String, directory: Boolean, priority: Int?) = Unit
    override suspend fun storageDrop(folder: String, device: String, below: Int, force: Boolean) =
        StorageDropResult(done = false, droppedChunks = 0u, droppedBytes = 0u, atRisk = listOf("Photos/2019/beach.jpg"))
}

class FakeSettings : SettingsModel {
    override val background = MutableStateFlow(SettingsViewModel.Background(unmeteredOnly = true, chargingOnly = false))
    override val localDiscovery = MutableStateFlow(true)
    override val limits =
        MutableStateFlow(SettingsViewModel.Limits(uploadKib = 512, downloadKib = 0, limitLocal = false))
    override val limitsProblem = MutableStateFlow<String?>(null)
    override val folders = MutableStateFlow(Sample.folders)
    override val reportUrl = MutableStateFlow("")
    override val reportUrlProblem = MutableStateFlow<String?>(null)
    override val errors = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun setBackground(change: (SettingsViewModel.Background) -> SettingsViewModel.Background) {
        background.value = change(background.value)
    }

    override fun setLocalDiscovery(on: Boolean) {
        localDiscovery.value = on
    }

    override fun setLimits(limits: SettingsViewModel.Limits) {
        this.limits.value = limits
    }

    override fun setReportUrl(url: String) {
        reportUrl.value = url
    }

    override fun syncNow() = Unit
    override fun setChoice(folder: Folder, choice: SyncChoice) = Unit
    override fun setRetention(folder: String, days: UInt) = Unit
    override fun rebuild(folder: String) = Unit
}

class FakeStatus : StatusModel {
    override val peers = MutableStateFlow(Sample.peers)
    override val transfer = MutableStateFlow<Transfer?>(Sample.transfer)
    override val checks = MutableStateFlow<List<Check>?>(null)
    override val bundle = MutableStateFlow<DiagnosticsBundle?>(null)
    override val error = MutableStateFlow<String?>(null)

    override fun showing(shown: Boolean) = Unit

    override fun check() {
        checks.value = Sample.checks
    }

    override fun buildBundle() {
        bundle.value = Sample.bundle
    }

    override fun bundleClosed() {
        bundle.value = null
    }
}

class FakeAddFolder(step: AddFolderViewModel.Step) : AddFolderModel {
    override val step = MutableStateFlow(step)

    override fun start(offer: FolderOffer?, again: Boolean) = Unit
    override fun reset() = Unit
    override fun useAppStorage() = Unit
    override fun usePhoneFolder() = Unit
    override fun accessChanged() = Unit
    override fun open(dir: File?) = Unit
    override fun up(): Boolean = false
    override fun choose(dir: File) {
        step.value = AddFolderViewModel.Step.Choose(dir)
    }

    override fun add(dir: File, choice: SyncChoice) = Unit
}

class FakePairing(step: PairingViewModel.Step) : PairingModel {
    override val step = MutableStateFlow(step)

    override fun retry() = Unit
}
