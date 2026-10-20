package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.TeamIteration
import io.github.iacoposb.adodash.data.BoardRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BacklogOrderTest {
    private fun item(id: Int, rank: Double? = id.toDouble(), parentId: Int? = null) =
        SprintItem(Fakes.workItem(id, "Task"), rank = rank, parentId = parentId)

    private val sprint = Sprint(
        BoardRef("Contoso", "Backend"),
        TeamIteration("it-42", "Sprint 42", "Contoso\\Sprint 42"),
        listOf(
            SprintGroup(item(1), listOf(item(11, parentId = 1), item(12, parentId = 1), item(13, parentId = 1))),
            SprintGroup(item(2)),
            SprintGroup(item(3), listOf(item(31, parentId = 30))),
            SprintGroup(item(4)),
        ),
    )

    private fun Sprint.top() = groups.map { it.parent.id }
    private fun Sprint.children(id: Int) = groups.first { it.parent.id == id }.children.map { it.id }

    @Test fun `reordering needs every sibling shown`() {
        assertTrue(BacklogOrder.canReorder(BacklogFilter()))
        assertTrue(BacklogOrder.canReorder(BacklogFilter(hideClosed = false)))
        assertFalse(BacklogOrder.canReorder(BacklogFilter(mine = true)))
        assertFalse(BacklogOrder.canReorder(BacklogFilter(types = setOf("Bug"))))
    }

    @Test fun `a move names the neighbours where the item lands, 0 at the ends`() {
        assertEquals(BacklogMove(4, 0, 1, 2), BacklogOrder.move(listOf(1, 2, 3, 4), 4, 1, 0))
        assertEquals(BacklogMove(1, 0, 4, 0), BacklogOrder.move(listOf(1, 2, 3, 4), 1, 3, 0))
        assertEquals(BacklogMove(3, 0, 0, 1), BacklogOrder.move(listOf(1, 2, 3, 4), 3, 0, 0))
        assertNull(BacklogOrder.move(listOf(1, 2, 3, 4), 2, 1, 0))
        assertNull(BacklogOrder.move(listOf(1), 1, 0, 0))
        // Out of range: at the end
        assertEquals(BacklogMove(1, 0, 4, 0), BacklogOrder.move(listOf(1, 2, 3, 4), 1, 9, 0))
    }

    @Test fun `the menu's moves, none past the ends`() {
        val siblings = listOf(11, 12, 13)
        assertEquals(BacklogMove(13, 1, 0, 11), BacklogOrder.shift(siblings, 13, Shift.TOP, 1))
        assertEquals(BacklogMove(13, 1, 11, 12), BacklogOrder.shift(siblings, 13, Shift.UP, 1))
        assertEquals(BacklogMove(11, 1, 12, 13), BacklogOrder.shift(siblings, 11, Shift.DOWN, 1))
        assertEquals(BacklogMove(11, 1, 13, 0), BacklogOrder.shift(siblings, 11, Shift.BOTTOM, 1))
        assertNull(BacklogOrder.shift(siblings, 11, Shift.TOP, 1))
        assertNull(BacklogOrder.shift(siblings, 11, Shift.UP, 1))
        assertNull(BacklogOrder.shift(siblings, 13, Shift.DOWN, 1))
        assertNull(BacklogOrder.shift(siblings, 13, Shift.BOTTOM, 1))
    }

    @Test fun `the parent named is none at the top level, the real one for a grandchild`() {
        val g1 = sprint.groups[0]
        val g3 = sprint.groups[2]
        assertEquals(0, BacklogOrder.parentOf(g1.parent, null))
        assertEquals(0, BacklogOrder.parentOf(g1.parent, g1))
        assertEquals(1, BacklogOrder.parentOf(g1.children[0], g1))
        assertEquals(30, BacklogOrder.parentOf(g3.children[0], g3))
        assertEquals(1, BacklogOrder.parentOf(item(14), g1))
    }

    @Test fun `a top-level move is shown at once, children going along`() {
        val moved = BacklogOrder.apply(sprint, BacklogMove(1, 0, 3, 4))
        assertEquals(listOf(2, 3, 1, 4), moved.top())
        assertEquals(listOf(11, 12, 13), moved.children(1))
        assertEquals(listOf(4, 1, 2, 3), BacklogOrder.apply(sprint, BacklogMove(4, 0, 0, 1)).top())
        assertEquals(listOf(2, 3, 4, 1), BacklogOrder.apply(sprint, BacklogMove(1, 0, 4, 0)).top())
    }

    @Test fun `a child moves among its siblings only`() {
        val moved = BacklogOrder.apply(sprint, BacklogMove(13, 1, 0, 11))
        assertEquals(listOf(13, 11, 12), moved.children(1))
        assertEquals(sprint.top(), moved.top())
    }

    @Test fun `between neighbours not next to each other it goes right after the previous one`() {
        // 2 and 3 hidden by a filter: dropped between 1 and 4
        assertEquals(listOf(1, 4, 2, 3), BacklogOrder.apply(sprint, BacklogMove(4, 0, 1, 2)).top())
        // A previous one gone meanwhile: before the next one
        assertEquals(listOf(1, 4, 2, 3), BacklogOrder.apply(sprint, BacklogMove(4, 0, 99, 2)).top())
        // Neither there: at the end
        assertEquals(listOf(2, 3, 4, 1), BacklogOrder.apply(sprint, BacklogMove(1, 0, 98, 99)).top())
    }

    @Test fun `the move back is the one to the neighbours it had`() {
        val back = BacklogOrder.current(sprint, 2)!!
        assertEquals(BacklogMove(2, 0, 1, 3), back)
        val moved = BacklogOrder.apply(sprint, BacklogMove(2, 0, 4, 0))
        assertEquals(sprint, BacklogOrder.apply(moved, back))
        assertEquals(BacklogMove(12, 1, 11, 13), BacklogOrder.current(sprint, 12))
        assertEquals(BacklogMove(31, 30, 0, 0), BacklogOrder.current(sprint, 31))
        assertNull(BacklogOrder.current(sprint, 99))
    }

    @Test fun `the new ranks are taken, the rows keep their places`() {
        val ranked = BacklogOrder.withRanks(BacklogOrder.apply(sprint, BacklogMove(4, 0, 0, 1)), mapOf(4 to 0.5, 12 to 7.0))
        assertEquals(listOf(4, 1, 2, 3), ranked.top())
        assertEquals(0.5, ranked.groups[0].parent.rank)
        assertEquals(7.0, ranked.groups[1].children[1].rank)
    }
}
