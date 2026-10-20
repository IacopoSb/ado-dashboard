package io.github.iacoposb.adodash.data

import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.WorkItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** What a work item condition looks at. */
@Serializable
enum class WiField { TYPE, ASSIGNED_TO, STATE }

/**
 * One condition of the work items panel; a panel's conditions combine with AND. [values] are
 * the types or states the condition lists (with [CLOSED] for every closed state), or for
 * [WiField.ASSIGNED_TO] a single [ME], [ANYONE] or a person's display name. [negate] reads
 * "is not one of" and only applies to types and states. No values: no constraint.
 */
@Serializable
data class Condition(val field: WiField, val negate: Boolean = false, val values: List<String> = emptyList()) {

    /**
     * Whether [wi] meets the condition. [closed] says whether the work item is in a closed
     * state (Resolved, Completed or Removed category), which only the sync knows. Without
     * [checkAssignee] assignments always pass: the query already chose them, by identity
     * rather than by display name.
     */
    fun accepts(wi: WorkItem, me: Identity, closed: (WorkItem) -> Boolean, checkAssignee: Boolean = true): Boolean {
        if (values.isEmpty()) return true
        return when (field) {
            WiField.ASSIGNED_TO -> !checkAssignee || when (val who = values.first()) {
                ANYONE -> true
                ME -> wi.assignedTo.equals(me.name, ignoreCase = true)
                else -> wi.assignedTo.equals(who, ignoreCase = true)
            }
            WiField.TYPE -> values.any { it.equals(wi.type, ignoreCase = true) } != negate
            WiField.STATE -> values.any { v ->
                if (v == CLOSED) closed(wi) else v.equals(wi.state, ignoreCase = true)
            } != negate
        }
    }

    companion object {
        const val ME = "@me"
        const val ANYONE = "@anyone"
        /** Every state of the Resolved, Completed and Removed categories, whatever their name. */
        const val CLOSED = "@closed"

        fun assignedTo(who: String) = Condition(WiField.ASSIGNED_TO, values = listOf(who))
        val notClosed = Condition(WiField.STATE, negate = true, values = listOf(CLOSED))
    }
}

@Serializable
enum class WiOrder {
    /** Highest priority first, then most recently changed: as before views existed. */
    PRIORITY,
    CHANGED,
}

@Serializable
data class WorkItemsPanel(
    val enabled: Boolean = true,
    val conditions: List<Condition> = listOf(Condition.assignedTo(Condition.ME), Condition.notClosed),
    val order: WiOrder = WiOrder.PRIORITY,
) {
    fun accepts(wi: WorkItem, me: Identity, closed: (WorkItem) -> Boolean, checkAssignee: Boolean = true) =
        conditions.all { it.accepts(wi, me, closed, checkAssignee) }

    fun sort(items: List<WorkItem>): List<WorkItem> = when (order) {
        WiOrder.PRIORITY -> items.sortedWith(compareBy<WorkItem>({ it.priority ?: 9 }).thenByDescending { it.changedAt })
        WiOrder.CHANGED -> items.sortedByDescending { it.changedAt }
    }

    /** The assignee of the last assignment condition with a value; [Condition.ANYONE] without one. */
    val assignee: String
        get() = conditions.lastOrNull { it.field == WiField.ASSIGNED_TO && it.values.isNotEmpty() }?.values?.first()
            ?: Condition.ANYONE

    /**
     * Everything this panel shows is among my work items that are not closed, which every sync
     * reads anyway for the notifications: those are filtered, no other query is needed.
     */
    val withinMine: Boolean
        get() = assignee == Condition.ME && conditions.any {
            it.field == WiField.STATE && it.negate && Condition.CLOSED in it.values
        }
}

@Serializable
enum class PrScope { TO_REVIEW, MINE, ALL }

