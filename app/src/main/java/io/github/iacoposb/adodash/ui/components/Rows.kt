package io.github.iacoposb.adodash.ui.components

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
import androidx.compose.material.icons.filled.ThumbUp
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventKind
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
 * A pull request: draft chip and title, then "!id · repo · branches" (with author when
 * [reviewing]) with the signals at the end of the same line: my vote when [reviewing], the
 * reviewers' votes otherwise. The details give way (ellipsis) to the signals; only more than
 * [AVATARS_INLINE] reviewers move to a line of their own.
 */
@Composable
fun PrRow(pr: PullRequest, me: Identity, reviewing: Boolean, now: Instant, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        val avatarsInline = !reviewing && pr.reviewers.size <= AVATARS_INLINE
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    "!${pr.id}", pr.repoName, "${pr.sourceBranch} → ${pr.targetBranch}",
                    pr.author.name.takeIf { reviewing }, ago(pr.createdAt, now),
                ).joinToString(" · "),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (reviewing) {
                Spacer(Modifier.width(8.dp))
                VoteBadge(pr.reviewers.firstOrNull { it.id == me.id }?.vote ?: Vote.NONE)
            } else if (avatarsInline && pr.reviewers.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                ReviewerAvatars(pr)
            }
        }
        if (!reviewing && !avatarsInline) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { ReviewerAvatars(pr) }
        }
    }
}

/** Reviewers that fit next to the details of a pull request; more go on a line below. */
private const val AVATARS_INLINE = 4

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
    }
}
