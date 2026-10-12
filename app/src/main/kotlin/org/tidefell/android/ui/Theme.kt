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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Tidefell's look: the website's teal and cool slate greys (tidefell/site), not Material's
 * default purple. Every colour role is set, so no default shows through anywhere.
 */

/* The website's colours, and tones made from them for the roles it has no colour for. */
private val LightPrimary = Color(0xFF0D6E8A)
private val White = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFC4E8F3)
private val LightOnPrimaryContainer = Color(0xFF00222D)
private val LightInversePrimary = Color(0xFF4FB3CF)
private val LightSecondary = Color(0xFF4C6370)
private val LightSecondaryContainer = Color(0xFFD6E5EE)
private val LightOnSecondaryContainer = Color(0xFF0A1E28)
private val LightTertiary = Color(0xFF8A5A12)
private val LightTertiaryContainer = Color(0xFFFCE3BF)
private val LightOnTertiaryContainer = Color(0xFF2D1A00)
private val LightBackground = Color(0xFFFBFCFD)
private val LightOnBackground = Color(0xFF1C2530)
private val LightSurfaceVariant = Color(0xFFDDE3EA)
private val LightOnSurfaceVariant = Color(0xFF5B6776)
private val LightInverseSurface = Color(0xFF2B3540)
private val LightInverseOnSurface = Color(0xFFEDF1F5)
private val LightError = Color(0xFFB3261E)
private val LightErrorContainer = Color(0xFFFADEDB)
private val LightOnErrorContainer = Color(0xFF410E0B)
private val LightOutline = Color(0xFF8994A1)
private val Black = Color(0xFF000000)
private val LightSurfaceDim = Color(0xFFD8DEE4)
private val LightSurfaceContainerLow = Color(0xFFF4F7F9)
private val LightSurfaceContainer = Color(0xFFEEF2F6)
private val LightSurfaceContainerHigh = Color(0xFFE7ECF1)
private val LightSurfaceContainerHighest = Color(0xFFE1E7ED)
private val DarkOnPrimary = Color(0xFF00313F)
private val DarkPrimaryContainer = Color(0xFF0B4F63)
private val DarkSecondary = Color(0xFFB3C8D5)
private val DarkOnSecondary = Color(0xFF1E333F)
private val DarkSecondaryContainer = Color(0xFF344A57)
private val DarkTertiary = Color(0xFFF2BE74)
private val DarkOnTertiary = Color(0xFF482A00)
private val DarkTertiaryContainer = Color(0xFF66420A)
private val DarkBackground = Color(0xFF11171D)
private val DarkOnBackground = Color(0xFFE3E9EF)
private val DarkSurfaceVariant = Color(0xFF2A343F)
private val DarkOnSurfaceVariant = Color(0xFF9AA7B5)
private val DarkError = Color(0xFFF2B8B5)
private val DarkOnError = Color(0xFF601410)
private val DarkErrorContainer = Color(0xFF8C1D18)
private val DarkOnErrorContainer = Color(0xFFF9DEDC)
private val DarkOutline = Color(0xFF6B7785)
private val DarkSurfaceBright = Color(0xFF2F3943)
private val DarkSurfaceContainerLowest = Color(0xFF0B1116)
private val DarkSurfaceContainerLow = Color(0xFF161E25)
private val DarkSurfaceContainer = Color(0xFF1B242D)
private val DarkSurfaceContainerHigh = Color(0xFF232D37)
private val LightOk = Color(0xFF1E7B53)
private val DarkOk = Color(0xFF7DD3A8)

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = LightInversePrimary,
    secondary = LightSecondary,
    onSecondary = White,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = White,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightBackground,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    error = LightError,
    onError = White,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    outline = LightOutline,
    outlineVariant = LightSurfaceVariant,
    scrim = Black,
    surfaceBright = LightBackground,
    surfaceDim = LightSurfaceDim,
    surfaceContainerLowest = White,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)

private val DarkColors = darkColorScheme(
    primary = LightInversePrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = LightPrimaryContainer,
    inversePrimary = LightPrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = LightSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = LightTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkBackground,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = LightInversePrimary,
    inverseSurface = DarkOnBackground,
    inverseOnSurface = LightOnBackground,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    outline = DarkOutline,
    outlineVariant = DarkSurfaceVariant,
    scrim = Black,
    surfaceBright = DarkSurfaceBright,
    surfaceDim = DarkBackground,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = LightInverseSurface,
)

/** Colours Material has no role for: a state that is fine, and one that wants attention. */
@Immutable
data class StatusColors(val ok: Color, val attention: Color)

private val LightStatus = StatusColors(ok = LightOk, attention = LightTertiary)
private val DarkStatus = StatusColors(ok = DarkOk, attention = DarkTertiary)

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** The status colours of the current theme. */
val MaterialTheme.statusColors: StatusColors
    @Composable @ReadOnlyComposable
    get() = LocalStatusColors.current

/** Material's scale, with firmer titles and a little more room in body text. */
private val TidefellTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(lineHeight = 24.sp, letterSpacing = 0.15.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 20.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Small section headings, such as "NETWORK" in settings. */
val Typography.sectionLabel: TextStyle
    get() = labelMedium.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)

private val TidefellShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** The app's colours, type and shapes, light or dark as the phone is. */
@Composable
fun TidefellTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, typography = TidefellTypography, shapes = TidefellShapes, content = content)
    }
}
