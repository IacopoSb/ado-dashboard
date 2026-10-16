package io.github.iacoposb.adodash.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.ui.AdoTheme

enum class ButtonStyle { Filled, Tonal, Outlined, Text, Danger }

/** The design's pill button, 40 dp tall, with an optional leading icon. */
@Composable
fun AdoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = AdoTheme.colors
    val (container, content) = when (style) {
        ButtonStyle.Filled -> c.primary to c.onPrimary
        ButtonStyle.Tonal -> c.primaryContainer to c.onPrimaryContainer
        ButtonStyle.Outlined, ButtonStyle.Text -> Color.Transparent to c.primary
        ButtonStyle.Danger -> c.dangerContainer to c.danger
    }
    val colors = ButtonDefaults.buttonColors(
        containerColor = container,
        contentColor = content,
        // As in the design: the same button, faded
        disabledContainerColor = if (container == Color.Transparent) container else container.copy(alpha = 0.38f),
        disabledContentColor = if (container == Color.Transparent) content.copy(alpha = 0.38f) else content.copy(alpha = 0.7f),
    )
    val horizontal = if (style == ButtonStyle.Text) 12.dp else 16.dp
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
        enabled = enabled,
        colors = colors,
        border = if (style == ButtonStyle.Outlined) BorderStroke(1.dp, c.outline) else null,
        contentPadding = PaddingValues(start = if (icon != null) horizontal - 4.dp else horizontal, end = horizontal),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
