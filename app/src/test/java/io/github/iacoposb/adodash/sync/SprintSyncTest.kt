package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.BacklogItem
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.DayRange
import io.github.iacoposb.adodash.api.IterationLink
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.TeamIteration
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.DashboardView
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/** The sprint a board's sync reads. */
class SprintSyncTest {
    private class FakeApi(
        val iteration: TeamIteration? = TeamIteration(
            "it-42", "Sprint 42", "Contoso\\Sprint 42", Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-23T00:00:00Z"),
        ),
        val itemsError: Boolean = false,
    ) : AdoApi {
        var iterationCalls = 0
        var workingDayCalls = 0
        var daysOffCalls = 0
        val backlogIds = mutableListOf<List<Int>>()
        override suspend fun me() = me
        override suspend fun projects() = listOf("Contoso")
        override suspend fun queryWorkItemIds(wiql: String, top: Int) = emptyList<Int>()
        override suspend fun workItems(ids: List<Int>) = emptyList<WorkItem>()
        override suspend fun stateCategories(project: String, type: String) = when (type) {
            "Task" -> mapOf("To Do" to "Proposed", "In Progress" to "InProgress", "Done" to "Completed")
            else -> mapOf("New" to "Proposed", "Active" to "InProgress", "Resolved" to "Resolved", "Closed" to "Completed")
        }
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter) = emptyList<PullRequest>()
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()

        override suspend fun currentIteration(project: String, team: String): TeamIteration? {
            iterationCalls++
            return iteration
        }
        override suspend fun iterationWorkItems(project: String, team: String, iterationId: String): List<IterationLink> {
            if (itemsError) throw AdoException(500, "boom")
            return listOf(IterationLink(4790), IterationLink(4791, 4790), IterationLink(4792), IterationLink(4812))
        }
        override suspend fun backlogItems(ids: List<Int>): List<BacklogItem> {
            backlogIds += ids
            return listOf(
                BacklogItem(Fakes.workItem(4790, "User Story", state = "Active"), me.id, rank = 2.0),
                BacklogItem(Fakes.workItem(4791, "Task", state = "Done"), me.id, remainingWork = 0.0, rank = 1.0),
                // Its parent comes from the field: the sprint's links left it top-level
                BacklogItem(Fakes.workItem(4792, "Task", state = "To Do"), me.id, remainingWork = 3.0, parentId = 4790),
                BacklogItem(Fakes.workItem(4812, "Bug", state = "Resolved").copy(assignedTo = "Anna Verdi"), "someone", rank = 1.0),
            )
        }
        override suspend fun teamWorkingDays(project: String, team: String): Set<DayOfWeek> {
            workingDayCalls++
            return setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        }
        override suspend fun teamDaysOff(project: String, team: String, iterationId: String): List<DayRange> {
            daysOffCalls++
            return listOf(DayRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19)))
        }
    }

    private val board = DashboardView.forBoard(BoardRef("Contoso", "Backend"))

    @Test fun `a board reads its sprint with the hierarchy, categories and the team's days`() = runTest {
        val api = FakeApi()
        val cache = SignalCache()
        val engine = SyncEngine(api, texts = Fakes.Texts, cache = cache)
        val sprint = engine.fetch(me, since = null, view = board).snapshot.shownFor(board)!!.sprint!!
        assertEquals("Sprint 42", sprint.iteration.name)
        assertEquals(listOf(4812, 4790), sprint.groups.map { it.parent.id })
        assertEquals(listOf(4791, 4792), sprint.groups[1].children.map { it.id })
        assertEquals(listOf("Resolved", "InProgress"), sprint.groups.map { it.parent.category })
        assertEquals(listOf(4790, 4791, 4792, 4812), api.backlogIds.single())
        assertEquals(MineCounts(toDo = 1, inProgress = 1, done = 1), sprint.mine(me))
        // Mon-Thu without Monday 19: Thu 22 and Wed 21, Tue 20 from Friday 16
        assertEquals(3, sprint.daysLeft(LocalDate.of(2026, 10, 16)))

        engine.fetch(me, since = null, view = board)
        assertEquals("the sprint is read at every sync", 2, api.iterationCalls)
        assertEquals("working days are read once", 1, api.workingDayCalls)
        assertEquals("days off once per sprint", 1, api.daysOffCalls)
    }

    @Test fun `the global view has no sprint`() = runTest {
        val api = FakeApi()
        val shown = SyncEngine(api, texts = Fakes.Texts).fetch(me, since = null).snapshot.shownFor(DashboardView.global)!!
        assertNull(shown.sprint)
        assertEquals(0, api.iterationCalls)
    }

    @Test fun `a team without a current sprint has no tile`() = runTest {
        val snapshot = SyncEngine(FakeApi(iteration = null), texts = Fakes.Texts).fetch(me, since = null, view = board).snapshot
        assertNull(snapshot.shownFor(board)!!.sprint)
        assertTrue(snapshot.warnings.isEmpty())
    }

    @Test fun `a sprint that cannot be read is left out with a warning`() = runTest {
        val snapshot = SyncEngine(FakeApi(itemsError = true), texts = Fakes.Texts).fetch(me, since = null, view = board).snapshot
        assertNull(snapshot.shownFor(board)!!.sprint)
        assertEquals(listOf("Contoso › Backend: boom"), snapshot.warnings)
    }
}