@Serializable
data class PullRequestsPanel(
    val enabled: Boolean = true,
    val show: Set<PrScope> = setOf(PrScope.TO_REVIEW, PrScope.MINE),
    /** Repository names; empty for all of them, or for none on a board ([chosenOnly]). */
    val repositories: Set<String> = emptySet(),
    val drafts: Boolean = true,
) {
    /**
     * Whether [repo] is among the panel's repositories. None chosen is all of them, except
     * with [chosenOnly] (a board, whose repositories are chosen by hand): then it is none.
     */
    fun includesRepository(repo: String, chosenOnly: Boolean = false): Boolean =
        if (repositories.isEmpty()) !chosenOnly else repositories.any { it.equals(repo, ignoreCase = true) }

    fun accepts(pr: PullRequest, chosenOnly: Boolean = false): Boolean =
        (drafts || !pr.draft) && includesRepository(pr.repoName, chosenOnly)
}

/** The kinds of activity a view can show. */
@Serializable
enum class ActivityKind {
    MENTIONS, ASSIGNMENTS, VOTES, COMMENTS, REVIEW_REQUESTS,
    /** Builds failed on my pull requests; on in the views saved before it was offered. */
    BUILDS_FAILED,
    ;

    companion object {
        /** What the editor offers. */
        val offered = entries.toList()

        fun of(kind: EventKind) = when (kind) {
            EventKind.MENTION -> MENTIONS
            EventKind.ASSIGNED -> ASSIGNMENTS
            EventKind.PR_VOTE -> VOTES
            EventKind.PR_COMMENT -> COMMENTS
            EventKind.PR_REVIEW -> REVIEW_REQUESTS
            EventKind.BUILD_FAILED -> BUILDS_FAILED
        }
    }
}

@Serializable
data class ActivityPanel(val enabled: Boolean = true, val kinds: Set<ActivityKind> = ActivityKind.entries.toSet()) {
    val showsAll get() = kinds.containsAll(ActivityKind.offered)
}

/** A team's board in a project. */
@Serializable
data class BoardRef(val project: String, val team: String) {
    val viewId get() = "$BOARD_PREFIX$project/$team"

    companion object {
        const val BOARD_PREFIX = "board:"
    }
}

/**
 * What the dashboard shows. A saved view has a name and its own panels; a board view
 * ([board] not null) shows the same panels limited to the team's area paths, the
 * repositories chosen for it (none until chosen) and the project's activity. The global filters always apply first: a view only
 * narrows what they include (or reads more of it, such as other people's work items).
 */
