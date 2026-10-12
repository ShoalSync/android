package org.tidefell.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Tidefell's teal, lighter on a dark background. */
private val Teal = Color(0xFF0D6E8A)
private val LightTeal = Color(0xFF4FB3CF)

/** The app's colours, light or dark as the phone is. */
@Composable
fun TidefellTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) {
        darkColorScheme(primary = LightTeal)
    } else {
        lightColorScheme(primary = Teal)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
