@file:UseSerializers(InstantSerializer::class)

package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.data.InstantSerializer
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.Serializable
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.Build
import io.github.iacoposb.adodash.api.BuildState
import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PrStatus
import io.github.iacoposb.adodash.api.PrThread
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a sync read. [workItems], [reviewPrs] and [myPrs] are always mine, whatever the view:
 * the events and notifications come from them. What the current view shows is [shown].
 */
@Serializable
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
    /** Build, threads, conflicts and readiness by pull request id, for every PR in [shown]. */
    val signals: Map<Int, PrSignals> = emptyMap(),
    /** The credentials cannot read builds (a PAT without Build (read)): no pipeline anywhere. */
    val buildsDenied: Boolean = false,
) {
    /** What the dashboard shows for [view]: null while the last sync read another view. */
    fun shownFor(view: DashboardView): Shown? = shown?.takeIf { it.viewKey == view.queryKey }
        ?: if (shown == null) Shown(view.queryKey, workItems, reviewPrs, myPrs) else null

    /**
     * [item] in place of the work item with its id, wherever it is listed; in the sprint with
     * [sprintChange] applied too.
     */
    fun replacing(item: WorkItem, sprintChange: (SprintItem) -> SprintItem = { it.copy(item = item) }): Snapshot {
        fun List<WorkItem>.swap() = map { if (it.id == item.id) item else it }
        return copy(
            workItems = workItems.swap(),
            shown = shown?.copy(workItems = shown.workItems.swap(), sprint = shown.sprint?.replacing(item.id, sprintChange)),
        )
    }

    /** My [vote] on pull request [prId] wherever it is listed: added as a reviewer if I was not one. */
    fun withMyVote(prId: Int, vote: Int): Snapshot {
        fun List<PullRequest>.swap() = map { pr ->
            if (pr.id != prId) return@map pr
            val reviewers = if (pr.reviewers.any { it.id.equals(me.id, ignoreCase = true) }) {
                pr.reviewers.map { if (it.id.equals(me.id, ignoreCase = true)) it.copy(vote = vote) else it }
            } else {
                pr.reviewers + io.github.iacoposb.adodash.api.Reviewer(me.id, me.name, vote, required = false)
            }
            pr.copy(reviewers = reviewers)
        }
        return copy(
            reviewPrs = reviewPrs.swap(), myPrs = myPrs.swap(),
            shown = shown?.copy(reviewPrs = shown.reviewPrs.swap(), myPrs = shown.myPrs.swap(), otherPrs = shown.otherPrs.swap()),
        )
    }

    /** A pull request listed anywhere, for its page. */
    fun pullRequest(id: Int): PullRequest? =
        (reviewPrs + myPrs + shown?.let { it.reviewPrs + it.myPrs + it.otherPrs }.orEmpty()).firstOrNull { it.id == id }
}

/**
 * What the current view shows. Views change only what is on screen: the notifications stay
 * those of my own work items and pull requests (see [Snapshot]).
 */
@Serializable
data class Shown(
    /** The [DashboardView.queryKey] these were read for. */
    val viewKey: String,
    val workItems: List<WorkItem>,
    val reviewPrs: List<PullRequest>,
    val myPrs: List<PullRequest>,
    /** For views showing every PR in reach: those that are neither in [reviewPrs] nor in [myPrs]. */
    val otherPrs: List<PullRequest> = emptyList(),
    /** The pipeline tile's builds, most recent first (see [Pipelines.tile]). */
    val pipeline: List<Build> = emptyList(),
    /**
     * The project whose pipelines the tile lists: a board's, when its builds could be read and
     * it has some. Null hides the tile, as in the global view.
     */
    val pipelineProject: String? = null,
    /** A board's current sprint with its backlog; null for other views or a team without one. */
    val sprint: Sprint? = null,
)

/** A snapshot plus the comments needed to find new events. */
data class Fetched(
    val snapshot: Snapshot,
    val mentionedWorkItems: List<Pair<WorkItem, List<Comment>>> = emptyList(),
    val prComments: Map<Int, List<PrComment>> = emptyMap(),
    /** Failed builds of my pull requests: all of them on the first sync, then those finished since the last. */
    val failedPrBuilds: List<FailedPrBuild> = emptyList(),
    /** [Snapshot.workItems] holds all my work items the filters show: none left out by a failed query. */
    val workItemsComplete: Boolean = false,
    /** The projects whose pull requests were read: in the others, a review missing proves nothing. */
    val prProjectsRead: Set<String> = emptySet(),
    /** Every pull request I am a reviewer of in [prProjectsRead], whatever the filters show. */
    val reviewerPrIds: Set<Int> = emptySet(),
)

