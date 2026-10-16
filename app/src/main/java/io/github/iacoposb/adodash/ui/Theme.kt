package io.github.iacoposb.adodash.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun style(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal, letterSpacing: Float = 0f) = TextStyle(
    fontFamily = FontFamily.Default, // Roboto on Android
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

/** The text styles of the design (docs/design/text-styles.json) in the Material 3 roles. */
val AdoTypography = Typography(
    headlineMedium = style(28, 32, FontWeight.Medium), // Numero/Grande
    headlineSmall = style(20, 28, FontWeight.Medium), // Titolo/Dettaglio
    titleLarge = style(22, 28, FontWeight.Medium), // Titolo/App
    titleMedium = style(16, 24, FontWeight.Medium), // Titolo/Pannello
    titleSmall = style(14, 20, FontWeight.Medium), // Corpo/Medio forte
    bodyLarge = style(16, 22), // Corpo/Grande
    bodyMedium = style(14, 20), // Corpo/Medio
    bodySmall = style(12, 16), // Corpo/Piccolo
    labelLarge = style(14, 20, FontWeight.Medium), // Etichetta/Bottone
    labelMedium = style(12, 16, FontWeight.Medium), // Etichetta/Chip
    // Etichetta/Sezione: 0.8 px tracking, upper case applied where used
    labelSmall = style(11, 16, FontWeight.Medium, letterSpacing = 0.8f),
)

/** "Codice": identifiers, hashes, URIs. */
val CodeStyle = style(13, 20).copy(fontFamily = FontFamily.Monospace)

/**
 * The design palette is the app's identity, so [dynamic] (Android 12+ wallpaper colors for the
 * Material slots) is off by default; the extra tokens in [AdoColors] never change.
 */
@Composable
fun AdoTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = false, content: @Composable () -> Unit) {
    val ado = if (dark) DarkAdoColors else LightAdoColors
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        else -> ado.toColorScheme(dark)
    }
    CompositionLocalProvider(LocalAdoColors provides ado) {
        MaterialTheme(colorScheme = scheme, typography = AdoTypography, content = content)
    }
}

object AdoTheme {
    val colors: AdoColors
        @Composable @ReadOnlyComposable get() = LocalAdoColors.current
}

/** The colors Azure Boards uses for the work item types. */
@Composable
@ReadOnlyComposable
fun workItemColor(type: String): Color {
    val c = AdoTheme.colors
    return when (type.lowercase()) {
        "bug" -> c.wiBug
        "task" -> c.wiTask
        "user story", "product backlog item", "requirement" -> c.wiStory
        "feature" -> c.wiFeature
        "epic" -> c.wiEpic
        "documentation", "documentazione", "doc" -> c.wiDoc
        "issue", "impediment" -> Color(0xFFB4009E)
        else -> c.wiChore
    }
}
