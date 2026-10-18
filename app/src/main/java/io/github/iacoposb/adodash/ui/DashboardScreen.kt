package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.PrScope
import io.github.iacoposb.adodash.sync.BacklogFilter
import io.github.iacoposb.adodash.sync.SyncState
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.Banner
import io.github.iacoposb.adodash.ui.components.EmptyState
import io.github.iacoposb.adodash.ui.components.BannerTone
import io.github.iacoposb.adodash.ui.components.EventRow
import io.github.iacoposb.adodash.ui.components.Panel
import io.github.iacoposb.adodash.ui.components.PrRow
import io.github.iacoposb.adodash.ui.components.SectionHeader
import io.github.iacoposb.adodash.ui.components.SkeletonRows
import io.github.iacoposb.adodash.ui.components.TextAction
import io.github.iacoposb.adodash.ui.components.WorkItemRow
import java.time.Instant

/**
 * Stateless: everything comes in, every action goes out (the switcher and the editor being
 * open is the screen's own state). The panels show the current view of [choices].
 */
@Composable
fun DashboardScreen(
    organization: String,
    state: SyncState,
    /** Already limited to what the current view shows. */
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
    /** The views and boards to switch to; the current one is what the panels show. */
    choices: ViewChoices = ViewChoices(),
    onOpenView: (String) -> Unit = {},
    onOpenBoard: (BoardRef) -> Unit = {},
    onSaveView: (DashboardView) -> Unit = {},
    onDeleteView: (DashboardView) -> Unit = {},
    /** The switcher opened: it lists the boards. */
    onLoadBoards: () -> Unit = {},
    /** For the screenshots: the switcher or the editor open from the start. */
    initialSwitcher: Boolean = false,
    initialSwitcherQuery: String = "",
    initialEditor: EditorTarget? = null,
    /** The build details open over the dashboard, if any. */
    buildDetail: BuildDetail? = null,
    onRerunBuild: () -> Unit = {},
    onCloseBuild: () -> Unit = {},
    /** A board's pipelines open over the dashboard, if any: a [buildDetail] then shows inside. */
    pipelines: PipelinesState? = null,
    /** The tile tapped: the project's pipelines. */
    onOpenPipelines: (project: String, team: String?) -> Unit = { _, _ -> },
    pipelinesActions: PipelinesActions = PipelinesActions(),
    /** For the screenshots: folders and pipelines expanded in the pipelines, and the search. */
    pipelinesFolders: Set<String> = emptySet(),
    pipelinesOpen: Set<Int> = emptySet(),
    pipelinesQuery: String = "",
    /** A work item row tapped: its details (null: the browser). */
    onOpenWorkItem: ((WorkItem) -> Unit)? = null,
    /** The work item details open over the dashboard, if any. */
    workItem: WorkItemModal? = null,
    workItemActions: WorkItemActions = WorkItemActions(),
    /** For the screenshots: a field's menu open in the work item details. */
    workItemMenu: String? = null,
    /** For the screenshots: what that menu's search starts with. */
    workItemMenuQuery: String = "",
    /** A pull request row tapped: its page (null: the browser). */
    onOpenPullRequest: ((PullRequest) -> Unit)? = null,
    /** The pull request page open over the dashboard, if any. */
    pullRequest: PrScreen? = null,
    prActions: PrActions = PrActions(),
    /** For the screenshots: the vote menu open, and threads expanded, on the pull request page. */
    prVoteMenu: Boolean = false,
    prExpanded: Set<Int> = emptySet(),
    /** For the screenshots: the sprint backlog open, with its filters and the parents expanded. */
    initialBacklog: Boolean = false,
    backlogFilter: BacklogFilter = BacklogFilter(),
    backlogExpanded: Set<Int> = emptySet(),
    /** The device has a network: otherwise the offline banner, over what was saved. */
    online: Boolean = true,
    /** "Sign in" on the expired session's banner. */
    onSignIn: () -> Unit = {},
) {
    val c = AdoTheme.colors
    val view = choices.views.current
    val status = DashboardStatus.of(state, online, view)
    var switcher by remember { mutableStateOf(initialSwitcher) }
    var editor by remember { mutableStateOf(initialEditor) }
    LaunchedEffect(switcher) { if (switcher) onLoadBoards() }
    // The backlog of the board's sprint: only while the board shows one
    var backlog by remember(view.id) { mutableStateOf(initialBacklog) }
    val sprint = state.snapshot?.shownFor(view)?.sprint?.takeIf { view.board != null }
    val backlogShown = backlog && sprint != null

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                // Under the switcher or the editor nothing is reachable
                .then(
                    if (switcher || editor != null || buildDetail != null || workItem != null || pipelines != null || pullRequest != null ||
                        backlogShown
                    ) {
                        Modifier.clearAndSetSemantics {}
                    } else {
                        Modifier
                    },
                ),
        ) {
            DashboardBar(
                organization, view, state, status, now, onRefresh, onSettings, onTitle = { switcher = true }, onOpenPipelines = onOpenPipelines,
                onOpenSprint = { backlog = true },
            )
            Panels(
                state, status, events, view, now, updateVersion, onUpdate, onDismissUpdate, onRefresh, onSignIn, onOpen, onEventOpen,
                onMarkAllRead, onEdit = { editor = EditorTarget(view, focus = it) },
                onOpenWorkItem = onOpenWorkItem ?: { onOpen(it.url) },
                onOpenPullRequest = onOpenPullRequest ?: { onOpen(it.url) },
            )
        }

        if (switcher) {
            ViewSwitcher(
                choices,
                onOpenView = { switcher = false; onOpenView(it) },
                onOpenBoard = { switcher = false; onOpenBoard(it) },
                onEdit = { switcher = false; editor = EditorTarget(view) },
                onNew = { switcher = false; editor = EditorTarget(DashboardView.newSaved(""), isNew = true) },
                onDismiss = { switcher = false },
                initialQuery = initialSwitcherQuery,
            )
        }
        editor?.let { target ->
            ViewEditor(
                target, choices.options,
                onSave = { editor = null; onSaveView(it) },
                onDelete = { editor = null; onDeleteView(it) },
                onDismiss = { editor = null },
            )
        }
        // The details of its rows show over it
        if (backlogShown && sprint != null) {
            Box(Modifier.fillMaxSize().then(if (workItem != null) Modifier.clearAndSetSemantics {} else Modifier)) {
                SprintBacklogScreen(
                    sprint, state.snapshot?.me ?: Identity("", ""), now, state.syncing,
                    BacklogActions(
                        onBack = { backlog = false },
                        onRefresh = onRefresh,
                        onOpen = onOpenWorkItem ?: { onOpen(it.url) },
                    ),
                    boardTitle = viewTitle(view),
                    initialFilter = backlogFilter,
                    initialExpanded = backlogExpanded,
                )
            }
        }
        // The page first: the build and work item details it opens show over it
        pullRequest?.let {
            Box(Modifier.fillMaxSize().then(if (buildDetail != null || workItem != null) Modifier.clearAndSetSemantics {} else Modifier)) {
                PullRequestScreen(it, now, prActions, initialVoteMenu = prVoteMenu, initialExpanded = prExpanded)
            }
        }
        if (pipelines != null) {
            PipelinesModal(pipelines, buildDetail, now, pipelinesActions, pipelinesFolders, pipelinesOpen, pipelinesQuery)
        } else {
            buildDetail?.let { BuildDetailsModal(it, now, onOpen, onRerunBuild, onCloseBuild) }
        }
        workItem?.let { WorkItemDetailsModal(it, now, workItemActions, initialMenu = workItemMenu, initialQuery = workItemMenuQuery) }
    }
}

