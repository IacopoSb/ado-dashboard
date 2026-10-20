package io.github.iacoposb.adodash.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.ActivityKind
import io.github.iacoposb.adodash.data.Condition
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.data.PrScope
import io.github.iacoposb.adodash.data.ViewSet
import io.github.iacoposb.adodash.data.WiField
import io.github.iacoposb.adodash.data.WorkItemsPanel
import io.github.iacoposb.adodash.data.PullRequestsPanel
import io.github.iacoposb.adodash.data.ActivityPanel
import io.github.iacoposb.adodash.sync.CatalogState
import io.github.iacoposb.adodash.sync.FilterCatalog
import io.github.iacoposb.adodash.sync.Snapshot
import io.github.iacoposb.adodash.sync.TeamInfo
import io.github.iacoposb.adodash.ui.components.AdoIcons

/** A project's teams, as the pickers list them. */
data class ProjectBoards(val project: String, val teams: List<TeamInfo>)

/** What the editor's dropdowns offer, besides "Me", "Anyone" and "Closed states". */
data class EditorOptions(
    val types: List<String> = emptyList(),
    val states: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val repositories: List<String> = emptyList(),
    /** Each project's repositories, which a board chooses from. */
    val projectRepositories: Map<String, List<String>> = emptyMap(),
    /** The catalog that has them is being read for the first time. */
    val repositoriesLoading: Boolean = false,
) {
    /** A board's project's repositories, those of every project in reach for the other views. */
    fun repositoriesFor(view: DashboardView): List<String> = when (val board = view.board) {
        null -> repositories
        else -> projectRepositories.entries.firstOrNull { it.key.equals(board.project, ignoreCase = true) }?.value.orEmpty()
    }
}

/** Everything the view pickers and the editor choose from. */
data class ViewChoices(
    val views: ViewSet = ViewSet(),
    /** Null while the boards are being read for the first time. */
    val boards: List<ProjectBoards>? = emptyList(),
    val options: EditorOptions = EditorOptions(),
    /** The boards last opened, of the projects the global filters include. */
    val recentBoards: List<BoardRef> = views.recentBoards,
    /** Why there are no boards: reading them failed. */
    val boardsError: String? = null,
)

/**
 * The choices within the global filters: only the projects they include, with their teams and
 * repositories; types and states from the catalog and the last sync; people seen in it.
 */
fun viewChoices(views: ViewSet, catalogState: CatalogState, filters: Filters, snapshot: Snapshot?): ViewChoices {
    val catalog = catalogState.catalog
    // A catalog saved by a release before the views has no teams: it says nothing about them
    val withTeams = catalog?.takeIf { it.version >= FilterCatalog.VERSION }
    val boards = when {
        withTeams != null -> withTeams.projects.map { it.name }.filter(filters::includesProject)
            .map { ProjectBoards(it, withTeams.teams[it].orEmpty()) }
            .filter { it.teams.isNotEmpty() }
        // Still to read, or being read: only a failure says there are none
        catalogState.error != null && !catalogState.loading -> emptyList()
        else -> null
    }
    val shown = snapshot?.shown
    val items = snapshot?.workItems.orEmpty() + shown?.workItems.orEmpty()
    val prs = snapshot?.reviewPrs.orEmpty() + snapshot?.myPrs.orEmpty() + shown?.otherPrs.orEmpty()
    fun List<String>.clean() = filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    // A board's project's repositories: the catalog's, and those seen in its pull requests
    val catalogRepositories = catalog?.repositories?.filterKeys(filters::includesProject).orEmpty()
    val projectRepositories = (catalogRepositories.keys + prs.map { it.project }).distinctBy { it.lowercase() }.associateWith { project ->
        (catalogRepositories.entries.filter { it.key.equals(project, ignoreCase = true) }.flatMap { it.value } +
            prs.filter { it.project.equals(project, ignoreCase = true) }.map { it.repoName })
            .clean().sortedBy { it.lowercase() }
    }
    val types = catalog?.types?.filter { t -> t.projects.any(filters::includesProject) }?.map { it.name }.orEmpty()
    val states = catalog?.types?.flatMap { it.states }.orEmpty()
    val me = snapshot?.me?.name
    return ViewChoices(
        views = views,
        boards = boards,
        recentBoards = views.recentBoards.filter { filters.includesProject(it.project) },
        boardsError = catalogState.error.takeIf { boards?.isEmpty() == true },
        options = EditorOptions(
            types = (types + items.map { it.type }).clean(),
            states = (states + items.map { it.state }).clean(),
            people = (items.mapNotNull { it.assignedTo } + prs.flatMap { pr -> listOf(pr.author.name) + pr.reviewers.map { it.name } })
                .clean().filterNot { it.equals(me, ignoreCase = true) }.sortedBy { it.lowercase() },
            repositories = (catalogRepositories.values.flatten() + prs.map { it.repoName }).clean().sortedBy { it.lowercase() },
            projectRepositories = projectRepositories,
            repositoriesLoading = catalog == null && catalogState.loading,
        ),
    )
}

