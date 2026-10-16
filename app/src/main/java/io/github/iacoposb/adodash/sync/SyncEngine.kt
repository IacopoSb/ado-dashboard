package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.data.Filters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Duration
import java.time.Instant
import java.util.Collections

/** What the dashboard shows. */
data class Snapshot(
    val me: Identity,
    val workItems: List<WorkItem>,
    /** Active PRs where I am a reviewer, mine excluded. */
    val reviewPrs: List<PullRequest>,
    val myPrs: List<PullRequest>,
    val syncedAt: Instant,
    /** Projects that could not be read, with the reason. */
    val warnings: List<String> = emptyList(),
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
    private val now: () -> Instant = Instant::now,
    private val texts: SyncTexts,
) {

    /**
     * [since] is the previous sync; null on the first one, when comments are
     * skipped since nothing before it is notified anyway. What [filters] hide is left out
     * of everything, the events computed from the result included.
     */
    suspend fun fetch(me: Identity, since: Instant?, filters: Filters = Filters()): Fetched = coroutineScope {
        val limit = Semaphore(4)
        val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val assigned = async {
            if (filters.selectsNothing) return@async emptyList()
            // Filtered in the query, so the top 200 are all items to show, and here again
            // for what the query cannot express exactly (case, renamed areas)
            val items = api.workItems(api.queryWorkItemIds(assignedWiql(filters))).filter(filters::accepts)
            openOnly(items, warnings)
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

        val myPrs = prs.flatMap { it.second }.distinctBy { it.id }
            .filter(filters::acceptsMine).sortedByDescending { it.createdAt }
        val reviewPrs = prs.flatMap { it.first }.distinctBy { it.id }
            .filter { it.author.id != me.id && filters.acceptsReview(it, me) }.sortedByDescending { it.createdAt }

        val prComments = if (since == null) emptyMap() else (reviewPrs + myPrs).map { pr ->
            async { limit.withPermit { pr.id to runCatching { api.pullRequestComments(pr) }.getOrDefault(emptyList()) } }
        }.awaitAll().toMap()
        val mentionedComments = mentioned.await()

        Fetched(
            snapshot = Snapshot(
                me = me,
                workItems = assigned.await().sortedWith(compareBy<WorkItem>({ it.priority ?: 9 }).thenByDescending { it.changedAt }),
                reviewPrs = reviewPrs,
                myPrs = myPrs,
                syncedAt = now(),
                warnings = warnings.toList(),
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
        val keys = items.map { "${it.project}/${it.type}" }.distinct()
        keys.filter { it !in stateCategories }.forEach { key ->
            val (project, type) = key.split('/', limit = 2)
            runCatching { api.stateCategories(project, type) }
                .onSuccess { stateCategories[key] = it }
                .onFailure { warnings += texts.statesWarning(type, texts.error(it)) }
        }
        return items.filter { wi ->
            val category = stateCategories["${wi.project}/${wi.type}"]?.get(wi.state) ?: return@filter true
            category in OPEN_CATEGORIES
        }
    }

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
