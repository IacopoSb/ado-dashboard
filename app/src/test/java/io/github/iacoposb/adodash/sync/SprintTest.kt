package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.api.DayRange
import io.github.iacoposb.adodash.api.TeamIteration
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.DashboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

class SprintTest {
    private fun item(
        id: Int, type: String = "Task", state: String = "Active", category: String? = SprintItem.IN_PROGRESS,
        rank: Double? = null, remaining: Double? = null, mine: Boolean = true,
    ) = SprintItem(
        Fakes.workItem(id, type, state = state).copy(assignedTo = if (mine) me.name else anna.name),
        assigneeId = if (mine) me.id else anna.id, remainingWork = remaining, rank = rank, category = category,
    )

    private val story = item(1, "User Story", rank = 2.0, mine = false)
    private val bug = item(2, "Bug", rank = 1.0, remaining = 3.0, mine = false)
    private val chore = item(3, "Chore", state = "Closed", category = SprintItem.COMPLETED, rank = 3.0)
    private val taskDone = item(11, state = "Closed", category = SprintItem.COMPLETED, rank = 2.0, remaining = 1.0, mine = false)
    private val taskMine = item(12, rank = 1.0, remaining = 5.0)
    private val taskRemoved = item(13, state = "Removed", category = SprintItem.REMOVED, rank = 3.0, mine = false)
    private val taskOther = item(14, state = "New", category = SprintItem.PROPOSED, remaining = 2.0, mine = false)
    private val all = listOf(chore, taskOther, story, taskDone, bug, taskMine, taskRemoved)
    private val parents = mapOf(11 to 1, 12 to 1, 13 to 1, 14 to 1)

    private val groups = Backlog.build(all, parents)

    @Test fun `top-level items in backlog order, children in theirs, unranked last`() {
        assertEquals(listOf(2, 1, 3), groups.map { it.parent.id })
        assertEquals(listOf(12, 11, 13, 14), groups[1].children.map { it.id })
        assertEquals(emptyList<SprintItem>(), groups[0].children)
    }

    @Test fun `a child whose parent is not in the sprint is top-level, a grandchild goes under the top`() {
        val feature = item(100, "Feature", rank = 0.0)
        val built = Backlog.build(listOf(feature, story, taskMine, taskOther), mapOf(1 to 100, 12 to 1, 14 to 999))
        assertEquals(listOf(100, 14), built.map { it.parent.id })
        assertEquals(listOf(12, 1), built[0].children.map { it.id })
    }

    @Test fun `a loop in the parents does not hang`() {
        val built = Backlog.build(listOf(story, bug), mapOf(1 to 2, 2 to 1))
        assertEquals(listOf(2, 1), built.map { it.parent.id })
        assertEquals(listOf(0, 0), built.map { it.children.size })
    }

    @Test fun `children done of total leave the removed out`() {
        val g = groups[1]
        assertEquals(1, g.childrenDone)
        assertEquals(3, g.childrenTotal)
    }

    @Test fun `hide closed hides closed top-level items but lists every child`() {
        val shown = Backlog.filter(groups, BacklogFilter(hideClosed = true), me)
        assertEquals(listOf(2, 1), shown.map { it.parent.id })
        assertEquals(4, shown[1].children.size)
        assertEquals(listOf(2, 1, 3), Backlog.filter(groups, BacklogFilter(hideClosed = false), me).map { it.parent.id })
    }

    @Test fun `mine shows my items with all their children and others with only my children`() {
        val mineStory = story.copy(assigneeId = me.id, item = story.item.copy(assignedTo = me.name))
        val withMine = Backlog.build(all.map { if (it.id == 1) mineStory else it }, parents)
        val filter = BacklogFilter(mine = true, hideClosed = false)
        val mine = Backlog.filter(withMine, filter, me)
        assertEquals(listOf(1, 3), mine.map { it.parent.id })
        assertEquals("my story: all its children", 4, mine[0].children.size)

        val others = Backlog.filter(groups, filter, me)
        assertEquals("the bug has no child of mine", listOf(1, 3), others.map { it.parent.id })
        assertEquals("someone else's story: only my children", listOf(12), others[0].children.map { it.id })
    }

