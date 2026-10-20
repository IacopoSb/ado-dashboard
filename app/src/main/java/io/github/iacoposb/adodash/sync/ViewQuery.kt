package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.TeamArea
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.data.Condition
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.data.WiField
import io.github.iacoposb.adodash.data.WiOrder
import io.github.iacoposb.adodash.data.WorkItemsPanel

/**
 * Where a board view looks: its project, the team's area paths and the repositories chosen
 * for it. No [areas] (unknown, or a team field other than the area path) is the whole
 * project; no [repositories] is no pull requests at all, not the whole project's.
 */
data class BoardScope(val project: String, val areas: List<TeamArea> = emptyList(), val repositories: Set<String> = emptySet()) {

    fun accepts(wi: WorkItem): Boolean {
        if (!wi.project.equals(project, ignoreCase = true)) return false
        if (areas.isEmpty()) return true
        val path = wi.areaPath.ifEmpty { wi.project }
        return areas.any { if (it.includeChildren) Filters.isUnder(path, it.path) else path.equals(it.path, ignoreCase = true) }
    }

    fun accepts(pr: PullRequest): Boolean = pr.project.equals(project, ignoreCase = true) && acceptsRepository(pr.repoName)

    /** One of the board's repositories, by name: none until they are chosen. */
    fun acceptsRepository(repo: String): Boolean = repositories.any { it.equals(repo, ignoreCase = true) }

    fun wiqlConditions(): String = buildString {
        append(" AND [System.TeamProject] = ${quote(project)}")
        if (areas.isNotEmpty()) {
            append(" AND (")
            append(
                areas.joinToString(" OR ") {
                    if (it.includeChildren) "[System.AreaPath] UNDER ${quote(it.path)}" else "[System.AreaPath] = ${quote(it.path)}"
                },
            )
            append(")")
        }
    }
}

/** The WIQL of a view's work items panel; the same conditions are checked again on the results. */
object ViewQuery {
    /**
     * Names that usually mean closed: they only narrow the query, the state categories decide.
     * "Resolved" is left out of "not closed" as before views: some processes use it for work
     * still in progress, and the categories drop it where it is not.
     */
    private val CLOSED_NAMES = listOf("Closed", "Done", "Removed", "Completed", "Cut")
    private val CLOSED_OR_RESOLVED_NAMES = CLOSED_NAMES + "Resolved"

    fun wiql(panel: WorkItemsPanel, filters: Filters, scope: BoardScope?): String {
        val where = buildString {
            append("[System.Id] > 0")
            panel.conditions.forEach { append(condition(it)) }
            scope?.let { append(it.wiqlConditions()) }
            append(filters.wiqlConditions())
        }
        val order = when (panel.order) {
            WiOrder.PRIORITY -> "[Microsoft.VSTS.Common.Priority] ASC, [System.ChangedDate] DESC"
            WiOrder.CHANGED -> "[System.ChangedDate] DESC"
        }
        return "SELECT [System.Id] FROM WorkItems\nWHERE $where\nORDER BY $order"
    }

    private fun condition(c: Condition): String {
        if (c.values.isEmpty()) return ""
        return when (c.field) {
            WiField.ASSIGNED_TO -> when (val who = c.values.first()) {
                Condition.ANYONE -> ""
                Condition.ME -> " AND [System.AssignedTo] = @Me"
                else -> " AND [System.AssignedTo] = ${quote(who)}"
            }
            WiField.TYPE -> " AND [System.WorkItemType] ${if (c.negate) "NOT IN" else "IN"} (${list(c.values)})"
            WiField.STATE -> {
                val closed = Condition.CLOSED in c.values
                val names = c.values.filter { it != Condition.CLOSED }
                if (c.negate) " AND [System.State] NOT IN (${list(names + if (closed) CLOSED_NAMES else emptyList())})"
                else " AND [System.State] IN (${list(names + if (closed) CLOSED_OR_RESOLVED_NAMES else emptyList())})"
            }
        }
    }

    private fun list(values: List<String>) = values.distinct().joinToString(",") { quote(it) }
}

private fun quote(value: String) = "'" + value.replace("'", "''") + "'"
