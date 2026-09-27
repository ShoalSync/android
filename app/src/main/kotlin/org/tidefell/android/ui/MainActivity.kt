package org.tidefell.android.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import org.tidefell.android.Notifications
import org.tidefell.android.Peer
import org.tidefell.android.SyncService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
            .launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Peer.paired) SyncService.start(this)
        opened(intent)
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
        opened(intent)
    }

    /** A notification about a conflict opens that file's conflict screen. */
    private fun opened(intent: Intent?) {
        val folder = intent?.getStringExtra(Notifications.EXTRA_FOLDER) ?: return
        val path = intent.getStringExtra(Notifications.EXTRA_PATH) ?: return
        Navigation.requests.value = Page.Conflict(folder, path)
    }
}
