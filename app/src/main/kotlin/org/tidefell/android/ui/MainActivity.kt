package org.tidefell.android.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.tidefell.android.Peer
import org.tidefell.android.SyncService
import org.tidefell.android.backup.RemoveOriginals

class MainActivity : ComponentActivity() {
    private var removing: List<RemoveOriginals.Eligible> = emptyList()
    private val deleteRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) RemoveOriginals.removed(this, removing)
        removing = emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
            .launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Peer.paired) SyncService.start(this)
        setContent {
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFF4FB3CF))
            } else {
                lightColorScheme(primary = Color(0xFF0D6E8A))
            }
            MaterialTheme(colorScheme = colors) {
                var paired by remember { mutableStateOf(Peer.paired) }
                if (paired) {
                    HomeScreen()
                } else {
                    PairingScreen(onPaired = {
                        SyncService.start(this)
                        paired = true
                    })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** From the notification: ask Android to delete the originals that are now eligible. */
    private fun handle(intent: Intent?) {
        if (intent?.action != RemoveOriginals.ACTION || !RemoveOriginals.supported || !Peer.paired) return
        val folder = Peer.settings.backupFolder
        if (folder.isEmpty()) return
        lifecycleScope.launch {
            val items = runCatching { RemoveOriginals.eligible(this@MainActivity, Peer.start(), folder) }
                .onFailure { Log.w(TAG, "could not list eligible originals", it) }
                .getOrDefault(emptyList())
            if (items.isEmpty()) return@launch
            removing = items
            deleteRequest.launch(IntentSenderRequest.Builder(RemoveOriginals.deleteRequest(this@MainActivity, items)).build())
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
