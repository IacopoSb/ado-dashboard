package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventKind
import io.github.iacoposb.adodash.sync.SyncState
import java.time.Instant

/** Stateless: everything comes in, every action goes out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    organization: String,
    state: SyncState,
    events: List<Event>,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (url: String) -> Unit,
    onEventOpen: (Event) -> Unit,
    onMarkAllRead: () -> Unit,
    now: Instant = Instant.now(),
    /** A newer version whose banner has not been closed. */
    updateVersion: String? = null,
    onUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.dashboard_title, organization), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val snapshot = state.snapshot
                        val subtitle = when {
                            snapshot != null -> stringResource(R.string.dashboard_synced, snapshot.me.name, timeOfDay(snapshot.syncedAt))
                            state.syncing -> stringResource(R.string.dashboard_syncing)
                            else -> stringResource(R.string.dashboard_not_synced)
                        }
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (state.syncing) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.action_settings)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            updateVersion?.let { UpdateBanner(it, onUpdate, onDismissUpdate) }
            state.error?.let { Banner(it) }
            state.snapshot?.warnings?.takeIf { it.isNotEmpty() }?.let { Banner(it.joinToString(" · "), subtle = true) }

            val snapshot = state.snapshot
            val workItems = snapshot?.workItems.orEmpty()
            val reviewPrs = snapshot?.reviewPrs.orEmpty()
            val myPrs = snapshot?.myPrs.orEmpty()
            val me = snapshot?.me ?: Identity("", "")
            val unread = events.count { !it.read }
            // Read here: the LazyListScope builders below are not composable
            val noWorkItems = stringResource(R.string.empty_work_items)
            val toReview = stringResource(R.string.section_to_review)
            val noReviews = stringResource(R.string.empty_to_review)
            val mine = stringResource(R.string.section_mine)
            val noMyPrs = stringResource(R.string.empty_mine)
            val noEvents = stringResource(R.string.empty_activity)

            val workPanel: @Composable (Modifier) -> Unit = { m ->
                Panel(stringResource(R.string.panel_work_items), workItems.size, m) {
                    if (workItems.isEmpty()) empty(noWorkItems)
                    items(workItems, key = { it.id }) { WorkItemRow(it, now) { onOpen(it.url) } }
                }
            }
            val prPanel: @Composable (Modifier) -> Unit = { m ->
                Panel(stringResource(R.string.panel_pull_requests), reviewPrs.size + myPrs.size, m) {
                    section(toReview, reviewPrs.size)
                    if (reviewPrs.isEmpty()) empty(noReviews)
                    items(reviewPrs, key = { "r${it.id}" }) { PrRow(it, me, reviewing = true, now) { onOpen(it.url) } }
                    section(mine, myPrs.size)
                    if (myPrs.isEmpty()) empty(noMyPrs)
                    items(myPrs, key = { "m${it.id}" }) { PrRow(it, me, reviewing = false, now) { onOpen(it.url) } }
                }
            }
            val activityPanel: @Composable (Modifier) -> Unit = { m ->
                Panel(
                    stringResource(R.string.panel_activity), unread, m,
                    action = {
                        if (unread > 0) TextButton(onClick = onMarkAllRead) { Text(stringResource(R.string.action_mark_all_read)) }
                    },
                ) {
                    if (events.isEmpty()) empty(noEvents)
                    items(events, key = { it.key }) { EventRow(it, now) { onEventOpen(it) } }
                }
            }

            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (maxWidth >= 840.dp) {
                    Row(
                        Modifier.fillMaxSize().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        workPanel(Modifier.weight(1f))
                        prPanel(Modifier.weight(1f))
                        activityPanel(Modifier.weight(0.9f))
                    }
                } else {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    Column(Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.tab_work_items, workItems.size)) })
                            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.tab_pull_requests, reviewPrs.size + myPrs.size)) })
                            Tab(tab == 2, { tab = 2 }, text = {
                                Text(if (unread > 0) stringResource(R.string.tab_activity_unread, unread) else stringResource(R.string.tab_activity))
                            })
                        }
                        val m = Modifier.fillMaxSize().padding(12.dp)
                        when (tab) {
                            0 -> workPanel(m)
                            1 -> prPanel(m)
                            else -> activityPanel(m)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, subtle: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(if (subtle) colors.surfaceVariant else colors.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, null, tint = if (subtle) colors.onSurfaceVariant else colors.onErrorContainer)
        Spacer(Modifier.width(8.dp))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (subtle) colors.onSurfaceVariant else colors.onErrorContainer,
        )
    }
}

