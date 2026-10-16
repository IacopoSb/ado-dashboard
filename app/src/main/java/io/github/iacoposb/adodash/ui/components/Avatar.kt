package io.github.iacoposb.adodash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.ui.AdoTheme

/** "Anna Verdi" -> "AV"; "[Contoso]\Backend Team" -> "BT". */
fun initials(name: String): String =
    name.substringAfterLast('\\').split(' ', '[', ']', '.', '_').filter { it.isNotBlank() }
        .take(2).joinToString("") { it.take(1) }.uppercase()

/** Initials in a circle; [ring] (e.g. the reviewer's vote color) draws a 2 dp border. */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    ring: Color? = null,
    size: Dp = 32.dp,
    description: String? = null,
) {
    val c = AdoTheme.colors
    Box(
        modifier.size(size).clip(CircleShape)
            .border(if (ring != null) 2.dp else 1.dp, ring ?: c.outline, CircleShape)
            .background(c.surfaceAlt)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), style = MaterialTheme.typography.labelMedium, color = c.text, maxLines = 1)
    }
}

/** A reviewer with the color of their vote around the initials. */
@Composable
fun ReviewerAvatar(name: String, vote: Int, description: String, modifier: Modifier = Modifier) =
    Avatar(name, modifier, ring = AdoTheme.colors.vote(vote), description = description)
