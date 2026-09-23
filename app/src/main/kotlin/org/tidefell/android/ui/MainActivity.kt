package org.tidefell.android.ui

import android.Manifest
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
import org.tidefell.android.Peer
import org.tidefell.android.SyncService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}