@Serializable
data class DashboardView(
    val id: String,
    /** Empty for the global view's own localized name. */
    val name: String = "",
    val board: BoardRef? = null,
    val workItems: WorkItemsPanel = WorkItemsPanel(),
    val pullRequests: PullRequestsPanel = PullRequestsPanel(),
    val activity: ActivityPanel = ActivityPanel(),
) {
    val isGlobal get() = id == GLOBAL_ID

    /** Something to show: a view with every panel off cannot be saved. */
    val showsSomething get() = workItems.enabled || pullRequests.enabled || activity.enabled

    /** What the sync reads for this view; the activity only filters stored events. */
    val queryKey: String get() = json.encodeToString(serializer(), copy(id = "", name = "", activity = ActivityPanel()))

    /**
     * A board whose repositories are still to be chosen: it shows no pull requests nor builds,
     * rather than the whole project's.
     */
    val needsRepositories: Boolean get() = board != null && pullRequests.repositories.isEmpty()

    /** A repository whose pull requests and builds this view shows: on a board, only the chosen ones. */
    fun acceptsRepository(repo: String): Boolean = pullRequests.includesRepository(repo, chosenOnly = board != null)

    /** A pull request the panel shows, as far as its repository and drafts go. */
    fun acceptsPullRequest(pr: PullRequest): Boolean = pullRequests.accepts(pr, chosenOnly = board != null)

    /** The project's activity for a board view, any for the others, both by [activity]'s kinds. */
    fun acceptsEvent(event: Event): Boolean {
        if (!activity.kinds.contains(ActivityKind.of(event.kind))) return false
        return board == null || event.project.equals(board.project, ignoreCase = true)
    }

    fun needs() = ViewNeeds(
        workItemQuery = workItems.enabled && !workItems.withinMine,
        allPullRequests = pullRequests.enabled && PrScope.ALL in pullRequests.show && !needsRepositories,
        teamAreas = board != null && workItems.enabled,
    )

    companion object {
        const val GLOBAL_ID = "global"

        /** As the dashboard was before views: my open work items, PRs to review and mine, all activity. */
        val global = DashboardView(GLOBAL_ID)

        fun newSaved(name: String) = DashboardView(UUID.randomUUID().toString(), name)

        /** A board seen for the first time: the global view's panels, limited to the board. */
        fun forBoard(board: BoardRef) = DashboardView(board.viewId, board = board)

        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** The extra reads a view needs on top of what every sync reads for the notifications. */
data class ViewNeeds(
    /** Work items other than my open ones: a WIQL query of their own. */
    val workItemQuery: Boolean,
    /** Every active PR of the repositories in reach, not only reviews and mine; none for a board without them. */
    val allPullRequests: Boolean,
    /** The team's area paths (Team field values). */
    val teamAreas: Boolean,
)

/**
 * The views of one organization (another one starts from the defaults), the one last opened
 * and whether it opens at launch without asking.
 */
@Serializable
data class ViewSet(
    val views: List<DashboardView> = listOf(DashboardView.global),
    val currentId: String = DashboardView.GLOBAL_ID,
    /** "Always open this view": no "What do you want to see?" at launch. */
    val alwaysOpen: Boolean = false,
    /** The launch screen was answered once: before that it suggests "Always open this view". */
    val chosenAtLaunch: Boolean = false,
    /** The boards last opened, most recent first, at most [RECENT_BOARDS]. */
    val recentBoards: List<BoardRef> = emptyList(),
) {
    /** The global view first, whatever was stored. */
    private val all: List<DashboardView> =
        if (views.any { it.isGlobal }) views.sortedBy { !it.isGlobal } else listOf(DashboardView.global) + views

    val current: DashboardView get() = all.firstOrNull { it.id == currentId } ?: global

    val global: DashboardView get() = all.first { it.isGlobal }

    /** "My views": the global view and the saved ones, boards excluded. */
    val saved: List<DashboardView> get() = all.filter { it.board == null }

    /** A board as last edited, or its defaults. */
    fun board(ref: BoardRef): DashboardView = all.firstOrNull { it.board == ref } ?: DashboardView.forBoard(ref)

    fun find(id: String): DashboardView? = all.firstOrNull { it.id == id }

    /** Adds or replaces [view] and opens it. */
    fun put(view: DashboardView): ViewSet {
        val replaced = all.any { it.id == view.id }
        return copy(
            views = if (replaced) all.map { if (it.id == view.id) view else it } else all + view,
            currentId = view.id,
            recentBoards = withRecent(view.board),
        )
    }

    fun open(id: String) = copy(views = all, currentId = id, recentBoards = withRecent(find(id)?.board))

    private fun withRecent(board: BoardRef?): List<BoardRef> =
        if (board == null) recentBoards else (listOf(board) + recentBoards.filterNot { it == board }).take(RECENT_BOARDS)

    /** A board is stored once opened, so that it can be the one opened at launch. */
    fun openBoard(ref: BoardRef) = put(board(ref))

    /** The global view stays: it can only be edited. Deleting the open view opens the global one. */
    fun delete(id: String): ViewSet {
        if (id == DashboardView.GLOBAL_ID) return this
        return copy(views = all.filterNot { it.id == id }, currentId = if (currentId == id) DashboardView.GLOBAL_ID else currentId)
    }

    fun encode(): String = DashboardView.json.encodeToString(serializer(), copy(views = all))

    companion object {
        const val RECENT_BOARDS = 3

        /** Nothing stored (before 0.0.6, or another organization): the global view only. */
        fun decode(text: String?): ViewSet =
            text?.let { runCatching { DashboardView.json.decodeFromString(serializer(), it) }.getOrNull() } ?: ViewSet()
    }
}
