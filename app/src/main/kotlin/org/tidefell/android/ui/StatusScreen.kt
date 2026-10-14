package org.tidefell.android.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tidefell.android.Peer
import org.tidefell.android.Usage
import org.tidefell.android.folders.PhoneFolders
import org.tidefell.android.folders.PhoneStorage
import org.tidefell.design.TidefellIcons
import org.tidefell.design.TidefellSize
import org.tidefell.design.TidefellSpace
import org.tidefell.ffi.Check
import org.tidefell.ffi.CheckLevel
import org.tidefell.ffi.DiagnosticsBundle
import org.tidefell.ffi.PeerState
import org.tidefell.ffi.Transfer
import java.io.File

/**
 * This phone's status (ADR 0030): the devices it syncs with and how, what it has sent and
 * received, the doctor's checks, and a diagnostics bundle to share.
 */
class StatusViewModel(application: Application) : AndroidViewModel(application), StatusModel {
    private val app get() = getApplication<Application>()
    private val tick = MutableStateFlow(0)
    private val checksState = MutableStateFlow<List<Check>?>(null)
    private val bundleState = MutableStateFlow<DiagnosticsBundle?>(null)
    private val errorState = MutableStateFlow<String?>(null)
    private var ticking: Job? = null

    override val peers: StateFlow<List<PeerState>> = viewModelScope.nodeState(emptyList(), refresh = tick) {
        it.peerStates()
    }

    override val transfer: StateFlow<Transfer?> = viewModelScope.nodeState(null, refresh = tick) { it.transfer() }

    /** The doctor's checks, once asked for. */
    override val checks: StateFlow<List<Check>?> = checksState

    /** A diagnostics bundle written and waiting to be looked at and shared. */
    override val bundle: StateFlow<DiagnosticsBundle?> = bundleState

    override val error: StateFlow<String?> = errorState

    /** Reads the rates again every two seconds while the page is shown. */
    override fun showing(shown: Boolean) {
        ticking?.cancel()
        ticking = if (!shown) {
            null
        } else {
            viewModelScope.launch {
                while (isActive) {
                    delay(2_000)
                    tick.value++
                }
            }
        }
    }

    /** Runs the engine's checks and the phone's own; they read the disks and the network. */
    override fun check() {
        errorState.value = null
        viewModelScope.launch {
            checksState.value = withContext(Dispatchers.IO) {
                runCatching { Peer.start().doctor() + phoneChecks() }.getOrElse {
                    errorState.value = it.message ?: it.toString()
                    null
                }
            }
        }
    }

    private suspend fun phoneChecks(): List<Check> {
        val power = app.getSystemService(PowerManager::class.java)
        val activities = app.getSystemService(ActivityManager::class.java)
        return androidChecks(
            notificationsAllowed = NotificationManagerCompat.from(app).areNotificationsEnabled(),
            ignoringBatteryOptimizations = power.isIgnoringBatteryOptimizations(app.packageName),
            backgroundRestricted = activities.isBackgroundRestricted,
            hasPhoneFolders = Peer.start().folders().any { !PhoneFolders.inAppStorage(it) },
            allFilesAccess = PhoneStorage.hasAccess(),
        )
    }

    /** Writes a diagnostics bundle, with names hashed, for the user to look at first. */
    override fun buildBundle() {
        errorState.value = null
        viewModelScope.launch {
            bundleState.value = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(app.cacheDir, "diagnostics").path
                    Peer.start().diagnostics(dir, false, appInfo().toString())
                }.getOrElse {
                    errorState.value = it.message ?: it.toString()
                    null
                }
            }
        }
    }

    override fun bundleClosed() {
        bundleState.value = null
    }

    /** What the bundle says about the app: Android's version, the phone and the app's settings. */
    private fun appInfo(): JSONObject {
        val settings = Peer.settings
        return JSONObject()
            .put("android_api", Build.VERSION.SDK_INT)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put(
                "settings",
                JSONObject()
                    .put("custom_relay", settings.relay.isNotEmpty())
                    .put("local_discovery", settings.localDiscovery)
                    .put("sync_unmetered_only", settings.syncUnmeteredOnly)
                    .put("sync_charging_only", settings.syncChargingOnly)
                    .put("upload_kib", settings.uploadKib)
                    .put("download_kib", settings.downloadKib)
                    .put("limit_local", settings.limitLocal)
                    .put("report_url_set", settings.reportUrl.isNotEmpty()),
            )
            .put("usage", Usage.toJson(app, Peer.live()))
    }
}

