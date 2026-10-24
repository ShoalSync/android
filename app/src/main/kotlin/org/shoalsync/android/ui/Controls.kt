package org.shoalsync.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.shoalsync.design.ShoalSize
import org.shoalsync.design.ShoalSpace

/*
 * Shoal's own controls, in place of Material's: drawn from the design tokens, so the Apple
 * apps can draw the same ones. Each says how it looks; what it does is the caller's.
 */

/**
 * A text field: its [label] above, a box to type in, and under it [help], or [error] in red
 * when the value is wrong. The box's outline is the accent colour while it is being typed in.
 */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    help: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val outline = when {
        error != null -> colors.error
        focused -> colors.primary
        else -> colors.outline
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ShoalSpace.Xs)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = colors.onSurface) }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().semantics { label?.let { contentDescription = it } },
            decorationBox = { input ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = ShoalSize.Control)
                        .clip(MaterialTheme.shapes.small)
                        .background(colors.surfaceContainerLowest)
                        .border(ShoalSize.Border, outline, MaterialTheme.shapes.small)
                        .padding(horizontal = ShoalSpace.Md, vertical = ShoalSpace.Sm),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    input()
                }
            },
        )
        when {
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
            help != null -> Text(help, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** A switch's track and thumb, on when [checked]. It only shows the state: the row around it is what is toggled. */
@Composable
fun Toggle(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val track by animateColorAsState(if (checked) colors.primary else colors.outline, label = "track")
    val offset by animateDpAsState(if (checked) ToggleWidth - ToggleHeight else 0.dp, label = "thumb")
    Box(
        modifier.size(ToggleWidth, ToggleHeight).clip(CircleShape).background(track).padding(ToggleInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(offset.roundToPx(), 0) }
                .size(ToggleHeight - ToggleInset * 2)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** A toggle's size: a little smaller than Apple's switch, so it sits in a row of body text. */
private val ToggleWidth = 44.dp
private val ToggleHeight = 26.dp
private val ToggleInset = 3.dp

/** How much a button says: the page's main action, another action, a quiet one, or one that deletes. */
enum class ButtonKind { Primary, Secondary, Quiet, Danger }

/**
 * A button: [label], with an [icon] before it, at least [ShoalSize.Control] high. It dims while
 * pressed, as on Apple platforms, rather than rippling, and fades when not [enabled].
 */
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (kind) {
        ButtonKind.Primary -> colors.primary to colors.onPrimary
        ButtonKind.Secondary -> colors.secondaryContainer to colors.onSecondaryContainer
        ButtonKind.Quiet -> Color.Transparent to colors.primary
        ButtonKind.Danger -> Color.Transparent to colors.error
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .heightIn(min = ShoalSize.Control)
            .alpha(if (!enabled) DISABLED_ALPHA else if (pressed) PRESSED_ALPHA else 1f)
            .clip(MaterialTheme.shapes.small)
            .background(container)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (container == Color.Transparent) ShoalSpace.Sm else ShoalSpace.Lg),
        horizontalArrangement = Arrangement.spacedBy(ShoalSpace.Sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(ShoalSize.IconMd)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

private const val PRESSED_ALPHA = 0.7f
private const val DISABLED_ALPHA = 0.38f

/**
 * How Shoal shows a press: a faint shade of [color] over what is pressed, as on Apple
 * platforms, in place of Material's ripple. The theme gives it to every clickable.
 */
data class PressIndication(val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, color)
}

private class PressNode(private val interactions: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            interactions.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                if (pressed != presses.isNotEmpty()) {
                    pressed = presses.isNotEmpty()
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) drawRect(color.copy(alpha = PRESSED_SHADE))
    }
}

/** An icon that is a button, such as in the top bar: [ShoalSize.Control] square. */
@Composable
fun IconAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier.size(ShoalSize.Control).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription)
    }
}

/**
 * A small rounded button with an [icon] in the accent colour, such as a way into a folder's
 * other pages. With [selected] it is a filter: filled while on.
 */
@Composable
fun Chip(label: String, onClick: () -> Unit, icon: ImageVector? = null, selected: Boolean? = null) {
    val colors = MaterialTheme.colorScheme
    val on = selected == true
    val shape = MaterialTheme.shapes.small
    val press = if (selected == null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier.toggleable(value = on, role = Role.Checkbox, onValueChange = { onClick() })
    }
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = ChipHeight)
            .clip(shape)
            .background(if (on) colors.secondaryContainer else Color.Transparent)
            .border(ShoalSize.Border, if (on) Color.Transparent else colors.outlineVariant, shape)
            .then(press)
            .padding(horizontal = ShoalSpace.Md),
        horizontalArrangement = Arrangement.spacedBy(ShoalSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = colors.primary, modifier = Modifier.size(ShoalSize.IconSm)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) colors.onSecondaryContainer else colors.onSurface)
    }
}

/** A radio button's ring, with a dot when [selected]. It only shows the state: the row around it is what is chosen. */
@Composable
fun RadioMark(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(RadioSize).border(ShoalSize.Border * 2, if (selected) colors.primary else colors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(RadioDot).clip(CircleShape).background(colors.primary))
    }
}

/** A chip's height: less than a button's, with the touch target made up around it. */
private val ChipHeight = 36.dp
private val RadioSize = 20.dp
private val RadioDot = 10.dp
private const val PRESSED_SHADE = 0.08f
