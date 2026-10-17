package io.github.iacoposb.adodash.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A thin thumb while [state] can scroll, so that content below the fold is seen to be there.
 * Goes before verticalScroll(state), on the viewport; drawn [gap] past its right edge, in the
 * margin, so it never covers the content.
 */
fun Modifier.scrollbar(state: ScrollState, color: Color, gap: Dp = 4.dp): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
    val width = 4.dp.toPx()
    val thumb = (size.height * size.height / (size.height + max)).coerceAtLeast(32.dp.toPx())
    val top = (size.height - thumb) * state.value / max
    drawRoundRect(
        color,
        topLeft = Offset(size.width + gap.toPx(), top),
        size = Size(width, thumb),
        cornerRadius = CornerRadius(width / 2),
    )
}
