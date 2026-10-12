package org.tidefell.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.tidefell.ffi.Folder
import org.tidefell.ffi.FolderState

/** Tidefell's mark: the white wave on teal, as on the launcher icon. */
@Composable
fun BrandMark(size: Dp = 32.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * TILE_CORNER)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(TidefellIcons.Wave, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size))
    }
}

/** An icon on a tinted rounded square, leading a row or a card. */
@Composable
fun IconTile(
    icon: ImageVector,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 40.dp,
) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * TILE_CORNER)).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * TILE_ICON))
    }
}

/** A group of related content on a card, under a small heading. */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let {
                Text(it.uppercase(), style = MaterialTheme.typography.sectionLabel, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

/** A coloured notice across the page, such as syncing being paused, or in red an [error]. */
@Composable
fun Banner(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (error) colors.errorContainer else colors.tertiaryContainer,
        contentColor = if (error) colors.onErrorContainer else colors.onTertiaryContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            action?.let { Box(Modifier.padding(start = 20.dp)) { it() } }
        }
    }
}

/** An icon and a line of text in one colour, such as a folder's state. */
@Composable
fun StatusLine(icon: ImageVector, text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** A small rounded label, such as a folder's sync choice. */
@Composable
fun Pill(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** What a page shows when it has nothing to list. */
@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconTile(
            icon,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 72.dp,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** One choice in a list of actions, such as in a file's dialog. */
@Composable
fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** How a folder's state looks: its icon and colour. */
data class FolderLook(val icon: ImageVector, val color: Color)

/** A folder in error is red, one with problems or decisions to make amber, one that is fine green. */
@Composable
fun folderLook(folder: Folder): FolderLook {
    val colors = MaterialTheme.colorScheme
    val status = MaterialTheme.statusColors
    return when {
        folder.state == FolderState.ERROR -> FolderLook(TidefellIcons.Error, colors.error)
        folder.problems > 0UL || folder.conflicts > 0UL -> FolderLook(TidefellIcons.Warning, status.attention)
        folder.state == FolderState.PAUSED -> FolderLook(TidefellIcons.Pause, colors.onSurfaceVariant)
        folder.state == FolderState.IDLE -> FolderLook(TidefellIcons.Ok, status.ok)
        else -> FolderLook(TidefellIcons.Sync, colors.primary)
    }
}

/** A way into one of a folder's pages, or an action on it. */
@Composable
fun LinkChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

/** How round a tile's corners are, and how much of it its icon fills, as fractions of its size. */
private const val TILE_CORNER = 0.3f
private const val TILE_ICON = 0.55f
