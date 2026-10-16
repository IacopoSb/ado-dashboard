package io.github.iacoposb.adodash.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The design tokens (docs/design/tokens.css), including those Material 3 has no slot for. */
@Immutable
data class AdoColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val outline: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val success: Color,
    val successContainer: Color,
    val suggest: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val accent: Color,
    val wiBug: Color,
    val wiTask: Color,
    val wiStory: Color,
    val wiFeature: Color,
    val wiEpic: Color,
    val wiChore: Color,
    val wiDoc: Color,
    val diffAdd: Color,
    val diffDel: Color,
)

val LightAdoColors = AdoColors(
    bg = Color(0xFFF4F6F9),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEEF1F5),
    outline = Color(0xFFDCE1E8),
    text = Color(0xFF1A1C20),
    textMuted = Color(0xFF5C6370),
    textFaint = Color(0xFF8A919C),
    primary = Color(0xFF0067B8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCEBFA),
    onPrimaryContainer = Color(0xFF00365F),
    success = Color(0xFF107C10),
    successContainer = Color(0xFFDFF3DC),
    suggest = Color(0xFF4F8A00),
    warning = Color(0xFFA15C00),
    warningContainer = Color(0xFFFFF0D4),
    danger = Color(0xFFC42B30),
    dangerContainer = Color(0xFFFCE4E5),
    accent = Color(0xFF773B93),
    wiBug = Color(0xFFCC293D),
    wiTask = Color(0xFFF2CB1D),
    wiStory = Color(0xFF009CCC),
    wiFeature = Color(0xFF773B93),
    wiEpic = Color(0xFFFF7B00),
    wiChore = Color(0xFF8A8886),
    wiDoc = Color(0xFF339947),
    diffAdd = Color(0xFFE6F6E4),
    diffDel = Color(0xFFFCE8E8),
)

val DarkAdoColors = AdoColors(
    bg = Color(0xFF101216),
    surface = Color(0xFF1A1D22),
    surfaceAlt = Color(0xFF242830),
    outline = Color(0xFF353A43),
    text = Color(0xFFE4E6EB),
    textMuted = Color(0xFFA3AAB5),
    textFaint = Color(0xFF727A86),
    primary = Color(0xFF6CB8F6),
    onPrimary = Color(0xFF00284A),
    primaryContainer = Color(0xFF123A5C),
    onPrimaryContainer = Color(0xFFCFE6FB),
    success = Color(0xFF6CCB5F),
    successContainer = Color(0xFF1C3A1B),
    suggest = Color(0xFFA3D65E),
    warning = Color(0xFFF2B54A),
    warningContainer = Color(0xFF3D2D10),
    danger = Color(0xFFFF8A8C),
    dangerContainer = Color(0xFF4A1C1E),
    accent = Color(0xFFCFA3E6),
    wiBug = Color(0xFFCC293D),
    wiTask = Color(0xFFF2CB1D),
    wiStory = Color(0xFF009CCC),
    wiFeature = Color(0xFF9A5BB8),
    wiEpic = Color(0xFFFF7B00),
    wiChore = Color(0xFF8A8886),
    wiDoc = Color(0xFF339947),
    diffAdd = Color(0xFF17301A),
    diffDel = Color(0xFF3A1A1C),
)

val LocalAdoColors = staticCompositionLocalOf { LightAdoColors }

/** The Material 3 slots filled from the tokens, so stock components match the design. */
fun AdoColors.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = if (dark) LightAdoColors.primary else DarkAdoColors.primary,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = primaryContainer,
        onSecondaryContainer = onPrimaryContainer,
        tertiary = accent,
        onTertiary = onPrimary,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceAlt,
        onSurfaceVariant = textMuted,
        surfaceTint = primary,
        surfaceBright = surface,
        surfaceDim = bg,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceAlt,
        surfaceContainerHighest = surfaceAlt,
        outline = textFaint,
        outlineVariant = outline,
        error = danger,
        onError = surface,
        errorContainer = dangerContainer,
        onErrorContainer = danger,
    )
}