/** A failed build of one of my pull requests, with its failed tests when they could be counted. */
data class FailedPrBuild(val pr: PullRequest, val build: Build, val failedTests: Int? = null)

/** Reads from Azure DevOps everything one sync needs. */
class SyncEngine(
    private val api: AdoApi,
    /** "project/type" to its state categories; kept by the caller across syncs. */
    private val stateCategories: MutableMap<String, Map<String, String>> = mutableMapOf(),
    /** Board to its team's area paths; kept by the caller across syncs. */
    private val teamAreas: MutableMap<BoardRef, List<TeamArea>> = mutableMapOf(),
    private val now: () -> Instant = Instant::now,
    private val texts: SyncTexts,
    /** Policies, threads, branches and test counts; kept by the caller across syncs. */
    private val cache: SignalCache = SignalCache(),
    /** The saved filters' catalog, to tell the areas that no longer exist; null when none is saved. */
    private val catalog: suspend () -> FilterCatalog? = { null },
) {
    private val catalogLock = Mutex()
    private var catalogRead = false
    private var savedCatalog: FilterCatalog? = null
    private val missingAreaWarned = AtomicBoolean(false)

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
        val scope = view.board?.let {
            BoardScope(it.project, if (needs.teamAreas) areasOf(it, warnings) else emptyList(), view.pullRequests.repositories)
        }
        val panel = view.workItems

        val assignedComplete = AtomicBoolean(false)
        val assigned = async {
            if (filters.selectsNothing) return@async emptyList()
            // Filtered in the query, so the top 200 are all items to show, and here again
            // for what the query cannot express exactly (case, renamed areas)
            val ids = queryIds(filters, warnings) { assignedWiql(it) }
            assignedComplete.set(ids != null)
            val items = api.workItems(ids.orEmpty()).filter(filters::accepts)
            openOnly(items, warnings)
        }
        // Someone else's work items, or closed ones: a query of the view's own
        val viewItems = async {
            if (!needs.workItemQuery || filters.selectsNothing) return@async null
            val ids = queryIds(filters, warnings) { ViewQuery.wiql(panel, it, scope) }.orEmpty()
            val items = api.workItems(ids).filter(filters::accepts)
            loadCategories(items, warnings)
            items
        }
        // A board's sprint: its own reads, whatever the panels show
        val sprintRead = async { view.board?.let { readSprint(it, warnings, limit) } }
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
        val firstFailure = AtomicReference<Throwable?>(null)
        val prsByProject = projects.map { project ->
            async {
                limit.withPermit {
                    project to runCatching {
                        api.activePullRequests(project, PrFilter.Reviewer(me.id)) to
                            api.activePullRequests(project, PrFilter.Creator(me.id))
                    }.onFailure {
                        if (it is CancellationException) throw it
                        firstFailure.compareAndSet(null, it)
                        warnings += texts.projectWarning(project, texts.error(it))
                    }.getOrNull()
                }
            }
        }.awaitAll()
        val prs = prsByProject.mapNotNull { it.second }
        // Why, for the dashboard: no connection or credentials refused are not a project's fault
        if (projects.isNotEmpty() && prs.isEmpty()) throw SyncException(warnings.firstOrNull() ?: texts.noReadableProject(), firstFailure.get())
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

        val prPanel = view.pullRequests
        fun List<PullRequest>.forView(scopeOf: PrScope) =
            if (!prPanel.enabled || scopeOf !in prPanel.show) emptyList()
            else filter { (scope == null || scope.accepts(it)) && view.acceptsPullRequest(it) }
        val shownReview = reviewPrs.forView(PrScope.TO_REVIEW)
        val shownMine = myPrs.forView(PrScope.MINE)
        val listed = (shownReview + shownMine).map { it.id }.toSet()
        val shownOthers = allPrs.filter { it.id !in listed && filters.includesProject(it.project) }
            .forView(PrScope.ALL).sortedByDescending { it.createdAt }

        // My PRs' projects for the notifications, whatever the view, and the projects of the
        // other PRs with signals; a board's project too for its tile (the global view has none,
        // nor a board whose repositories are still to be chosen)
        val tracked = (reviewPrs + myPrs).distinctBy { it.id }
        val boardProject = scope?.takeIf { !view.needsRepositories }
            ?.let { s -> projects.firstOrNull { it.equals(s.project, ignoreCase = true) } }
        val buildProjects = (listOfNotNull(boardProject) + (tracked + shownOthers).map { it.project }).distinctBy { it.lowercase() }
        val buildsRead = async { readBuilds(buildProjects, limit, branchesOf = boardProject) }
        // My PRs' and my reviews' threads: read at every sync, for the comments and the active ones
        val trackedThreads = tracked.map { pr ->
            async { limit.withPermit { pr.id to runCatching { api.pullRequestThreads(pr) }.getOrNull() } }
        }.awaitAll().toMap()
        // Nothing before the first sync is notified anyway
        val prComments = if (since == null) emptyMap() else tracked.associate { pr ->
            pr.id to trackedThreads[pr.id].orEmpty().flatMap { it.comments }
        }
        val mentionedComments = mentioned.await()
        val myItems = assigned.await().sortedWith(compareBy<WorkItem>({ it.priority ?: 9 }).thenByDescending { it.changedAt })

        val (builds, buildsDenied) = buildsRead.await()
        val trackedIds = tracked.map { it.id }.toSet()
        val threads = trackedThreads + shownOthers.filter { it.id !in trackedIds }
            .map { pr -> async { pr.id to otherThreads(pr, limit) } }.awaitAll()
        val signalPrs = (tracked + shownOthers).distinctBy { it.id }
        // Statuses posted by services only for the rows shown
        val shownIds = (shownReview + shownMine + shownOthers).map { it.id }.toSet()
        val signals = signalPrs.map { pr ->
            async {
                val statuses = async { if (pr.id in shownIds) statuses(pr, limit) else null }
                pr.id to Signals.of(pr, policies(pr, threads[pr.id], limit), threads[pr.id], builds, statuses.await())
            }
        }.awaitAll().toMap()

        // Only a board has a tile: its project's builds
        val pipelineProject = boardProject?.takeIf { p -> !buildsDenied && builds.any { it.project.equals(p, ignoreCase = true) } }
        val pipeline = if (scope == null || pipelineProject == null) emptyList() else Pipelines.tile(
            builds, myPrs.filter { scope.accepts(it) },
            cache.defaultBranches[pipelineProject].orEmpty(),
            acceptsRepo = { b ->
                filters.includesProject(b.project) && b.project.equals(pipelineProject, ignoreCase = true) &&
                    scope.acceptsRepository(b.repoName)
            },
        )
        val failedPrBuilds = failedBuildsOfMine(builds, myPrs, since, limit)

        val shown = Shown(
            viewKey = view.queryKey,
            workItems = if (!panel.enabled) emptyList() else panel.sort(
                (viewItems.await() ?: myItems).filter {
                    (scope == null || scope.accepts(it)) && panel.accepts(it, me, ::isClosed, checkAssignee = false)
                },
            ),
            reviewPrs = shownReview,
            myPrs = shownMine,
            otherPrs = shownOthers,
            pipeline = pipeline,
            pipelineProject = pipelineProject,
            sprint = sprintRead.await(),
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
                signals = signals,
                buildsDenied = buildsDenied,
            ),
            mentionedWorkItems = mentionedComments,
            prComments = prComments,
            failedPrBuilds = failedPrBuilds,
            workItemsComplete = assignedComplete.get(),
            prProjectsRead = prsByProject.filter { it.second != null }.map { it.first }.toSet(),
            reviewerPrIds = prs.flatMap { it.first }.filter { it.author.id != me.id }.map { it.id }.toSet(),
        )
    }

    /**
     * The ids [wiql] finds with [filters]. A saved area that was renamed or deleted makes Azure
     * DevOps refuse the query (HTTP 400, TF51011): then it runs again without the areas the saved
     * catalog does not know, and if that cannot be done or fails as well it finds nothing (null),
     * with a warning, so that the pull requests and the pipelines still show.
     */
    private suspend fun queryIds(filters: Filters, warnings: MutableList<String>, wiql: (Filters) -> String): List<Int>? {
        try {
            return api.queryWorkItemIds(wiql(filters))
        } catch (e: AdoException) {
            if (e.code != 400 || filters.areas.isNullOrEmpty()) throw e
        }
        fun warn() {
            if (missingAreaWarned.compareAndSet(false, true)) warnings += texts.missingAreaWarning()
        }
        val known = savedCatalog()?.let { filters.withoutAreasMissingFrom(it.projects) }
        if (known != null && known != filters) {
            warn()
            if (known.selectsNothing) return emptyList()
            try {
                return api.queryWorkItemIds(wiql(known))
            } catch (e: AdoException) {
                if (e.code != 400) throw e
            }
        }
        warn()
        return null
    }

    private suspend fun savedCatalog(): FilterCatalog? = catalogLock.withLock {
        if (!catalogRead) {
            savedCatalog = try {
                catalog()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            catalogRead = true
        }
        savedCatalog
    }

    /**
     * The latest builds of [projects] and whether builds are refused. A refusal is remembered for
     * [BUILDS_RETRY], so that a PAT without Build (read) costs one failed call now and then.
     * The default branches of [branchesOf] are read too, once, for its tile.
     */
    private suspend fun readBuilds(
        projects: List<String>,
        limit: Semaphore,
        branchesOf: String?,
    ): Pair<List<Build>, Boolean> = coroutineScope {
        val deniedAt = cache.buildsDeniedAt
        if (deniedAt != null && Duration.between(deniedAt, now()) < BUILDS_RETRY) return@coroutineScope emptyList<Build>() to true
        if (projects.isEmpty()) return@coroutineScope emptyList<Build>() to false
        val refused = AtomicBoolean(false)
        val builds = projects.map { project ->
            async {
                limit.withPermit {
                    try {
                        api.builds(project)
                    } catch (e: AdoException) {
                        if (e.code == 401 || e.code == 403) refused.set(true)
                        emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // One project's builds missing only leaves them out of the tile
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()
        if (refused.get() && builds.isEmpty()) {
            cache.buildsDeniedAt = now()
            return@coroutineScope emptyList<Build>() to true
        }
        cache.buildsDeniedAt = null
        // Default branches change seldom: read once per project
        if (branchesOf != null && !cache.defaultBranches.containsKey(branchesOf) &&
            builds.any { it.project.equals(branchesOf, ignoreCase = true) }
        ) {
            limit.withPermit {
                runCatching { api.defaultBranches(branchesOf) }.onSuccess { cache.defaultBranches[branchesOf] = it }
            }
        }
        builds to false
    }

    /**
     * The team's current sprint with its work items: null when it has none, or when it cannot
     * be read (with a warning). The team's working days and its days off in the sprint are
     * read once and kept; without them the days left count Monday to Friday.
     */
    private suspend fun readSprint(board: BoardRef, warnings: MutableList<String>, limit: Semaphore): Sprint? = coroutineScope {
        val (project, team) = board
        val warn = { e: Throwable -> warnings += texts.projectWarning("$project › $team", texts.error(e)) }
        val iteration = limit.withPermit { runCatching { api.currentIteration(project, team) } }
            .onFailure(warn).getOrNull() ?: return@coroutineScope null
        val teamKey = "$project/$team"
        val days = async {
            cache.workingDays[teamKey] ?: limit.withPermit { runCatching { api.teamWorkingDays(project, team) }.getOrNull() }
                ?.also { cache.workingDays[teamKey] = it }
        }
        val daysOffKey = "$teamKey/${iteration.id}"
        val daysOff = async {
            cache.daysOff[daysOffKey] ?: limit.withPermit { runCatching { api.teamDaysOff(project, team, iteration.id) }.getOrNull() }
                ?.also { cache.daysOff[daysOffKey] = it }
        }
        val items = runCatching {
            val links = limit.withPermit { api.iterationWorkItems(project, team, iteration.id) }
            val read = if (links.isEmpty()) emptyList() else limit.withPermit { api.backlogItems(links.map { it.id }) }
            links to read
        }.onFailure(warn).getOrNull() ?: return@coroutineScope null
        val (links, read) = items
        loadCategories(read.map { it.item }, warnings)
        val sprintItems = read.map { b ->
            SprintItem(b.item, b.assigneeId, b.remainingWork, b.rank, categoryOf(b.item))
        }
        // The sprint's own hierarchy first, the parent field for what it leaves out
        val fromField = read.mapNotNull { b -> b.parentId?.let { b.item.id to it } }.toMap()
        val parents = fromField + links.mapNotNull { l -> l.parentId?.let { l.id to it } }.toMap()
        Sprint(
            board, iteration, Backlog.build(sprintItems, parents),
            workingDays = days.await() ?: Sprint.DEFAULT_WORKING_DAYS,
            daysOff = daysOff.await().orEmpty(),
        )
    }

    /** Threads of a PR neither mine nor to review: only counted, so read again only now and then. */
    private suspend fun otherThreads(pr: PullRequest, limit: Semaphore): List<PrThread>? {
        val key = pr.lastCommit.orEmpty()
        cache.threads[pr.id]?.takeIf { it.key == key && Duration.between(it.at, now()) < SIGNALS_TTL }?.let { return it.threads }
        return limit.withPermit { runCatching { api.pullRequestThreads(pr) }.getOrNull() }
            ?.also { cache.threads[pr.id] = SignalCache.Threads(key, it, now()) }
            ?: cache.threads[pr.id]?.threads
    }

    /**
     * A PR's policies, read again only when its commit, votes or active threads changed, when a
     * policy was still running, or after [SIGNALS_TTL]; the last ones read if they cannot be.
     */
    private suspend fun policies(pr: PullRequest, threads: List<PrThread>?, limit: Semaphore): List<PolicyEvaluation>? {
        val votes = pr.reviewers.sortedBy { it.id }.joinToString(",") { "${it.id}=${it.vote}" }
        val key = "${pr.lastCommit}|$votes|${threads?.count { it.active }}"
        val cached = cache.policies[pr.id]
        if (cached != null && cached.key == key && !Signals.pending(cached.evaluations) &&
            Duration.between(cached.at, now()) < SIGNALS_TTL
        ) return cached.evaluations
        return limit.withPermit { runCatching { api.policyEvaluations(pr) }.getOrNull() }
            ?.also { cache.policies[pr.id] = SignalCache.Policies(key, it, now()) }
            ?: cached?.evaluations
    }

    /**
     * The statuses services posted on a PR, read again only when its commit changed, when one
     * was still pending, or after [SIGNALS_TTL]; the last ones read if they cannot be.
     */
    private suspend fun statuses(pr: PullRequest, limit: Semaphore): List<PrStatus>? {
        val key = pr.lastCommit.orEmpty()
        val cached = cache.statuses[pr.id]
        if (cached != null && cached.key == key && !Signals.pendingStatuses(cached.statuses) &&
            Duration.between(cached.at, now()) < SIGNALS_TTL
        ) return cached.statuses
        return limit.withPermit { runCatching { api.pullRequestStatuses(pr) }.getOrNull() }
            ?.also { cache.statuses[pr.id] = SignalCache.Statuses(key, it, now()) }
            ?: cached?.statuses
    }

    /**
     * My PRs' failed builds: all of them on the first sync (history, never notified), then those
     * finished since the last sync, with their failed tests counted (once per build).
     */
    private suspend fun failedBuildsOfMine(
        builds: List<Build>,
        myPrs: List<PullRequest>,
        since: Instant?,
        limit: Semaphore,
    ): List<FailedPrBuild> = coroutineScope {
        val mine = myPrs.associateBy { it.id }
        builds.filter { b ->
            b.state == BuildState.FAILED && b.prId != null && mine[b.prId]?.let { b.repoId.isEmpty() || it.repoId == b.repoId } == true &&
                (since == null || b.at > since - OVERLAP)
        }.map { b ->
            async {
                val pr = mine.getValue(b.prId!!)
                if (since == null) return@async FailedPrBuild(pr, b)
                val failed = cache.failedTests[b.id] ?: limit.withPermit {
                    runCatching { api.testRuns(b.project, b.id).sumOf { it.failed } }.getOrNull()
                }?.also { cache.failedTests[b.id] = it }
                FailedPrBuild(pr, b, failed)
            }
        }.awaitAll()
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
        val category = categoryOf(wi) ?: return false
        return category !in OPEN_CATEGORIES
    }

    private fun categoryOf(wi: WorkItem): String? = categoryOf(stateCategories, wi)

    /** Unreadable or not by area: the whole project, with a warning for the former. */
    private suspend fun areasOf(board: BoardRef, warnings: MutableList<String>): List<TeamArea> =
        teamAreas[board] ?: runCatching { api.teamAreas(board.project, board.team).orEmpty() }
            .onSuccess { teamAreas[board] = it }
            .onFailure { warnings += texts.projectWarning("${board.project} › ${board.team}", texts.error(it)) }
            .getOrDefault(emptyList())

    companion object {
        val OPEN_CATEGORIES = setOf("Proposed", "InProgress")

        /** The category of [wi]'s state, from "project/type" to state to category. */
        fun categoryOf(categories: Map<String, Map<String, String>>, wi: WorkItem): String? =
            categories["${wi.project}/${wi.type}"]?.get(wi.state)

        /** Late-indexed changes still get caught by the next sync. */
        private val OVERLAP = Duration.ofMinutes(10)

        /** Policies and other people's threads of an unchanged PR are read again after this. */
        val SIGNALS_TTL: Duration = Duration.ofMinutes(10)

        /** Builds refused: tried again after this. */
        val BUILDS_RETRY: Duration = Duration.ofHours(1)

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
