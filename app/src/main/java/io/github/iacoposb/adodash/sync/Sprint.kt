@file:UseSerializers(DayOfWeekSerializer::class)

package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.data.DayOfWeekSerializer
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.Serializable
import io.github.iacoposb.adodash.api.DayRange
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.TeamIteration
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.data.BoardRef
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

/** A work item of the sprint with its state's category (Proposed, InProgress, …), null when unknown. */
@Serializable
data class SprintItem(
    val item: WorkItem,
    val assigneeId: String? = null,
    /** Hours. */
    val remainingWork: Double? = null,
    /** Backlog order: lower first; null after the ranked ones. */
    val rank: Double? = null,
    val category: String? = null,
) {
    val id get() = item.id

    /** Assigned to [me]: by identity id, or by name when the id is not known. */
    fun isMine(me: Identity): Boolean =
        (assigneeId != null && me.id.isNotEmpty() && assigneeId.equals(me.id, ignoreCase = true)) ||
            (item.assignedTo != null && item.assignedTo.equals(me.name, ignoreCase = true))

    /** Done for the counts: Resolved or Completed. */
    val done get() = category == RESOLVED || category == COMPLETED

    /**
     * Closed: what "Hide closed" hides at the top level, greyed and without hours left as a
     * child. Resolved counts: the processes in use treat it as done, like Completed; Removed too.
     */
    val closed get() = category == RESOLVED || category == COMPLETED || category == REMOVED

    val removed get() = category == REMOVED

    companion object {
        const val PROPOSED = "Proposed"
        const val IN_PROGRESS = "InProgress"
        const val RESOLVED = "Resolved"
        const val COMPLETED = "Completed"
        const val REMOVED = "Removed"
    }
}

/** An item of the backlog's top level with its children, both in backlog order. */
@Serializable
data class SprintGroup(val parent: SprintItem, val children: List<SprintItem> = emptyList()) {
    /** Children counted in "done/total": the removed ones are not. */
    val childrenTotal get() = children.count { !it.removed }
    val childrenDone get() = children.count { it.done }
}

/** My items of the sprint by state category, for the tile. */
data class MineCounts(val toDo: Int = 0, val inProgress: Int = 0, val done: Int = 0)

/**
 * The current sprint of a board: its top-level items (the backlog's requirements, bugs and the
 * like, and anything else without a parent in the sprint) with their children, and what tells
 * the days left: the team's working days and days off.
 */
@Serializable
data class Sprint(
    val board: BoardRef,
    val iteration: TeamIteration,
    val groups: List<SprintGroup>,
    val workingDays: Set<DayOfWeek> = DEFAULT_WORKING_DAYS,
    val daysOff: List<DayRange> = emptyList(),
) {
    val items: List<SprintItem> get() = groups.flatMap { listOf(it.parent) + it.children }

    /**
     * Working days from [today] (or the start, if later) to the last day, both included, days
     * off excluded: as Azure Boards counts them. Null without dates; 0 once it is over.
     */
    fun daysLeft(today: LocalDate): Int? {
        // Azure DevOps keeps the days at midnight UTC
        val finish = iteration.finish?.atOffset(ZoneOffset.UTC)?.toLocalDate() ?: return null
        val start = iteration.start?.atOffset(ZoneOffset.UTC)?.toLocalDate()
        var day = if (start != null && start.isAfter(today)) start else today
        var count = 0
        while (!day.isAfter(finish)) {
            if (day.dayOfWeek in workingDays && daysOff.none { day in it }) count++
            day = day.plusDays(1)
        }
        return count
    }

    /** My items, parents and children, by category; removed ones left out. */
    fun mine(me: Identity): MineCounts {
        val mine = items.filter { it.isMine(me) && !it.removed }
        return MineCounts(
            toDo = mine.count { it.category == null || it.category == SprintItem.PROPOSED },
            inProgress = mine.count { it.category == SprintItem.IN_PROGRESS },
            done = mine.count { it.done },
        )
    }

    /** [change] applied to the item with its id, wherever it is listed (a change from the app). */
    fun replacing(id: Int, change: (SprintItem) -> SprintItem): Sprint = copy(
        groups = groups.map { g ->
            g.copy(
                parent = if (g.parent.id == id) change(g.parent) else g.parent,
                children = g.children.map { if (it.id == id) change(it) else it },
            )
        },
    )

    companion object {
        val DEFAULT_WORKING_DAYS: Set<DayOfWeek> =
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    }
}

