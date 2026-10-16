package io.github.iacoposb.adodash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.ui.AdoColors
import io.github.iacoposb.adodash.ui.AdoTheme
import io.github.iacoposb.adodash.ui.voteLabel
import io.github.iacoposb.adodash.R

enum class Tone { Neutral, Info, Success, Warning, Danger }

/** Background and content colors of a [Tone]. */
@Composable
fun Tone.colors(): Pair<Color, Color> = AdoTheme.colors.run {
    when (this@colors) {
        Tone.Neutral -> surfaceAlt to textMuted
        Tone.Info -> primaryContainer to onPrimaryContainer
        Tone.Success -> successContainer to success
        Tone.Warning -> warningContainer to warning
        Tone.Danger -> dangerContainer to danger
    }
}

/** A work item state, "Draft" and the like. [filled] = false drops the background (selected rows). */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral, filled: Boolean = true) {
    val (bg, fg) = tone.colors()
    Box(
        modifier.height(22.dp).clip(RoundedCornerShape(6.dp))
            .background(if (filled) bg else Color.Transparent).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** Tone of a work item state by its usual names: proposed is neutral, in progress is info. */
fun stateTone(state: String): Tone = when (state.lowercase()) {
    "new", "proposed", "to do", "design", "open" -> Tone.Neutral
    "resolved", "done", "closed", "completed" -> Tone.Success
    "removed", "cut" -> Tone.Danger
    "blocked", "on hold" -> Tone.Warning
    else -> Tone.Info
}

/** The number next to a panel title. */
@Composable
fun CountPill(count: Int, modifier: Modifier = Modifier) {
    val c = AdoTheme.colors
    Box(
        modifier.height(16.dp).defaultMinSize(minWidth = 22.dp).clip(CircleShape)
            .background(c.primaryContainer).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", style = MaterialTheme.typography.labelMedium, color = c.onPrimaryContainer, maxLines = 1)
    }
}

/** Color of a reviewer's vote, for badges and avatar rings. */
fun AdoColors.vote(vote: Int): Color = when (vote) {
    Vote.APPROVED -> success
    Vote.APPROVED_WITH_SUGGESTIONS -> suggest
    Vote.WAITING -> warning
    Vote.REJECTED -> danger
    else -> outline
}

/** My vote on a pull request; [Vote.NONE] reads "Needs your vote". */
@Composable
fun VoteBadge(vote: Int, modifier: Modifier = Modifier) {
    val c = AdoTheme.colors
    val (bg, fg) = when (vote) {
        Vote.APPROVED -> c.successContainer to c.success
        Vote.APPROVED_WITH_SUGGESTIONS -> c.successContainer to c.suggest
        Vote.WAITING -> c.warningContainer to c.warning
        Vote.REJECTED -> c.dangerContainer to c.danger
        else -> c.primaryContainer to c.primary
    }
    Row(
        modifier.height(22.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val icon = when (vote) {
            Vote.APPROVED, Vote.APPROVED_WITH_SUGGESTIONS -> Icons.Filled.Check
            Vote.WAITING -> AdoIcons.Hourglass
            Vote.REJECTED -> Icons.Filled.Close
            else -> null
        }
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = fg)
        else Box(Modifier.padding(horizontal = 3.dp).size(6.dp).clip(CircleShape).background(fg))
        Text(
            stringResource(if (vote == Vote.NONE) R.string.pr_vote_needed else voteLabel(vote)),
            style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1,
        )
    }
}

/** A selectable filter: tick and tinted when on, outlined when off. */
@Composable
fun AdoFilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AdoTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.height(32.dp).clip(shape)
            .then(if (selected) Modifier.background(c.primaryContainer) else Modifier.border(1.dp, c.outline, shape))
            .selectable(selected, onClick = onClick, role = Role.Checkbox)
            .padding(start = if (selected) 10.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = c.onPrimaryContainer)
        Text(
            text, style = MaterialTheme.typography.titleSmall, maxLines = 1,
            color = if (selected) c.onPrimaryContainer else c.textMuted,
        )
    }
}
