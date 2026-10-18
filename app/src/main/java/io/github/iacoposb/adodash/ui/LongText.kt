package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import kotlin.math.roundToInt

/** When a comment is shown collapsed, and how much of it. Pure. */
object LongText {
    /** Longer than this many lines of body text: collapsed. */
    const val MAX_LINES = 12

    /** What a collapsed comment shows. */
    const val COLLAPSED_LINES = 8

    fun collapses(heightPx: Int, lineHeightPx: Float): Boolean = lineHeightPx > 0 && heightPx > MAX_LINES * lineHeightPx

    fun collapsedHeight(lineHeightPx: Float): Int = (COLLAPSED_LINES * lineHeightPx).roundToInt()
}

/** The long comments expanded with "Show all", by key: kept for the session, also once a page is closed. */
object ExpandedComments {
    private val keys = mutableStateMapOf<String, Unit>()

    operator fun contains(key: String) = key in keys

    fun toggle(key: String) {
        if (keys.remove(key) == null) keys[key] = Unit
    }
}

/**
 * [content] (a comment's text) as is when short; longer than [LongText.MAX_LINES] lines, cut
 * with a fade into [background] and "Show all", then "Show less" once expanded. [key] names the
 * comment in [ExpandedComments].
 */
@Composable
internal fun CollapsibleText(
    key: String,
    modifier: Modifier = Modifier,
    background: Color = AdoTheme.colors.surface,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val lineHeight = with(density) { MaterialTheme.typography.bodyMedium.lineHeight.toPx() }
    var fullHeight by remember(key) { mutableIntStateOf(0) }
    val long = LongText.collapses(fullHeight, lineHeight)
    val expanded = key in ExpandedComments
    val collapsed = long && !expanded
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().then(
                if (collapsed) Modifier.heightIn(max = with(density) { LongText.collapsedHeight(lineHeight).toDp() }).clipToBounds()
                else Modifier,
            ),
        ) {
            Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { fullHeight = it.height }) {
                content()
            }
            if (collapsed) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(40.dp)
                        .background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background))),
                )
            }
        }
        if (long) {
            TextButton(onClick = { ExpandedComments.toggle(key) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text(
                    stringResource(if (expanded) R.string.comment_show_less else R.string.comment_show_all),
                    style = MaterialTheme.typography.titleSmall, color = AdoTheme.colors.primary, maxLines = 1,
                )
            }
        }
    }
}
