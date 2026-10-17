package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.TeamArea
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.data.PrScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Duration
import java.time.Instant
import java.util.Collections

/**
 * What a sync read. [workItems], [reviewPrs] and [myPrs] are always mine, whatever the view:
 * the events and notifications come from them. What the current view shows is [shown].
 */
data class Snapshot(
    val me: Identity,
    /** My open work items. */
    val workItems: List<WorkItem>,
    /** Active PRs where I am a reviewer, mine excluded. */
    val reviewPrs: List<PullRequest>,
    val myPrs: List<PullRequest>,
    val syncedAt: Instant,
    /** Projects that could not be read, with the reason. */
    val warnings: List<String> = emptyList(),
    /** Null only for snapshots made before views (tests): then the view shows the above. */
    val shown: Shown? = null,
) {
    /** What the dashboard shows for [view]: null while the last sync read another view. */
    fun shownFor(view: DashboardView): Shown? = shown?.takeIf { it.viewKey == view.queryKey }
        ?: if (shown == null) Shown(view.queryKey, workItems, reviewPrs, myPrs) else null
}

/**
 * What the current view shows. Views change only what is on screen: the notifications stay
 * those of my own work items and pull requests (see [Snapshot]).
 */
data class Shown(
    /** The [DashboardView.queryKey] these were read for. */
    val viewKey: String,
    val workItems: List<WorkItem>,
    val reviewPrs: List<PullRequest>,
    val myPrs: List<PullRequest>,
    /** For views showing every PR in reach: those that are neither in [reviewPrs] nor in [myPrs]. */
    val otherPrs: List<PullRequest> = emptyList(),
)

/** A snapshot plus the comments needed to find new events. */
data class Fetched(
    val snapshot: Snapshot,
    val mentionedWorkItems: List<Pair<WorkItem, List<Comment>>> = emptyList(),
    val prComments: Map<Int, List<PrComment>> = emptyMap(),
)