/** The view's name: the global view's own when not renamed, "Project › Team" for a board. */
@Composable
@ReadOnlyComposable
fun viewTitle(view: DashboardView): String = when {
    view.board != null -> stringResource(R.string.view_board_title, view.board.project, view.board.team)
    view.name.isBlank() -> stringResource(R.string.view_global)
    else -> view.name
}

/** Icon of a saved view: what it is mostly about. */
fun viewIcon(view: DashboardView): ImageVector = when {
    view.isGlobal -> Icons.Filled.Search
    view.board != null -> AdoIcons.Flag
    !view.workItems.enabled && view.pullRequests.enabled -> AdoIcons.MergeType
    view.workItems.conditions.any { it.field == WiField.TYPE && !it.negate && it.values.any { v -> v.equals("Bug", true) } } ->
        AdoIcons.Error
    else -> AdoIcons.FilterList
}

/** "Mine · not closed": the work items panel's header action. */
@Composable
@ReadOnlyComposable
fun workItemsSummary(panel: WorkItemsPanel): String {
    val parts = mutableListOf<String>()
    parts += when (val who = panel.assignee) {
        Condition.ME -> stringResource(R.string.summary_mine)
        Condition.ANYONE -> stringResource(R.string.summary_anyone)
        else -> who
    }
    panel.conditions.filter { it.values.isNotEmpty() }.forEach { c ->
        when (c.field) {
            WiField.ASSIGNED_TO -> {}
            WiField.TYPE -> parts += if (c.negate) stringResource(R.string.summary_not, c.values.joinToString(", "))
            else c.values.joinToString(", ")
            WiField.STATE -> {
                val names = c.values.map { if (it == Condition.CLOSED) stringResource(R.string.summary_closed) else it }
                parts += if (c.negate) stringResource(R.string.summary_not, names.joinToString(", ")) else names.joinToString(", ")
            }
        }
    }
    return parts.joinToString(" · ")
}

/** "Reviews and mine": the pull requests panel's header action. */
@Composable
@ReadOnlyComposable
fun pullRequestsSummary(panel: PullRequestsPanel): String {
    val show = panel.show
    val parts = mutableListOf(
        when {
            PrScope.ALL in show -> stringResource(R.string.summary_pr_all)
            PrScope.TO_REVIEW in show && PrScope.MINE in show -> stringResource(R.string.summary_pr_reviews_and_mine)
            PrScope.TO_REVIEW in show -> stringResource(R.string.pr_show_to_review)
            PrScope.MINE in show -> stringResource(R.string.pr_show_mine)
            else -> stringResource(R.string.summary_none)
        },
    )
    if (panel.repositories.isNotEmpty()) parts += panel.repositories.sorted().joinToString(", ")
    if (!panel.drafts) parts += stringResource(R.string.summary_no_drafts)
    return parts.joinToString(" · ")
}

@Composable
@ReadOnlyComposable
fun activitySummary(panel: ActivityPanel): String =
    if (panel.showsAll) stringResource(R.string.summary_activity_all)
    else ActivityKind.offered.filter { it in panel.kinds }.map { stringResource(activityLabel(it)) }.joinToString(", ")
        .ifEmpty { stringResource(R.string.summary_none) }

/** "Work items: Mine · not closed, PRs: Reviews and mine, all activity". */
@Composable
@ReadOnlyComposable
fun viewDescription(view: DashboardView): String = listOfNotNull(
    if (view.workItems.enabled) stringResource(R.string.view_desc_work_items, workItemsSummary(view.workItems)) else null,
    if (view.pullRequests.enabled) stringResource(R.string.view_desc_prs, pullRequestsSummary(view.pullRequests)) else null,
    when {
        !view.activity.enabled -> null
        view.activity.showsAll -> stringResource(R.string.summary_activity_all)
        else -> stringResource(R.string.view_desc_activity, activitySummary(view.activity))
    },
).joinToString(", ").replaceFirstChar { it.uppercase() }

fun activityLabel(kind: ActivityKind): Int = when (kind) {
    ActivityKind.MENTIONS -> R.string.activity_mentions
    ActivityKind.ASSIGNMENTS -> R.string.activity_assignments
    ActivityKind.VOTES -> R.string.activity_votes
    ActivityKind.COMMENTS -> R.string.activity_comments
    ActivityKind.REVIEW_REQUESTS -> R.string.activity_review_requests
    ActivityKind.BUILDS_FAILED -> R.string.activity_builds_failed
}
