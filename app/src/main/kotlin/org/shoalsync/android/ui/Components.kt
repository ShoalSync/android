package org.shoalsync.android.ui

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
import org.shoalsync.design.ShoalIcons
import org.shoalsync.design.ShoalSize
import org.shoalsync.design.ShoalSpace
import org.shoalsync.ffi.Folder
import org.shoalsync.ffi.FolderState

/** Shoal's mark: the white wave on teal, as on the launcher icon. */
@Composable
fun BrandMark(size: Dp = ShoalSize.TileSm) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * TILE_CORNER)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ShoalIcons.Wave, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size))
    }
}

/** An icon on a tinted rounded square, leading a row or a card. */
@Composable
fun IconTile(
    icon: ImageVector,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = ShoalSize.TileMd,
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
        Column(Modifier.padding(ShoalSpace.Lg), verticalArrangement = Arrangement.spacedBy(ShoalSpace.Sm)) {
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
        Column(Modifier.padding(ShoalSpace.Lg), verticalArrangement = Arrangement.spacedBy(ShoalSpace.Sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ShoalSpace.Md)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ShoalSize.IconMd))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            action?.let { Box(Modifier.padding(start = ShoalSize.IconMd + ShoalSpace.Md)) { it() } }
        }
    }
}

/** An icon and a line of text in one colour, such as a folder's state. */
@Composable
fun StatusLine(icon: ImageVector, text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ShoalSpace.Sm), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = ShoalSpace.Xxs).size(ShoalSize.IconSm))
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
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = ShoalSpace.Md, vertical = ShoalSpace.Xs),
        )
    }
}

/** What a page shows when it has nothing to list. */
@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = ShoalSpace.Xxl, vertical = ShoalSpace.Xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ShoalSpace.Lg),
    ) {
        IconTile(
            icon,
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            size = ShoalSize.Hero,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Where an [ActionRow]'s label starts, to line a note up under it. */
val ActionRowTextStart = ShoalSpace.Xs + ShoalSize.IconLg + ShoalSpace.Lg

/** One choice in a list of actions, such as in a file's dialog. */
@Composable
fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = ShoalSpace.Xs, vertical = ShoalSpace.Md),
        horizontalArrangement = Arrangement.spacedBy(ShoalSpace.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(ShoalSize.IconLg))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** How a folder's state looks: its icon and colour. */
data class FolderLook(val icon: ImageVector, val color: Color)

/**
 * A folder in error or unavailable is red, one with problems, decisions to make or low on space
 * amber, one that is fine green.
 */
@Composable
fun folderLook(folder: Folder): FolderLook {
    val colors = MaterialTheme.colorScheme
    val status = MaterialTheme.statusColors
    val attention = folder.problems > 0UL || folder.conflicts > 0UL || folder.heldDeletes > 0UL || folder.lowSpace != null
    return when {
        folder.state == FolderState.ERROR -> FolderLook(ShoalIcons.Error, colors.error)
        attention -> FolderLook(ShoalIcons.Warning, status.attention)
        folder.state == FolderState.PAUSED -> FolderLook(ShoalIcons.Pause, colors.onSurfaceVariant)
        folder.state == FolderState.IDLE -> FolderLook(ShoalIcons.Ok, status.ok)
        else -> FolderLook(ShoalIcons.Sync, colors.primary)
    }
}

/** How round a tile's corners are, and how much of it its icon fills, as fractions of its size. */
private const val TILE_CORNER = 0.3f
private const val TILE_ICON = 0.55f