    @Test fun `mine is told by identity id, or by name without it`() {
        val byName = item(5).copy(assigneeId = null)
        val other = item(6, mine = false).copy(item = item(6, mine = false).item.copy(assignedTo = "Someone"), assigneeId = me.id)
        assertEquals(true, byName.isMine(me))
        assertEquals(true, other.isMine(me))
        assertEquals(false, item(7, mine = false).isMine(me))
    }

    @Test fun `types filter the top-level items in the order they appear`() {
        assertEquals(listOf("Bug", "User Story", "Chore"), Backlog.types(groups))
        val shown = Backlog.filter(groups, BacklogFilter(types = setOf("user story"), hideClosed = false), me)
        assertEquals(listOf(1), shown.map { it.parent.id })
    }

    @Test fun `remaining rolls up the open children shown, totals add the rows`() {
        val shown = Backlog.filter(groups, BacklogFilter(), me)
        assertEquals("the bug's own", 3.0, shown[0].remainingWork)
        assertEquals("open children only: 5 + 2", 7.0, shown[1].remainingWork)
        assertEquals(BacklogTotals(items = 2, children = 4, remainingWork = 10.0), Backlog.totals(shown))
        val mine = Backlog.filter(groups, BacklogFilter(mine = true), me)
        assertEquals("only my child's hours", BacklogTotals(1, 1, 5.0), Backlog.totals(mine))
    }

    private fun day(d: String) = Instant.parse("${d}T00:00:00Z")
    private fun sprint(start: String?, finish: String?, daysOff: List<DayRange> = emptyList(), workingDays: Set<DayOfWeek> = Sprint.DEFAULT_WORKING_DAYS) =
        Sprint(
            BoardRef("Contoso", "Backend"), TeamIteration("it", "Sprint 42", "Contoso\\Sprint 42", start?.let(::day), finish?.let(::day)),
            groups, workingDays, daysOff,
        )

    @Test fun `days left are working days from today to the last day, both included`() {
        // Friday 16 October to Friday 23: Fri, Mon-Fri
        val s = sprint("2026-10-12", "2026-10-23")
        assertEquals(6, s.daysLeft(LocalDate.of(2026, 10, 16)))
        assertEquals("the last day", 1, s.daysLeft(LocalDate.of(2026, 10, 23)))
        assertEquals("over", 0, s.daysLeft(LocalDate.of(2026, 10, 24)))
        assertEquals("not started yet: from its start", 10, s.daysLeft(LocalDate.of(2026, 10, 1)))
        assertNull(sprint(null, null).daysLeft(LocalDate.of(2026, 10, 16)))
    }

    @Test fun `days off and the team's working days count`() {
        val off = listOf(DayRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20)))
        assertEquals(4, sprint("2026-10-12", "2026-10-23", off).daysLeft(LocalDate.of(2026, 10, 16)))
        val fourDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        assertEquals(4, sprint("2026-10-12", "2026-10-23", workingDays = fourDays).daysLeft(LocalDate.of(2026, 10, 16)))
    }

    @Test fun `my items by category for the tile, removed ones left out`() {
        val s = sprint("2026-10-12", "2026-10-23")
        // Mine: chore (completed), task 12 (in progress)
        assertEquals(MineCounts(toDo = 0, inProgress = 1, done = 1), s.mine(me))
        val unknown = s.replacing(12) { it.copy(category = null) }
        assertEquals("an unknown category counts as to do", MineCounts(1, 0, 1), unknown.mine(me))
        assertEquals(MineCounts(1, 2, 1), s.mine(anna))
    }

    @Test fun `a change from the app reaches the sprint row`() {
        val board = DashboardView.forBoard(BoardRef("Contoso", "Backend"))
        val s = sprint("2026-10-12", "2026-10-23")
        val snapshot = Snapshot(
            me, emptyList(), emptyList(), emptyList(), Fakes.now,
            shown = Shown(board.queryKey, emptyList(), emptyList(), emptyList(), sprint = s),
        )
        val changed = taskOther.item.copy(state = "Active")
        val after = snapshot.replacing(changed) { it.copy(item = changed, category = SprintItem.IN_PROGRESS, remainingWork = 1.0) }
        val row = after.shown!!.sprint!!.groups[1].children.first { it.id == 14 }
        assertEquals("Active", row.item.state)
        assertEquals(SprintItem.IN_PROGRESS, row.category)
        assertEquals(1.0, row.remainingWork)
    }
}
