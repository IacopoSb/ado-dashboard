package io.github.iacoposb.adodash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.ui.AdoTheme

/**
 * A panel with nothing in it, as in the design: the icon in a circle, a title and one line,
 * centered in [modifier]'s space. Not a placeholder: what loads shows [SkeletonRows].
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    /** What to do about it, below the text. */
    action: (@Composable () -> Unit)? = null,
) {
    val c = AdoTheme.colors
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(c.surfaceAlt), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = c.textMuted)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                color = c.text, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text, Modifier.widthIn(max = 280.dp), style = MaterialTheme.typography.bodySmall,
                color = c.textMuted, textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}
