package io.github.iacoposb.adodash.sync

/**
 * Where a work item goes among its siblings in the sprint backlog: right after [previousId], or
 * right before [nextId] (0 for none: at that end). [parentId] is the parent they share, 0 at the
 * top level.
 */
data class BacklogMove(val id: Int, val parentId: Int, val previousId: Int, val nextId: Int)

/** The row menu's moves. */
enum class Shift { TOP, UP, DOWN, BOTTOM }

/** The sprint backlog's order as the app changes it: by drag and drop or from a row's menu. */
object BacklogOrder {

    /**
     * Whether the rows shown can be reordered: "Mine" and the types hide siblings, and placing an
     * item between neighbours that are not really next to each other would be guesswork. Closed
     * items hidden are not in the way: an item still goes between the two it is dropped between.
     */
    fun canReorder(filter: BacklogFilter): Boolean = !filter.mine && filter.types.isEmpty()

    /** The parent a reorder names for [item]: 0 at the top level, its own parent under [group]. */
    fun parentOf(item: SprintItem, group: SprintGroup?): Int =
        if (group == null || group.parent.id == item.id) 0 else item.parentId ?: group.parent.id

    /** [id] moved to index [to] of [siblings] (the ids shown, [id] among them); null when it stays. */
    fun move(siblings: List<Int>, id: Int, to: Int, parentId: Int): BacklogMove? {
        val from = siblings.indexOf(id)
        if (from < 0 || siblings.size < 2) return null
        val target = to.coerceIn(0, siblings.lastIndex)
        if (target == from) return null
        return at(siblings.toMutableList().apply { removeAt(from); add(target, id) }, id, parentId)
    }

    /** [id] moved by [shift] in [siblings]; null when it is already there. */
    fun shift(siblings: List<Int>, id: Int, shift: Shift, parentId: Int): BacklogMove? {
        val from = siblings.indexOf(id)
        if (from < 0) return null
        val to = when (shift) {
            Shift.TOP -> 0
            Shift.UP -> from - 1
            Shift.DOWN -> from + 1
            Shift.BOTTOM -> siblings.lastIndex
        }
        if (to < 0 || to > siblings.lastIndex) return null
        return move(siblings, id, to, parentId)
    }

    /** The move that puts [id] where it is in [order], between its neighbours there. */
    fun at(order: List<Int>, id: Int, parentId: Int): BacklogMove {
        val i = order.indexOf(id)
        return BacklogMove(id, parentId, order.getOrNull(i - 1) ?: 0, order.getOrNull(i + 1) ?: 0)
    }

    /**
     * Where [id] is now in [sprint], with its neighbours there, hidden ones included: the move
     * that puts it back. Null when it is not in the sprint.
     */
    fun current(sprint: Sprint, id: Int): BacklogMove? {
        val top = sprint.groups.map { it.parent.id }
        if (id in top) return at(top, id, 0)
        val group = sprint.groups.firstOrNull { g -> g.children.any { it.id == id } } ?: return null
        val item = group.children.first { it.id == id }
        return at(group.children.map { it.id }, id, parentOf(item, group))
    }

    /**
     * [sprint] with [move] made at once, before Azure DevOps answers: the item right after
     * [BacklogMove.previousId], else right before [BacklogMove.nextId], else at the end named.
     */
    fun apply(sprint: Sprint, move: BacklogMove): Sprint {
        if (sprint.groups.any { it.parent.id == move.id }) {
            return sprint.copy(groups = place(sprint.groups, move) { it.parent.id })
        }
        return sprint.copy(
            groups = sprint.groups.map { g ->
                if (g.children.any { it.id == move.id }) g.copy(children = place(g.children, move) { it.id }) else g
            },
        )
    }

    private fun <T> place(list: List<T>, move: BacklogMove, id: (T) -> Int): List<T> {
        val moved = list.firstOrNull { id(it) == move.id } ?: return list
        val rest = list.filter { id(it) != move.id }
        val after = if (move.previousId == 0) -1 else rest.indexOfFirst { id(it) == move.previousId }
        val before = if (move.nextId == 0) -1 else rest.indexOfFirst { id(it) == move.nextId }
        val at = when {
            after >= 0 -> after + 1
            before >= 0 -> before
            move.previousId == 0 -> 0
            else -> rest.size
        }
        return rest.toMutableList().apply { add(at, moved) }
    }

    /** The new backlog order Azure DevOps gave the items it moved; the rows keep their places. */
    fun withRanks(sprint: Sprint, ranks: Map<Int, Double>): Sprint =
        ranks.entries.fold(sprint) { s, (id, rank) -> s.replacing(id) { it.copy(rank = rank) } }
}
