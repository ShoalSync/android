package org.shoalsync.android.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.shoalsync.design.ShoalSize

/**
 * Lays out a page's content: the whole of a phone's screen, and on a tablet or an open foldable no
 * wider than [ShoalSize.ContentMax], centred, so rows and fields keep a width that reads well.
 */
fun Modifier.pageWidth(): Modifier =
    fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ShoalSize.ContentMax).fillMaxWidth()