@Composable
private fun Panels(
    state: SyncState,
    status: DashboardStatus,
    events: List<Event>,
    view: DashboardView,
    now: Instant,
    updateVersion: String?,
    onUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
    onOpen: (url: String) -> Unit,
    onEventOpen: (Event) -> Unit,
    onMarkAllRead: () -> Unit,
    onEdit: (ViewPanel) -> Unit,
    onOpenWorkItem: (WorkItem) -> Unit,
    onOpenPullRequest: (PullRequest) -> Unit,
) {
    val c = AdoTheme.colors
    val snapshot = state.snapshot
    // Another view's data stays hidden until the sync for this one is done
    val shown = snapshot?.shownFor(view)
    val workItems = shown?.workItems.orEmpty()
    val reviewPrs = shown?.reviewPrs.orEmpty()
    val myPrs = shown?.myPrs.orEmpty()
    val otherPrs = shown?.otherPrs.orEmpty()
    val prCount = reviewPrs.size + myPrs.size + otherPrs.size
    val me = snapshot?.me ?: Identity("", "")
    val unread = events.count { !it.read }
    val loading = status.content == PanelsContent.LOADING
    val noData = status.content == PanelsContent.NO_DATA
    val prScopes = view.pullRequests.show
    val signals = snapshot?.signals.orEmpty()

    Column(Modifier.padding(horizontal = 12.dp)) {
        val top = Modifier.padding(top = 8.dp)
        updateVersion?.let {
            Banner(stringResource(R.string.update_available, it), BannerTone.Info, top) {
                TextAction(stringResource(R.string.action_later), onDismissUpdate)
                TextAction(stringResource(R.string.action_update), onUpdate, color = c.onPrimaryContainer)
            }
        }
        when (val banner = status.banner) {
            is StatusBanner.Offline -> Banner(
                banner.syncedAt?.let { stringResource(R.string.banner_offline, updatedAt(it, now)) }
                    ?: stringResource(R.string.banner_offline_no_data),
                BannerTone.Offline, top,
            ) { TextAction(stringResource(R.string.action_retry), onRefresh) }
            StatusBanner.SessionExpired -> Banner(stringResource(R.string.banner_session_expired), BannerTone.Error, top) {
                TextAction(stringResource(R.string.action_sign_in), onSignIn)
            }
            is StatusBanner.Error -> Banner(banner.message, BannerTone.Error, top) {
                TextAction(stringResource(R.string.action_retry), onRefresh)
            }
            null -> Unit
        }
        snapshot?.warnings?.takeIf { it.isNotEmpty() }?.let { Banner(it.joinToString(" · "), BannerTone.Warning, top) }
    }

    // Read here: the LazyListScope builders below are not composable
    val noWorkItems = stringResource(R.string.empty_work_items)
    val noDataTexts = EmptyTexts(stringResource(R.string.empty_no_data_title), stringResource(R.string.empty_no_data_text))
    val emptyWorkItems = if (view.workItems.withinMine) {
        EmptyTexts(noWorkItems, stringResource(R.string.empty_work_items_text))
    } else {
        EmptyTexts(stringResource(R.string.empty_view_work_items_title), stringResource(R.string.empty_view_text))
    }
    val emptyPrs = if (PrScope.ALL in prScopes) {
        EmptyTexts(stringResource(R.string.empty_mine), stringResource(R.string.empty_view_text))
    } else {
        EmptyTexts(stringResource(R.string.empty_pull_requests_title), stringResource(R.string.empty_pull_requests_text))
    }
    val emptyActivity = EmptyTexts(stringResource(R.string.empty_activity_title), stringResource(R.string.empty_activity_text))
    val toReview = stringResource(R.string.section_to_review)
    val noReviews = stringResource(R.string.empty_to_review)
    val mine = stringResource(R.string.section_mine)
    val noMyPrs = stringResource(R.string.empty_mine)
    val others = stringResource(if (prScopes.size > 1) R.string.section_others_in_my_repos else R.string.section_in_my_repos)
    val noOthers = stringResource(R.string.empty_others)
    val syncing = stringResource(R.string.dashboard_syncing)
    val bottom = PaddingValues(bottom = 12.dp)

    val workPanel: @Composable (Modifier) -> Unit = { m ->
        Panel(
            stringResource(R.string.panel_work_items), m, count = workItems.size,
            action = { TextAction(workItemsSummary(view.workItems), { onEdit(ViewPanel.WORK_ITEMS) }) },
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
                when {
                    loading -> skeleton(syncing)
                    noData -> emptyState(AdoIcons.CloudOff, noDataTexts)
                    workItems.isEmpty() -> emptyState(Icons.Filled.Check, emptyWorkItems)
                    else -> items(workItems, key = { it.id }) { WorkItemRow(it, now, onClick = { onOpenWorkItem(it) }) }
                }
            }
        }
    }
    val prPanel: @Composable (Modifier) -> Unit = { m ->
        Panel(
            stringResource(R.string.panel_pull_requests), m, count = prCount,
            action = { TextAction(pullRequestsSummary(view.pullRequests), { onEdit(ViewPanel.PULL_REQUESTS) }) },
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
                if (loading) {
                    skeleton(syncing)
                    return@LazyColumn
                }
                if (noData) {
                    emptyState(AdoIcons.CloudOff, noDataTexts)
                    return@LazyColumn
                }
                // Nothing at all: one empty state for the panel, not one line per section
                if (prCount == 0) {
                    emptyState(AdoIcons.MergeType, emptyPrs)
                    return@LazyColumn
                }
                if (PrScope.TO_REVIEW in prScopes) {
                    item(key = "section-review") { SectionHeader(toReview, count = reviewPrs.size) }
                    if (reviewPrs.isEmpty()) empty(noReviews)
                    items(reviewPrs, key = { "r${it.id}" }) { PrRow(it, me, reviewing = true, now, onClick = { onOpenPullRequest(it) }, signals = signals[it.id]) }
                }
                if (PrScope.MINE in prScopes) {
                    item(key = "section-mine") { SectionHeader(mine, count = myPrs.size) }
                    if (myPrs.isEmpty()) empty(noMyPrs)
                    items(myPrs, key = { "m${it.id}" }) { PrRow(it, me, reviewing = false, now, onClick = { onOpenPullRequest(it) }, signals = signals[it.id]) }
                }
                if (PrScope.ALL in prScopes) {
                    item(key = "section-others") { SectionHeader(others, count = otherPrs.size) }
                    if (otherPrs.isEmpty()) empty(noOthers)
                    items(otherPrs, key = { "o${it.id}" }) { PrRow(it, me, reviewing = false, now, onClick = { onOpenPullRequest(it) }, signals = signals[it.id]) }
                }
                if (prScopes.isEmpty()) empty(noOthers)
            }
        }
    }
    val activityPanel: @Composable (Modifier) -> Unit = { m ->
        Panel(
            stringResource(R.string.panel_activity), m, count = unread,
            action = {
                if (unread > 0) TextAction(stringResource(R.string.action_mark_all_read), onMarkAllRead)
                else if (!view.activity.showsAll) TextAction(activitySummary(view.activity), { onEdit(ViewPanel.ACTIVITY) })
            },
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
                when {
                    events.isNotEmpty() -> Unit
                    loading && state.snapshot == null -> skeleton(syncing)
                    noData -> emptyState(AdoIcons.CloudOff, noDataTexts)
                    else -> emptyState(Icons.Filled.Notifications, emptyActivity)
                }
                items(events, key = { it.key }) { EventRow(it, now, onClick = { onEventOpen(it) }) }
            }
        }
    }

    // Only the panels the view shows, with their tab labels on narrow screens
    val workTab = stringResource(R.string.tab_work_items, workItems.size)
    val prTab = stringResource(R.string.tab_pull_requests, prCount)
    val activityTab = if (unread > 0) stringResource(R.string.tab_activity_unread, unread) else stringResource(R.string.tab_activity)
    val panels = buildList<Pair<String, @Composable (Modifier) -> Unit>> {
        if (view.workItems.enabled) add(workTab to workPanel)
        if (view.pullRequests.enabled) add(prTab to prPanel)
        if (view.activity.enabled) add(activityTab to activityPanel)
    }

    // An expired session: the data stays readable, dimmed, until signing in again
    BoxWithConstraints(Modifier.fillMaxSize().then(if (status.dimmed) Modifier.alpha(DIMMED_ALPHA) else Modifier)) {
        val insets = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        if (maxWidth >= 840.dp) {
            Row(insets.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                panels.forEach { (_, panel) -> panel(Modifier.weight(1f).fillMaxHeight()) }
            }
        } else if (panels.isNotEmpty()) {
            var selectedTab by rememberSaveable { mutableIntStateOf(0) }
            val tab = selectedTab.coerceIn(0, panels.lastIndex)
            Column(Modifier.fillMaxSize()) {
                TabRow(
                    selectedTabIndex = tab, containerColor = c.bg, contentColor = c.primary,
                    indicator = { positions ->
                        TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[tab]), color = c.primary)
                    },
                    divider = {},
                ) {
                    panels.forEachIndexed { i, (label, _) ->
                        Tab(tab == i, { selectedTab = i }, unselectedContentColor = c.textMuted, text = {
                            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        })
                    }
                }
                panels[tab].second(insets.fillMaxSize().padding(12.dp))
            }
        }
    }
}