/** The tallest the list of a diagnostics bundle's files gets; it scrolls beyond. */
private val DiagnosticsMaxHeight = 480.dp

@Composable
fun StatusPage(model: StatusModel = viewModel<StatusViewModel>()) {
    val context = LocalContext.current
    val peers by model.peers.collectAsStateWithLifecycle()
    val transfer by model.transfer.collectAsStateWithLifecycle()
    val checks by model.checks.collectAsStateWithLifecycle()
    val bundle by model.bundle.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        model.showing(true)
        onDispose { model.showing(false) }
    }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = TidefellSpace.Lg, vertical = TidefellSpace.Sm),
        verticalArrangement = Arrangement.spacedBy(TidefellSpace.Md),
    ) {
        SectionCard("Devices") {
            if (peers.isEmpty()) Text("This phone does not sync with any other device yet.")
            peers.forEach { PeerRow(it) }
        }
        SectionCard("Transfers") {
            transfer?.let { t ->
                Text("${size(context, t.sent)} sent, ${size(context, t.sendRate)}/s now")
                Text("${size(context, t.received)} received, ${size(context, t.receiveRate)}/s now")
            }
        }
        SectionCard("Checks") {
            checks?.forEach { CheckRow(it) }
            ActionButton(if (checks == null) "Check this phone" else "Check again", onClick = model::check)
        }
        SectionCard("Diagnostics") {
            Text(
                "A file describing this phone for a problem report: its settings, status, problems, recent logs and crashes. " +
                    "File and folder names are replaced by codes. You see what it holds before sharing it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionButton("Share diagnostics", onClick = model::buildBundle)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    bundle?.let { BundlePreview(context, it, model::bundleClosed) }
}

/** A device this phone syncs with, and how it is connected. */
@Composable
private fun PeerRow(peer: PeerState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Md)) {
        if (peer.connected) {
            IconTile(TidefellIcons.Devices, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        } else {
            IconTile(TidefellIcons.Devices, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            Text(peer.name ?: peer.device.take(8), style = MaterialTheme.typography.titleSmall)
            val seen = peer.lastSeenMs?.takeIf { !peer.connected }?.let { " · last seen ${relative(it)}" }.orEmpty()
            Text(
                peerText(peer) + seen,
                style = MaterialTheme.typography.bodySmall,
                color = if (peer.connected) MaterialTheme.statusColors.ok else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One of the doctor's checks: passed, a warning or a problem, with what to do. */
@Composable
private fun CheckRow(check: Check) {
    val (icon, color) = when (check.level) {
        CheckLevel.PASS -> TidefellIcons.Ok to MaterialTheme.statusColors.ok
        CheckLevel.WARNING -> TidefellIcons.Warning to MaterialTheme.statusColors.attention
        CheckLevel.FAILURE -> TidefellIcons.Error to MaterialTheme.colorScheme.error
    }
    Row(horizontalArrangement = Arrangement.spacedBy(TidefellSpace.Md)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = TidefellSpace.Xxs).size(TidefellSize.IconMd))
        Column {
            val level = when (check.level) {
                CheckLevel.PASS -> "OK"
                CheckLevel.WARNING -> "Warning"
                CheckLevel.FAILURE -> "Problem"
            }
            Text(
                "$level: ${check.detail}",
                color = if (check.level == CheckLevel.PASS) MaterialTheme.colorScheme.onSurface else color,
            )
            check.advice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What a diagnostics bundle holds, then the share sheet. */
@Composable
private fun BundlePreview(context: Context, bundle: DiagnosticsBundle, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share diagnostics?") },
        text = {
            Column(Modifier.heightIn(max = DiagnosticsMaxHeight).verticalScroll(rememberScrollState())) {
                bundle.entries.forEach { entry ->
                    Text(entry.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        entry.text.take(PREVIEW_CHARS) + if (entry.text.length > PREVIEW_CHARS) "\n…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = {
            ActionButton(
                "Share",
                onClick = {
                    share(context, File(bundle.path))
                    onDismiss()
                },
                kind = ButtonKind.Quiet,
            )
        },
        dismissButton = { ActionButton("Cancel", onClick = onDismiss, kind = ButtonKind.Quiet) },
    )
}

private fun share(context: Context, zip: File) {
    val uri = FileProvider.getUriForFile(context, "org.tidefell.android.files", zip)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Tidefell diagnostics")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, "Share diagnostics")) }
}

/** Characters of each file shown before sharing. */
private const val PREVIEW_CHARS = 4_000

private fun relative(ms: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

private fun size(context: Context, bytes: ULong): String = Formatter.formatShortFileSize(context, bytes.toLong())