@Composable
private fun Panel(
    title: String,
    count: Int,
    modifier: Modifier,
    action: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Card(modifier.fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            if (count > 0) CountPill(count)
            Spacer(Modifier.weight(1f))
            Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) { action() }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp), content = content)
    }
}

@Composable
private fun CountPill(count: Int) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            "$count", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun LazyListScope.section(title: String, count: Int) = item(key = "section-$title") {
    Text(
        "$title · $count".uppercase(), Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
    )
}

private fun LazyListScope.empty(text: String) = item(key = "empty-$text") {
    Text(
        text, Modifier.fillMaxWidth().padding(16.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Row2(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) { content() }
}

@Composable
private fun WorkItemRow(wi: WorkItem, now: Instant, onClick: () -> Unit) = Row2(onClick) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(workItemColor(wi.type)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${wi.id}  ${wi.title}", style = MaterialTheme.typography.bodyLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(wi.type, wi.project, wi.priority?.let { "P$it" }, ago(wi.changedAt, now)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        StateChip(wi.state)
    }
}

@Composable
private fun StateChip(state: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            state, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun PrRow(pr: PullRequest, me: Identity, reviewing: Boolean, now: Instant, onClick: () -> Unit) = Row2(onClick) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pr.draft) {
                StateChip(stringResource(R.string.pr_draft))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                pr.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "!${pr.id} · ${pr.repoName} · ${pr.sourceBranch} → ${pr.targetBranch}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (reviewing) "${pr.author.name} · ${ago(pr.createdAt, now)}" else ago(pr.createdAt, now),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (reviewing) {
                val myVote = pr.reviewers.firstOrNull { it.id == me.id }?.vote ?: Vote.NONE
                Text(
                    stringResource(if (myVote == Vote.NONE) R.string.pr_vote_needed else voteLabel(myVote)),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (myVote == Vote.NONE) MaterialTheme.colorScheme.primary else voteColor(myVote),
                )
            } else {
                pr.reviewers.take(6).forEach { ReviewerDot(it) }
            }
        }
    }
}

@Composable
private fun ReviewerDot(r: Reviewer) {
    val initials = r.name.split(' ', '\\', '[', ']').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }
    Box(
        Modifier.padding(start = 4.dp).size(28.dp).clip(CircleShape)
            .border(2.dp, voteColor(r.vote), CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials.uppercase(), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun voteColor(vote: Int): Color = when (vote) {
    Vote.APPROVED -> Color(0xFF107C10)
    Vote.APPROVED_WITH_SUGGESTIONS -> Color(0xFF6BB700)
    Vote.WAITING -> Color(0xFFD67F00)
    Vote.REJECTED -> Color(0xFFD13438)
    else -> MaterialTheme.colorScheme.outlineVariant
}

@Composable
private fun EventRow(event: Event, now: Instant, onClick: () -> Unit) {
    val (icon, tint) = eventIcon(event.kind)
    Column {
        Row2(onClick) {
            Row {
                Icon(icon, null, Modifier.padding(top = 2.dp).size(20.dp), tint = tint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        event.title, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (event.read) FontWeight.Normal else FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        event.body.replace('\n', ' '), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ago(Instant.ofEpochMilli(event.at), now), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (!event.read) {
                    Box(Modifier.padding(start = 8.dp, top = 6.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun eventIcon(kind: EventKind): Pair<ImageVector, Color> = when (kind) {
    EventKind.MENTION -> Icons.Filled.AccountCircle to Color(0xFFD13438)
    EventKind.ASSIGNED -> Icons.Filled.Person to MaterialTheme.colorScheme.primary
    EventKind.PR_REVIEW -> Icons.Filled.Edit to Color(0xFF773B93)
    EventKind.PR_COMMENT -> Icons.Filled.Email to MaterialTheme.colorScheme.onSurfaceVariant
    EventKind.PR_VOTE -> Icons.Filled.ThumbUp to Color(0xFF107C10)
}
