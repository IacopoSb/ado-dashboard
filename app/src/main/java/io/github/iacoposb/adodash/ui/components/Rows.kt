package io.github.iacoposb.adodash.ui.components

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventKind
import io.github.iacoposb.adodash.sync.BuildSignal
import io.github.iacoposb.adodash.sync.PrSignals
import io.github.iacoposb.adodash.ui.AdoTheme
import io.github.iacoposb.adodash.ui.ago
import io.github.iacoposb.adodash.ui.voteLabel
import io.github.iacoposb.adodash.ui.workItemColor
import java.time.Instant

/**
 * A work item: type color bar, "id  title" on up to two lines, type · project · priority · age,
 * state chip. [selected] tints the row (the item open in a detail).
 */
@Composable
fun WorkItemRow(wi: WorkItem, now: Instant, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val c = AdoTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.background(c.primaryContainer) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(workItemColor(wi.type)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${wi.id}  ${wi.title}", style = MaterialTheme.typography.bodyLarge, color = c.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(wi.type, wi.project, wi.priority?.let { "P$it" }, ago(wi.changedAt, now)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        StatusChip(wi.state, tone = stateTone(wi.state), filled = !selected)
    }
}

/**
 * A pull request: draft chip and title, then "author · age · !id · repo · branches" (author
 * only when [reviewing]), then the signals: build, active threads, conflicts or "ready to
 * complete" ([signals], none while unknown). My vote when [reviewing], or the reviewers' votes
 * otherwise, sit on the right of the signals' first line, or of the details line when there
 * are no signals. Signals that do not fit wrap below.
 */
