package org.tidefell.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import org.tidefell.android.R
import org.tidefell.design.TidefellColors
import org.tidefell.design.TidefellDarkColors
import org.tidefell.design.TidefellLightColors
import org.tidefell.design.TidefellRadius
import org.tidefell.design.TidefellText
import org.tidefell.design.TidefellTextStyle

/*
 * Tidefell's look. The colours, text styles and corner radii are the design tokens in
 * design/tokens, shared with the website and, later, the Apple apps; this file gives them to
 * Material. Every colour and text role is set, so none of Material's defaults shows through.
 */

/** Material's colour roles from Tidefell's colours. */
private fun TidefellColors.toMaterial(dark: Boolean): ColorScheme {
    // Material's own scheme only for the roles Tidefell does not use, such as primaryFixed.
    val material = if (dark) darkColorScheme() else lightColorScheme()
    return material.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
        inversePrimary = accentInverse,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondarySoft,
        onSecondaryContainer = onSecondarySoft,
        tertiary = attention,
        onTertiary = onAttention,
        tertiaryContainer = attentionSoft,
        onTertiaryContainer = onAttentionSoft,
        background = bg,
        onBackground = fg,
        surface = bg,
        onSurface = fg,
        surfaceVariant = border,
        onSurfaceVariant = fgMuted,
        surfaceTint = accent,
        inverseSurface = inverseBg,
        inverseOnSurface = inverseFg,
        error = danger,
        onError = onDanger,
        errorContainer = dangerSoft,
        onErrorContainer = onDangerSoft,
        outline = borderStrong,
        outlineVariant = border,
        scrim = scrim,
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainerLowest = surfaceLowest,
        surfaceContainerLow = surfaceLow,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHighest,
    )
}

private val LightColors = TidefellLightColors.toMaterial(dark = false)
private val DarkColors = TidefellDarkColors.toMaterial(dark = true)

/** Colours Material has no role for: a state that is fine, and one that wants attention. */
@Immutable
data class StatusColors(val ok: Color, val attention: Color)

private fun TidefellColors.status() = StatusColors(ok = ok, attention = attention)

private val LightStatus = TidefellLightColors.status()
private val DarkStatus = TidefellDarkColors.status()

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** The status colours of the current theme. */
val MaterialTheme.statusColors: StatusColors
    @Composable @ReadOnlyComposable
    get() = LocalStatusColors.current

/** Inter at weights the text styles and Material use, at one optical size. */
private fun inter(opticalSize: Float) = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(
            R.font.inter,
            weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight), FontVariation.Setting("opsz", opticalSize)),
        )
    },
)

/** Inter's optical sizes run from 14 to 32; Apple platforms choose it from the text's size too. */
private const val MIN_OPTICAL_SIZE = 14f
private const val MAX_OPTICAL_SIZE = 32f

private fun TidefellTextStyle.toCompose() = TextStyle(
    fontFamily = inter(size.value.coerceIn(MIN_OPTICAL_SIZE, MAX_OPTICAL_SIZE)),
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
)

private val Display = TidefellText.Display.toCompose()
private val Title = TidefellText.Title.toCompose()
private val Label = TidefellText.Label.toCompose()
private val LabelSmall = TidefellText.LabelSmall.toCompose()
private val Section = TidefellText.Section.toCompose()

/** Material's roles from Tidefell's text styles. */
private val TidefellTypography = Typography(
    displayLarge = Display,
    displayMedium = Display,
    displaySmall = Display,
    headlineLarge = Title,
    headlineMedium = Title,
    headlineSmall = Title,
    titleLarge = Title,
    titleMedium = TidefellText.Heading.toCompose(),
    titleSmall = Label,
    bodyLarge = TidefellText.Body.toCompose(),
    bodyMedium = TidefellText.BodySmall.toCompose(),
    bodySmall = TidefellText.Caption.toCompose(),
    labelLarge = Label,
    labelMedium = LabelSmall,
    labelSmall = LabelSmall,
)

/** Small section headings, such as "NETWORK" in settings. */
@Suppress("UnusedReceiverParameter") // Read beside the other styles, as MaterialTheme.typography.sectionLabel.
val Typography.sectionLabel: TextStyle
    get() = Section

private val TidefellShapes = Shapes(
    extraSmall = RoundedCornerShape(TidefellRadius.Xs),
    small = RoundedCornerShape(TidefellRadius.Sm),
    medium = RoundedCornerShape(TidefellRadius.Md),
    large = RoundedCornerShape(TidefellRadius.Lg),
    extraLarge = RoundedCornerShape(TidefellRadius.Xl),
)

/** The app's colours, type and shapes, light or dark as the phone is. */
@Composable
fun TidefellTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, typography = TidefellTypography, shapes = TidefellShapes, content = content)
    }
}
