package org.tidefell.android.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            TidefellTheme {
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
