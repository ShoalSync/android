package org.shoalsync.android.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalRippleConfiguration
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
import org.shoalsync.android.R
import org.shoalsync.design.ShoalColors
import org.shoalsync.design.ShoalDarkColors
import org.shoalsync.design.ShoalLightColors
import org.shoalsync.design.ShoalRadius
import org.shoalsync.design.ShoalText
import org.shoalsync.design.ShoalTextStyle

/*
 * Shoal's look. The colours, text styles and corner radii are the design tokens in
 * design/tokens, shared with the website and, later, the Apple apps; this file gives them to
 * Material. Every colour and text role is set, so none of Material's defaults shows through.
 */

/** Material's colour roles from Shoal's colours. */
private fun ShoalColors.toMaterial(dark: Boolean): ColorScheme {
    // Material's own scheme only for the roles Shoal does not use, such as primaryFixed.
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

private val LightColors = ShoalLightColors.toMaterial(dark = false)
private val DarkColors = ShoalDarkColors.toMaterial(dark = true)

/** Colours Material has no role for: a state that is fine, and one that wants attention. */
@Immutable
data class StatusColors(val ok: Color, val attention: Color)

private fun ShoalColors.status() = StatusColors(ok = ok, attention = attention)

private val LightStatus = ShoalLightColors.status()
private val DarkStatus = ShoalDarkColors.status()

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

private fun ShoalTextStyle.toCompose() = TextStyle(
    fontFamily = inter(size.value.coerceIn(MIN_OPTICAL_SIZE, MAX_OPTICAL_SIZE)),
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
)

private val Display = ShoalText.Display.toCompose()
private val Title = ShoalText.Title.toCompose()
private val Label = ShoalText.Label.toCompose()
private val LabelSmall = ShoalText.LabelSmall.toCompose()
private val Section = ShoalText.Section.toCompose()

/** Material's roles from Shoal's text styles. */
private val ShoalTypography = Typography(
    displayLarge = Display,
    displayMedium = Display,
    displaySmall = Display,
    headlineLarge = Title,
    headlineMedium = Title,
    headlineSmall = Title,
    titleLarge = Title,
    titleMedium = ShoalText.Heading.toCompose(),
    titleSmall = Label,
    bodyLarge = ShoalText.Body.toCompose(),
    bodyMedium = ShoalText.BodySmall.toCompose(),
    bodySmall = ShoalText.Caption.toCompose(),
    labelLarge = Label,
    labelMedium = LabelSmall,
    labelSmall = LabelSmall,
)

/** Small section headings, such as "NETWORK" in settings. */
@Suppress("UnusedReceiverParameter") // Read beside the other styles, as MaterialTheme.typography.sectionLabel.
val Typography.sectionLabel: TextStyle
    get() = Section

private val ShoalShapes = Shapes(
    extraSmall = RoundedCornerShape(ShoalRadius.Xs),
    small = RoundedCornerShape(ShoalRadius.Sm),
    medium = RoundedCornerShape(ShoalRadius.Md),
    large = RoundedCornerShape(ShoalRadius.Lg),
    extraLarge = RoundedCornerShape(ShoalRadius.Xl),
)

/** The app's colours, type and shapes, light or dark as the phone is. */
@Composable
fun ShoalTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, typography = ShoalTypography, shapes = ShoalShapes) {
            CompositionLocalProvider(
                LocalIndication provides PressIndication(colors.onSurface),
                LocalRippleConfiguration provides null,
                content = content,
            )
        }
    }
}
