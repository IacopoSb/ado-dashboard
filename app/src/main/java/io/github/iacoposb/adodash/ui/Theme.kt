package io.github.iacoposb.adodash.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val AzureBlue = Color(0xFF0078D4)

@Composable
fun AdoTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = true, content: @Composable () -> Unit) {
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme(primary = Color(0xFF6CB8F6), secondary = Color(0xFF9CCBF0))
        else -> lightColorScheme(primary = AzureBlue, secondary = Color(0xFF005A9E))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** The colors Azure Boards uses for the default work item types. */
fun workItemColor(type: String): Color = when (type.lowercase()) {
    "bug" -> Color(0xFFCC293D)
    "task" -> Color(0xFFF2CB1D)
    "user story" -> Color(0xFF009CCC)
    "product backlog item" -> Color(0xFF009CCC)
    "feature" -> Color(0xFF773B93)
    "epic" -> Color(0xFFFF7B00)
    "issue", "impediment" -> Color(0xFFB4009E)
    else -> Color(0xFF8A8886)
}
