package io.github.iacoposb.adodash.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.ui.components.highlight

/** Taps this soon after the boards appear were aimed at what was there before. */
private const val SETTLE_MILLIS = 300L

/**
 * Whether a tap counts: not in the first moments after [key] changes, when the rows it lands on
 * may have just appeared under the finger.
 */
@Composable
fun rememberSettled(key: Any?): () -> Boolean {
    val since = remember(key) { SystemClock.uptimeMillis() }
    return { SystemClock.uptimeMillis() - since >= SETTLE_MILLIS }
}

/** The boards being read: a spinner with "Loading boards…" over placeholder project rows. */
@Composable
fun BoardsLoading(modifier: Modifier = Modifier, rowHeight: Dp = 44.dp, rows: Int = 4) {
    val c = AdoTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = c.primary, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.picker_loading_boards), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        repeat(rows) {
            Box(Modifier.fillMaxWidth().height(rowHeight).clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt))
        }
    }
}

/** Why there is no board to show: none in the filters' projects, none matching, or an error. */
@Composable
fun BoardsMessage(choices: ViewChoices, menu: BoardMenu, modifier: Modifier = Modifier) {
    val text = when {
        choices.boardsError != null -> stringResource(R.string.boards_error, choices.boardsError)
        menu.searching -> stringResource(R.string.boards_no_results, menu.query)
        else -> stringResource(R.string.picker_no_boards)
    }
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = AdoTheme.colors.textMuted)
}

/** A project of the boards list: tapping it opens or closes its teams (not while searching). */
@Composable
fun ProjectHeader(group: BoardGroup, query: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = AdoTheme.colors
    val searching = query.isNotEmpty()
    val label = stringResource(if (group.expanded) R.string.action_collapse else R.string.action_expand, group.project)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = !searching, role = Role.Button, onClickLabel = label, onClick = onToggle)
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (group.expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null, Modifier.size(20.dp), tint = if (searching) c.textFaint else c.textMuted,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            highlight(group.project, query.takeIf { group.projectMatch }), Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (searching && !group.projectMatch) {
                stringResource(R.string.boards_matching, group.teams.size, group.total)
            } else {
                pluralStringResource(R.plurals.picker_board_count, group.total, group.total)
            },
            style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1,
        )
    }
}