/**
 * The view's name as its selector (tapping it opens the switcher), with "org · user · updated
 * at" below, or "team board · updated at" for a board; refresh and settings on the right.
 */
@Composable
private fun DashboardBar(
    organization: String,
    view: DashboardView,
    state: SyncState,
    status: DashboardStatus,
    now: Instant,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onTitle: () -> Unit,
    onOpenPipelines: (project: String, team: String?) -> Unit,
    onOpenSprint: () -> Unit,
) {
    val c = AdoTheme.colors
    BoxWithConstraints {
        val dots = maxWidth >= 600.dp
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(top = 4.dp)) {
                val choose = stringResource(R.string.action_choose_view)
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = choose, role = Role.Button, onClick = onTitle)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        viewTitle(view), Modifier.weight(1f, fill = false).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.KeyboardArrowDown, choose, Modifier.size(24.dp), tint = c.textMuted)
                }
                val snapshot = state.snapshot
                // What is on screen is from then, also while a sync runs over what was saved
                val subtitle = when {
                    snapshot != null && view.board != null -> stringResource(R.string.dashboard_updated_at, updatedAt(snapshot.syncedAt, now))
                    snapshot != null -> stringResource(R.string.dashboard_synced, snapshot.me.name, updatedAt(snapshot.syncedAt, now))
                    status.content == PanelsContent.LOADING -> stringResource(R.string.dashboard_syncing)
                    else -> stringResource(R.string.dashboard_not_synced)
                }
                Text(
                    if (view.board != null) stringResource(R.string.view_board_subtitle, subtitle) else "$organization · $subtitle",
                    Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // A board's sprint: the global view has no tile, nor a team without a current sprint
            val snapshot = state.snapshot
            val sprint = snapshot?.shownFor(view)?.sprint?.takeIf { view.board != null }
            if (sprint != null) {
                Spacer(Modifier.width(12.dp))
                SprintTile(sprint, snapshot.me, now, onOpenSprint, compact = !dots)
            }
            // A board's builds: the global view has no tile, nor a board without readable builds
            val shown = snapshot?.takeIf { !it.buildsDenied }?.shownFor(view)
            val pipeline = shown?.pipeline.orEmpty()
            val project = shown?.pipelineProject
            if (project != null) {
                Spacer(Modifier.width(12.dp))
                PipelineTile(pipeline, { onOpenPipelines(project, view.board?.team) }, dots = dots)
                Spacer(Modifier.width(20.dp))
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
}

private fun LazyListScope.skeleton(description: String) = item(key = "skeleton") {
    SkeletonRows(Modifier.semantics { contentDescription = description })
}

/** A panel's empty state: its title and one line. */
private data class EmptyTexts(val title: String, val text: String)

/** Readable, but clearly not current. */
private const val DIMMED_ALPHA = 0.55f

private fun LazyListScope.emptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, texts: EmptyTexts) =
    item(key = "empty-state") {
        EmptyState(icon, texts.title, texts.text, Modifier.fillParentMaxSize())
    }

private fun LazyListScope.empty(text: String) = item(key = "empty-$text") {
    Text(
        text, Modifier.fillMaxWidth().padding(16.dp),
        style = MaterialTheme.typography.bodyMedium, color = AdoTheme.colors.textMuted,
    )
}