@Composable
fun PrRow(
    pr: PullRequest,
    me: Identity,
    reviewing: Boolean,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    signals: PrSignals? = null,
) {
    val c = AdoTheme.colors
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row {
            if (pr.draft) {
                StatusChip(stringResource(R.string.pr_draft))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                pr.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        val items = signals?.let { signalItems(it) }.orEmpty()
        val votes: (@Composable () -> Unit)? = when {
            reviewing -> { { VoteBadge(pr.reviewers.firstOrNull { it.id == me.id }?.vote ?: Vote.NONE) } }
            pr.reviewers.isNotEmpty() -> { { ReviewerAvatars(pr) } }
            else -> null
        }
        val meta: @Composable () -> Unit = {
            Text(
                listOfNotNull(
                    pr.author.name.takeIf { reviewing }, ago(pr.createdAt, now),
                    "!${pr.id}", pr.repoName, "${pr.sourceBranch} → ${pr.targetBranch}",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (items.isEmpty()) {
            FlowWithTrailing(votes, Modifier.padding(top = 2.dp), content = meta)
        } else {
            Box(Modifier.padding(top = 2.dp)) { meta() }
            FlowWithTrailing(votes, Modifier.padding(top = 8.dp)) { items.forEach { SignalLabel(it) } }
        }
    }
}

/**
 * [content] flowed left to right, wrapping when it does not fit, with [trailing] right-aligned
 * on the first line; the items of each line are centered vertically on it.
 */
@Composable
private fun FlowWithTrailing(
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(contents = listOf(content, trailing ?: {}), modifier = modifier.fillMaxWidth()) { (items, end), constraints ->
        val gap = 12.dp.roundToPx()
        val lineGap = 2.dp.roundToPx()
        val endGap = 8.dp.roundToPx()
        val width = constraints.maxWidth
        val endPlaceable = end.firstOrNull()?.measure(Constraints(maxWidth = width))
        val firstWidth = (width - (endPlaceable?.let { it.width + endGap } ?: 0)).coerceAtLeast(0)
        // Lines of placeables: the first leaves room for the trailing content
        val lines = mutableListOf(mutableListOf<Placeable>())
        var used = 0
        items.forEach { m ->
            var available = if (lines.size == 1) firstWidth else width
            val natural = minOf(m.maxIntrinsicWidth(Constraints.Infinity), available)
            if (lines.last().isNotEmpty() && used + gap + natural > available) {
                lines += mutableListOf<Placeable>()
                used = 0
                available = width
            }
            val p = m.measure(Constraints(maxWidth = available))
            used += (if (lines.last().isEmpty()) 0 else gap) + p.width
            lines.last() += p
        }
        val heights = lines.mapIndexed { i, line ->
            maxOf(line.maxOfOrNull { it.height } ?: 0, if (i == 0) endPlaceable?.height ?: 0 else 0)
        }
        val height = heights.sum() + lineGap * (lines.size - 1)
        layout(width, height) {
            var y = 0
            lines.forEachIndexed { i, line ->
                var x = 0
                line.forEach { p ->
                    p.placeRelative(x, y + (heights[i] - p.height) / 2)
                    x += p.width + gap
                }
                if (i == 0) endPlaceable?.placeRelative(width - endPlaceable.width, (heights[0] - endPlaceable.height) / 2)
                y += heights[i] + lineGap
            }
        }
    }
}

/** One signal of a pull request: icon, text and their colors. */
private data class SignalItem(val icon: ImageVector, val text: String, val iconTint: Color, val textColor: Color)

@Composable
private fun signalItems(s: PrSignals): List<SignalItem> {
    val c = AdoTheme.colors
    return buildList {
        when (s.build) {
            BuildSignal.SUCCEEDED -> add(SignalItem(AdoIcons.CheckCircle, stringResource(R.string.pr_build_ok), c.success, c.textMuted))
            BuildSignal.FAILED -> add(SignalItem(AdoIcons.Cancel, stringResource(R.string.pr_build_failed), c.danger, c.danger))
            BuildSignal.RUNNING -> add(SignalItem(Icons.Filled.PlayArrow, stringResource(R.string.pr_build_running), c.primary, c.textMuted))
            BuildSignal.EXPIRED -> add(SignalItem(AdoIcons.Hourglass, stringResource(R.string.pr_build_expired), c.warning, c.textMuted))
            BuildSignal.NONE -> {}
        }
        checksText(s.failedChecks, R.string.pr_check_failed, R.plurals.pr_checks_failed)?.let {
            add(SignalItem(AdoIcons.Cancel, it, c.danger, c.danger))
        }
        checksText(s.pendingChecks, R.string.pr_check_pending, R.plurals.pr_checks_pending)?.let {
            add(SignalItem(AdoIcons.Hourglass, it, c.warning, c.textMuted))
        }
        // Threads that block completion get the warning's color
        val threadsTint = if (s.commentsBlocking) c.warning else c.textMuted
        val active = s.activeThreads?.takeIf { it > 0 }
        if (active != null) {
            add(SignalItem(AdoIcons.ChatBubble, pluralStringResource(R.plurals.pr_active_threads, active, active), threadsTint, c.textMuted))
        } else if (s.commentsBlocking) {
            add(SignalItem(AdoIcons.ChatBubble, stringResource(R.string.pr_check_comments), threadsTint, c.textMuted))
        }
        if (s.workItemsMissing) add(SignalItem(Icons.Filled.Warning, stringResource(R.string.pr_check_no_work_items), c.warning, c.textMuted))
        if (s.conflicts) add(SignalItem(AdoIcons.MergeType, stringResource(R.string.pr_conflicts), c.danger, c.danger))
        if (s.readyToComplete) add(SignalItem(AdoIcons.CheckCircle, stringResource(R.string.pr_ready), c.success, c.success))
    }
}

/** One check by name ("SonarQube/quality-gate failed"), several by count; null for none. */
@Composable
fun checksText(names: List<String>, @StringRes one: Int, @PluralsRes several: Int): String? = when (names.size) {
    0 -> null
    1 -> stringResource(one, names.single())
    else -> pluralStringResource(several, names.size, names.size)
}

@Composable
private fun SignalLabel(item: SignalItem) = Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(item.icon, null, Modifier.size(16.dp), tint = item.iconTint)
    Spacer(Modifier.width(4.dp))
    Text(item.text, style = MaterialTheme.typography.bodySmall, color = item.textColor, maxLines = 1)
}

@Composable
private fun ReviewerAvatars(pr: PullRequest) = Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    pr.reviewers.take(6).forEach {
        ReviewerAvatar(it.name, it.vote, "${it.name}: ${stringResource(voteLabel(it.vote))}")
    }
}

/** An activity event: icon by kind, title (bold and dotted while unread), body, age, divider. */
@Composable
fun EventRow(event: Event, now: Instant, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AdoTheme.colors
    val (icon, tint) = eventIcon(event.kind)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    event.title, style = MaterialTheme.typography.bodyMedium, color = c.text,
                    fontWeight = if (event.read) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (event.body.isNotBlank()) {
                    Text(
                        event.body.replace('\n', ' '), style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(ago(Instant.ofEpochMilli(event.at), now), style = MaterialTheme.typography.bodySmall, color = c.textFaint)
            }
            if (!event.read) {
                Box(Modifier.padding(start = 8.dp, top = 1.dp).size(8.dp).clip(CircleShape).background(c.primary))
            }
        }
        HorizontalDivider(color = c.outline)
    }
}

@Composable
private fun eventIcon(kind: EventKind): Pair<ImageVector, Color> = AdoTheme.colors.run {
    when (kind) {
        EventKind.MENTION -> AdoIcons.AlternateEmail to danger
        EventKind.ASSIGNED -> Icons.Filled.Person to primary
        EventKind.PR_REVIEW -> AdoIcons.RateReview to accent
        EventKind.PR_COMMENT -> AdoIcons.ChatBubble to textMuted
        EventKind.PR_VOTE -> Icons.Filled.ThumbUp to success
        EventKind.BUILD_FAILED -> AdoIcons.Cancel to danger
    }
}