/** What the backlog shows: "Mine" or the whole team, the types chosen (none: all), closed items or not. */
data class BacklogFilter(
    val mine: Boolean = false,
    val types: Set<String> = emptySet(),
    val hideClosed: Boolean = true,
)

/** A top-level row as shown, with the children shown under it. */
data class BacklogGroup(val group: SprintGroup, val children: List<SprintItem>) {
    val parent get() = group.parent

    /**
     * Hours left: the children's shown that are not closed when it has children (as Azure
     * Boards rolls them up), its own otherwise; null when nothing has an estimate.
     */
    val remainingWork: Double?
        get() = if (group.children.isEmpty()) {
            parent.remainingWork
        } else {
            children.filterNot { it.closed }.mapNotNull { it.remainingWork }.takeIf { it.isNotEmpty() }?.sum()
        }
}

/** The footer: rows, their children and the hours left. */
data class BacklogTotals(val items: Int, val children: Int, val remainingWork: Double)

/** The sprint backlog's logic: hierarchy, order, filters and totals. */
object Backlog {

    /**
     * The groups of the backlog. [parents] maps a child to its parent; a parent that is not in
     * the sprint makes the child top-level, and grandchildren go under their top-level
     * ancestor, so that there are only two levels as on the sprint backlog. Both levels are in
     * backlog order: rank, then id.
     */
    fun build(items: List<SprintItem>, parents: Map<Int, Int>): List<SprintGroup> {
        val byId = items.associateBy { it.id }
        // An item in a loop of parents (never in Azure DevOps, but cheap to survive) is top-level
        fun root(id: Int): Int {
            var current = id
            val seen = mutableSetOf(current)
            while (true) {
                val parent = parents[current]?.takeIf { it in byId } ?: return current
                if (!seen.add(parent)) return id
                current = parent
            }
        }
        val order = compareBy<SprintItem, Double?>(nullsLast()) { it.rank }.thenBy { it.id }
        val byRoot = items.groupBy { root(it.id) }
        return byRoot.keys.mapNotNull(byId::get).sortedWith(order).map { parent ->
            SprintGroup(parent, byRoot[parent.id].orEmpty().filter { it.id != parent.id }.sortedWith(order))
        }
    }

    /** The types of the top-level items, in the order they first appear. */
    fun types(groups: List<SprintGroup>): List<String> =
        groups.map { it.parent.item.type }.distinctBy { it.lowercase() }

    /**
     * The rows [filter] shows. Types and "Hide closed" look at the top-level items; children
     * are all listed under them, closed ones included, so that progress reads at a glance.
     * "Mine": the items assigned to me with all their children, and the others with children
     * of mine, with only those children.
     */
    fun filter(groups: List<SprintGroup>, filter: BacklogFilter, me: Identity): List<BacklogGroup> = groups.mapNotNull { g ->
        val parent = g.parent
        when {
            filter.types.isNotEmpty() && filter.types.none { it.equals(parent.item.type, ignoreCase = true) } -> null
            filter.hideClosed && parent.closed -> null
            !filter.mine || parent.isMine(me) -> BacklogGroup(g, g.children)
            else -> g.children.filter { it.isMine(me) }.takeIf { it.isNotEmpty() }?.let { BacklogGroup(g, it) }
        }
    }

    /**
     * Whether the team tracks hours in this sprint: some item, top-level or child, has remaining
     * work. Teams that do not never fill it in, and a column of dashes says nothing. The whole
     * sprint counts, not the rows shown, so that the column does not come and go with the filters.
     */
    fun tracksHours(groups: List<SprintGroup>): Boolean =
        groups.any { g -> g.parent.remainingWork != null || g.children.any { it.remainingWork != null } }

    fun totals(rows: List<BacklogGroup>) = BacklogTotals(
        items = rows.size,
        children = rows.sumOf { it.children.size },
        remainingWork = rows.sumOf { it.remainingWork ?: 0.0 },
    )
}
