package io.github.iacoposb.adodash.ui.components

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.ui.AdoTheme

/** Title line widths of the placeholder rows, as fractions of the row, so they don't look cloned. */
private val TITLE_WIDTHS = listOf(0.62f, 0.52f, 0.68f, 0.47f, 0.57f)

/**
 * Placeholder rows while the first data loads: a type bar and two rounded lines per row,
 * gently pulsing unless the system turned animations off.
 */
@Composable
fun SkeletonRows(modifier: Modifier = Modifier, rows: Int = TITLE_WIDTHS.size) {
    val color = AdoTheme.colors.surfaceAlt
    val context = LocalContext.current
    val animate = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val alpha = if (animate) {
        val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 1f, targetValue = 0.5f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "skeleton-alpha",
        )
        pulse
    } else {
        1f
    }
    Column(
        modifier.fillMaxWidth().alpha(alpha).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        repeat(rows) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Bar(TITLE_WIDTHS[i % TITLE_WIDTHS.size], 12)
                    Bar(0.35f, 9)
                }
            }
        }
    }
}

@Composable
private fun Bar(width: Float, height: Int) = Box(
    Modifier.fillMaxWidth(width).height(height.dp).clip(RoundedCornerShape(height.dp / 2))
        .background(AdoTheme.colors.surfaceAlt),
)
