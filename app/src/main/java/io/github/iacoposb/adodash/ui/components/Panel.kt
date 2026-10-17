package io.github.iacoposb.adodash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.ui.AdoTheme

/** A dashboard column: title, count, an optional action on the right and the rows below. */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    action: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(AdoTheme.colors.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title, Modifier.weight(1f, fill = false).semantics { heading() },
                style = MaterialTheme.typography.titleMedium, color = AdoTheme.colors.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (count != null && count > 0) {
                Spacer(Modifier.width(8.dp))
                CountPill(count)
            }
            Spacer(Modifier.weight(1f))
            action()
        }
        content()
    }
}

/** A card in the settings: title, [trailing] after it, and the content spaced by 12 dp. */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AdoTheme.colors.surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, color = AdoTheme.colors.text,
                )
                trailing?.invoke(this)
            }
        }
        content()
    }
}

/** "TO REVIEW · 2" inside a panel. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, count: Int? = null) {
    Text(
        (if (count != null) "$title · $count" else title).uppercase(),
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelSmall, color = AdoTheme.colors.primary,
    )
}
