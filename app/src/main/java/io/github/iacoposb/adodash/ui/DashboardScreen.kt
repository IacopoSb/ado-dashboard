package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.sync.SyncState
import io.github.iacoposb.adodash.ui.components.Banner
import io.github.iacoposb.adodash.ui.components.BannerTone
import io.github.iacoposb.adodash.ui.components.EventRow
import io.github.iacoposb.adodash.ui.components.Panel
import io.github.iacoposb.adodash.ui.components.PrRow
import io.github.iacoposb.adodash.ui.components.SectionHeader
import io.github.iacoposb.adodash.ui.components.TextAction
import io.github.iacoposb.adodash.ui.components.WorkItemRow
import java.time.Instant

/** Stateless: everything comes in, every action goes out. */
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
    val c = AdoTheme.colors
    Column(
        Modifier.fillMaxSize().background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        DashboardBar(organization, state, onRefresh, onSettings)

        val snapshot = state.snapshot
        val workItems = snapshot?.workItems.orEmpty()
        val reviewPrs = snapshot?.reviewPrs.orEmpty()
        val myPrs = snapshot?.myPrs.orEmpty()
        val me = snapshot?.me ?: Identity("", "")
        val unread = events.count { !it.read }

        Column(Modifier.padding(horizontal = 12.dp)) {
            val top = Modifier.padding(top = 8.dp)
            updateVersion?.let {
                Banner(stringResource(R.string.update_available, it), BannerTone.Info, top) {
                    TextAction(stringResource(R.string.action_later), onDismissUpdate)
                    TextAction(stringResource(R.string.action_update), onUpdate, color = c.onPrimaryContainer)
                }
            }
            state.error?.let {
                Banner(it, BannerTone.Error, top) { TextAction(stringResource(R.string.action_retry), onRefresh) }
            }
            snapshot?.warnings?.takeIf { it.isNotEmpty() }?.let { Banner(it.joinToString(" · "), BannerTone.Warning, top) }
        }

        // Read here: the LazyListScope builders below are not composable
        val noWorkItems = stringResource(R.string.empty_work_items)
        val toReview = stringResource(R.string.section_to_review)
        val noReviews = stringResource(R.string.empty_to_review)
        val mine = stringResource(R.string.section_mine)
        val noMyPrs = stringResource(R.string.empty_mine)
        val noEvents = stringResource(R.string.empty_activity)
        val bottom = PaddingValues(bottom = 12.dp)

        val workPanel: @Composable (Modifier) -> Unit = { m ->
            Panel(stringResource(R.string.panel_work_items), m, count = workItems.size) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
                    if (workItems.isEmpty()) empty(noWorkItems)
                    items(workItems, key = { it.id }) { WorkItemRow(it, now, onClick = { onOpen(it.url) }) }
                }
            }
        }
        val prPanel: @Composable (Modifier) -> Unit = { m ->
            Panel(stringResource(R.string.panel_pull_requests), m, count = reviewPrs.size + myPrs.size) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
                    item(key = "section-review") { SectionHeader(toReview, count = reviewPrs.size) }
                    if (reviewPrs.isEmpty()) empty(noReviews)
                    items(reviewPrs, key = { "r${it.id}" }) { PrRow(it, me, reviewing = true, now, onClick = { onOpen(it.url) }) }
                    item(key = "section-mine") { SectionHeader(mine, count = myPrs.size) }
                    if (myPrs.isEmpty()) empty(noMyPrs)
                    items(myPrs, key = { "m${it.id}" }) { PrRow(it, me, reviewing = false, now, onClick = { onOpen(it.url) }) }
                }
            }
        }
        val activityPanel: @Composable (Modifier) -> Unit = { m ->
            Panel(
                stringResource(R.string.panel_activity), m, count = unread,
                action = { if (unread > 0) TextAction(stringResource(R.string.action_mark_all_read), onMarkAllRead) },
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
                    if (events.isEmpty()) empty(noEvents)
                    items(events, key = { it.key }) { EventRow(it, now, onClick = { onEventOpen(it) }) }
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panels = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            if (maxWidth >= 840.dp) {
                Row(
                    panels.fillMaxSize().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    workPanel(Modifier.weight(1f).fillMaxHeight())
                    prPanel(Modifier.weight(1f).fillMaxHeight())
                    activityPanel(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize()) {
                    TabRow(
                        selectedTabIndex = tab, containerColor = c.bg, contentColor = c.primary,
                        indicator = { positions ->
                            TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[tab]), color = c.primary)
                        },
                        divider = {},
                    ) {
                        @Composable fun label(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Tab(tab == 0, { tab = 0 }, unselectedContentColor = c.textMuted, text = {
                            label(stringResource(R.string.tab_work_items, workItems.size))
                        })
                        Tab(tab == 1, { tab = 1 }, unselectedContentColor = c.textMuted, text = {
                            label(stringResource(R.string.tab_pull_requests, reviewPrs.size + myPrs.size))
                        })
                        Tab(tab == 2, { tab = 2 }, unselectedContentColor = c.textMuted, text = {
                            label(if (unread > 0) stringResource(R.string.tab_activity_unread, unread) else stringResource(R.string.tab_activity))
                        })
                    }
                    val m = panels.fillMaxSize().padding(12.dp)
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

/**
 * Title as the view selector (only the global view for now, so no menu yet), with
 * "org · user · updated at" below; refresh and settings on the right.
 */
@Composable
private fun DashboardBar(organization: String, state: SyncState, onRefresh: () -> Unit, onSettings: () -> Unit) {
    val c = AdoTheme.colors
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.view_global), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, color = c.text, maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(24.dp), tint = c.textMuted)
            }
            val snapshot = state.snapshot
            val status = when {
                snapshot != null -> stringResource(R.string.dashboard_synced, snapshot.me.name, timeOfDay(snapshot.syncedAt))
                state.syncing -> stringResource(R.string.dashboard_syncing)
                else -> stringResource(R.string.dashboard_not_synced)
            }
            Text(
                "$organization · $status", style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.syncing) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), color = c.primary, strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh), tint = c.textMuted) }
        }
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.action_settings), tint = c.textMuted) }
    }
}

private fun LazyListScope.empty(text: String) = item(key = "empty-$text") {
    Text(
        text, Modifier.fillMaxWidth().padding(16.dp),
        style = MaterialTheme.typography.bodyMedium, color = AdoTheme.colors.textMuted,
    )
}
