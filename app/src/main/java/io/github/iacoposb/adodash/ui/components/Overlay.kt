package io.github.iacoposb.adodash.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.ui.AdoTheme

/** Below this width a modal takes the whole screen. */
val FullScreenModalWidth = 840.dp

/**
 * A modal drawn over the screen it belongs to, with a scrim that closes it: a centered card up
 * to [maxWidth] wide, or the whole screen on narrow ones (`fullScreen` in [content]). Drawn in
 * the same window rather than in a dialog, so that the screenshots show it over its screen;
 * the screen below should hide its semantics while it is open.
 */
@Composable
fun ModalOverlay(
    title: String,
    onDismiss: () -> Unit,
    maxWidth: Dp = 900.dp,
    scrim: Boolean = true,
    /** Back: closes it, unless the modal has a way back of its own. */
    onBack: () -> Unit = onDismiss,
    /** The card's height at most; the screen's own height less the margins in any case. */
    maxHeight: Dp = Dp.Infinity,
    content: @Composable ColumnScope.(fullScreen: Boolean) -> Unit,
) {
    BackHandler(onBack = onBack)
    val c = AdoTheme.colors
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(if (scrim) Color.Black.copy(alpha = 0.32f) else c.bg)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        val fullScreen = this.maxWidth < FullScreenModalWidth
        val card = if (fullScreen) {
            Modifier.fillMaxSize().background(c.surface).windowInsetsPadding(WindowInsets.safeDrawing)
        } else {
            Modifier.align(Alignment.Center).padding(24.dp).width(maxWidth.coerceAtMost(this.maxWidth - 48.dp))
                .heightIn(max = maxHeight.coerceAtMost(this.maxHeight - 48.dp))
                .clip(RoundedCornerShape(24.dp)).background(c.surface)
        }
        Column(
            card
                // Taps inside the card stay there
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .semantics {
                    paneTitle = title
                    isTraversalGroup = true
                },
        ) { content(fullScreen) }
    }
}

/** Fills the rest of a [Box] with nothing: for the scrim of a popover menu. */
@Composable
fun DismissLayer(onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
}
