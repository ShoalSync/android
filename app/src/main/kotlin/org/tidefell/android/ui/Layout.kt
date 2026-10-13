package org.tidefell.android.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.tidefell.design.TidefellSize

/**
 * Lays out a page's content: the whole of a phone's screen, and on a tablet or an open foldable no
 * wider than [TidefellSize.ContentMax], centred, so rows and fields keep a width that reads well.
 */
fun Modifier.pageWidth(): Modifier =
    fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = TidefellSize.ContentMax).fillMaxWidth()