/** Reads from Azure DevOps everything one sync needs. */
class SyncEngine(
    private val api: AdoApi,
    /** "project/type" to its state categories; kept by the caller across syncs. */
    private val stateCategories: MutableMap<String, Map<String, String>> = mutableMapOf(),
    /** Board to its team's area paths; kept by the caller across syncs. */
    private val teamAreas: MutableMap<BoardRef, List<TeamArea>> = mutableMapOf(),
    private val now: () -> Instant = Instant::now,
    private val texts: SyncTexts,
) {

    /**
     * [since] is the previous sync; null on the first one, when comments are
     * skipped since nothing before it is notified anyway. What [filters] hide is left out
     * of everything, the events computed from the result included. [view] only adds what it
     * shows ([Snapshot.shown]): my own work items and PRs are read for the notifications
     * whatever the view, and the view's data never reaches the events.
     */
    suspend fun fetch(
        me: Identity,
        since: Instant?,
        filters: Filters = Filters(),
        view: DashboardView = DashboardView.global,
    ): Fetched = coroutineScope {
        val limit = Semaphore(4)
        val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val needs = view.needs()
        val scope = view.board?.let { BoardScope(it.project, if (needs.teamAreas) areasOf(it, warnings) else emptyList()) }
        val panel = view.workItems

        val assigned = async {
            if (filters.selectsNothing) return@async emptyList()
            // Filtered in the query, so the top 200 are all items to show, and here again
            // for what the query cannot express exactly (case, renamed areas)
            val items = api.workItems(api.queryWorkItemIds(assignedWiql(filters))).filter(filters::accepts)
            openOnly(items, warnings)
        }
        // Someone else's work items, or closed ones: a query of the view's own
        val viewItems = async {
            if (!needs.workItemQuery || filters.selectsNothing) return@async null
            val items = api.workItems(api.queryWorkItemIds(ViewQuery.wiql(panel, filters, scope))).filter(filters::accepts)
            loadCategories(items, warnings)
            items
        }
        val mentioned = async {
            if (since == null || filters.selectsNothing) return@async emptyList()
            // @RecentMentions: work items where I was mentioned in the last 30 days
            val ids = runCatching { api.queryWorkItemIds(mentionsWiql(filters), top = 50) }
                .onFailure { warnings += texts.mentionsWarning(texts.error(it)) }
                .getOrDefault(emptyList())
            val changed = api.workItems(ids).filter { it.changedAt > since - OVERLAP && filters.accepts(it) }
            changed.map { wi -> async { limit.withPermit { wi to api.workItemComments(wi.project, wi.id) } } }.awaitAll()
        }

        val projects = api.projects().filter(filters::includesProject)
        val prs = projects.map { project ->
            async {
                limit.withPermit {
                    runCatching {
                        api.activePullRequests(project, PrFilter.Reviewer(me.id)) to
                            api.activePullRequests(project, PrFilter.Creator(me.id))
                    }.onFailure { warnings += texts.projectWarning(project, texts.error(it)) }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
        if (projects.isNotEmpty() && prs.isEmpty()) error(warnings.firstOrNull() ?: texts.noReadableProject())
        val allPrs = if (!needs.allPullRequests) emptyList() else projects.filter { p -> scope == null || p.equals(scope.project, ignoreCase = true) }.map { project ->
            async {
                limit.withPermit {
                    runCatching { api.activePullRequests(project, PrFilter.All) }
                        .onFailure { warnings += texts.projectWarning(project, texts.error(it)) }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten().distinctBy { it.id }

        val myPrs = prs.flatMap { it.second }.distinctBy { it.id }
            .filter(filters::acceptsMine).sortedByDescending { it.createdAt }
        val reviewPrs = prs.flatMap { it.first }.distinctBy { it.id }
            .filter { it.author.id != me.id && filters.acceptsReview(it, me) }.sortedByDescending { it.createdAt }

        val prComments = if (since == null) emptyMap() else (reviewPrs + myPrs).map { pr ->
            async { limit.withPermit { pr.id to runCatching { api.pullRequestComments(pr) }.getOrDefault(emptyList()) } }
        }.awaitAll().toMap()
        val mentionedComments = mentioned.await()
        val myItems = assigned.await().sortedWith(compareBy<WorkItem>({ it.priority ?: 9 }).thenByDescending { it.changedAt })

        val prPanel = view.pullRequests
        fun List<PullRequest>.forView(scopeOf: PrScope) =
            if (!prPanel.enabled || scopeOf !in prPanel.show) emptyList()
            else filter { (scope == null || scope.accepts(it)) && prPanel.accepts(it) }
        val shownReview = reviewPrs.forView(PrScope.TO_REVIEW)
        val shownMine = myPrs.forView(PrScope.MINE)
        val listed = (shownReview + shownMine).map { it.id }.toSet()
        val shown = Shown(
            viewKey = view.queryKey,
            workItems = if (!panel.enabled) emptyList() else panel.sort(
                (viewItems.await() ?: myItems).filter {
                    (scope == null || scope.accepts(it)) && panel.accepts(it, me, ::isClosed, checkAssignee = false)
                },
            ),
            reviewPrs = shownReview,
            myPrs = shownMine,
            otherPrs = allPrs.filter { it.id !in listed && filters.includesProject(it.project) }
                .forView(PrScope.ALL).sortedByDescending { it.createdAt },
        )

        Fetched(
            snapshot = Snapshot(
                me = me,
                workItems = myItems,
                reviewPrs = reviewPrs,
                myPrs = myPrs,
                syncedAt = now(),
                warnings = warnings.toList(),
                shown = shown,
            ),
            mentionedWorkItems = mentionedComments,
            prComments = prComments,
        )
    }

    /**
     * Only work not started or in progress: what is Resolved, Completed or
     * Removed goes, whatever the process calls its states. A type whose
     * states cannot be read keeps all its items.
     */
    private suspend fun openOnly(items: List<WorkItem>, warnings: MutableList<String>): List<WorkItem> {
        loadCategories(items, warnings)
        return items.filterNot(::isClosed)
    }

    private val categoriesLock = Mutex()

    private suspend fun loadCategories(items: List<WorkItem>, warnings: MutableList<String>) = categoriesLock.withLock {
        val keys = items.map { "${it.project}/${it.type}" }.distinct()
        keys.filter { it !in stateCategories }.forEach { key ->
            val (project, type) = key.split('/', limit = 2)
            runCatching { api.stateCategories(project, type) }
                .onSuccess { stateCategories[key] = it }
                .onFailure { warnings += texts.statesWarning(type, texts.error(it)) }
        }
    }

    /** Resolved, Completed or Removed, by category; a state of unknown category is open. */
    private fun isClosed(wi: WorkItem): Boolean {
        val category = stateCategories["${wi.project}/${wi.type}"]?.get(wi.state) ?: return false
        return category !in OPEN_CATEGORIES
    }

    /** Unreadable or not by area: the whole project, with a warning for the former. */
    private suspend fun areasOf(board: BoardRef, warnings: MutableList<String>): List<TeamArea> =
        teamAreas[board] ?: runCatching { api.teamAreas(board.project, board.team).orEmpty() }
            .onSuccess { teamAreas[board] = it }
            .onFailure { warnings += texts.projectWarning("${board.project} › ${board.team}", texts.error(it)) }
            .getOrDefault(emptyList())

    companion object {
        val OPEN_CATEGORIES = setOf("Proposed", "InProgress")

        /** Late-indexed changes still get caught by the next sync. */
        private val OVERLAP = Duration.ofMinutes(10)

        private val CLOSED_STATES = listOf("Closed", "Done", "Removed", "Completed", "Cut")
            .joinToString(",") { "'$it'" }

        fun assignedWiql(filters: Filters = Filters()) = """
            SELECT [System.Id] FROM WorkItems
            WHERE [System.AssignedTo] = @Me AND [System.State] NOT IN ($CLOSED_STATES)${filters.wiqlConditions()}
            ORDER BY [System.ChangedDate] DESC
        """.trimIndent()

        fun mentionsWiql(filters: Filters = Filters()) = """
            SELECT [System.Id] FROM WorkItems
            WHERE [System.Id] IN (@RecentMentions)${filters.wiqlConditions()}
            ORDER BY [System.ChangedDate] DESC
        """.trimIndent()
    }
}
