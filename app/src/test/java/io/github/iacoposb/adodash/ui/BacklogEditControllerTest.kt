package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.BacklogReorder
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.FieldChange
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.Iteration
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.TeamIteration
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemDetail
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.sync.BacklogMove
import io.github.iacoposb.adodash.sync.Sprint
import io.github.iacoposb.adodash.sync.SprintGroup
import io.github.iacoposb.adodash.sync.SprintItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BacklogEditControllerTest {
    private val board = BoardRef("Contoso", "Backend")

    private fun item(id: Int, type: String = "User Story", state: String = "Active") =
        SprintItem(Fakes.workItem(id, type, state = state), rank = id.toDouble())

    private val sprint = Sprint(
        board,
        TeamIteration("it-42", "Sprint 42", "Contoso\\Release 3\\Sprint 42", Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-23T00:00:00Z")),
        listOf(SprintGroup(item(1)), SprintGroup(item(2)), SprintGroup(item(3))),
    )

    private open inner class Api : AdoApi {
        val reorders = mutableListOf<BacklogReorder>()
        var reorderFails: AdoException? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun reorderSprintItems(project: String, team: String, iterationId: String, move: BacklogReorder): Map<Int, Double> {
            reorders += move
            gate?.await()
            reorderFails?.let { throw it }
            return mapOf(move.ids.single() to 0.5)
        }

        /** The work items as Azure DevOps has them. */
        val details = mutableMapOf<Int, WorkItemDetail>()
        val updates = mutableListOf<Triple<Int, Int, FieldChange>>()
        /** Refused for these ids. */
        val failures = mutableMapOf<Int, AdoException>()
        /** Saved by someone else once, just before our save of these ids. */
        val conflictOnce = mutableSetOf<Int>()
        var running = 0
        var maxRunning = 0

        override suspend fun workItemDetail(id: Int) =
            details.getOrPut(id) { WorkItemDetail(Fakes.workItem(id, "Bug", state = "Active"), rev = 1) }

        override suspend fun updateWorkItem(id: Int, rev: Int, change: FieldChange): WorkItemDetail {
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                kotlinx.coroutines.yield()
                updates += Triple(id, rev, change)
                failures[id]?.let { throw it }
                val current = workItemDetail(id)
                if (conflictOnce.remove(id)) {
                    details[id] = current.copy(rev = current.rev + 1)
                    throw AdoException(412, "conflict")
                }
                if (rev != current.rev) throw AdoException(412, "conflict")
                return current.with(change).copy(rev = rev + 1).also { details[id] = it }
            } finally {
                running--
            }
        }

        override suspend fun iterations(project: String) = listOf(
            Iteration("Contoso", "Contoso"),
            Iteration("Sprint 43", "Contoso\\Release 3\\Sprint 43", Instant.parse("2026-10-26T00:00:00Z")),
        )

        override suspend fun me() = Identity("", "")
        override suspend fun projects() = emptyList<String>()
        override suspend fun queryWorkItemIds(wiql: String, top: Int) = emptyList<Int>()
        override suspend fun workItems(ids: List<Int>) = emptyList<WorkItem>()
        override suspend fun stateCategories(project: String, type: String) = when (type) {
            "Task" -> linkedMapOf("New" to "Proposed", "Active" to "InProgress", "Closed" to "Completed")
            else -> linkedMapOf("New" to "Proposed", "Active" to "InProgress", "Resolved" to "Resolved", "Closed" to "Completed")
        }
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter) = emptyList<PullRequest>()
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()
    }

    /** The sprint on screen, as the repository keeps it. */
    private var shown = sprint
    private val saved = mutableListOf<Pair<Int, Boolean>>()
    private var refreshed = 0

    private fun TestScope.controller(api: Api) = BacklogEditController(
        this, { api },
        onSprint = { b, change -> if (b == shown.board) shown = change(shown) },
        errorText = { it.message ?: "" },
        me = { Person(Fakes.me.id, Fakes.me.name, "me@contoso.com") },
        onSaved = { d, mine -> saved += d.id to mine },
        onBatchDone = { refreshed++ },
    )

    private fun items(vararg ids: Int) = ids.map { Fakes.workItem(it, "Bug") }

    @Test fun `a change of the selection goes to every item, a few at a time, then the backlog is read again`() = runTest {
        val api = Api()
        val c = controller(api)
        c.runBatch(items(1, 2, 3, 4, 5, 6, 7), FieldChange.State("Resolved"))
        assertEquals(BatchProgress(0, 7), c.state.value.progress)
        advanceUntilIdle()
        assertEquals((1..7).toList(), api.updates.map { it.first }.sorted())
        assertTrue(api.updates.all { it.second == 1 && it.third == FieldChange.State("Resolved") })
        assertTrue("at most ${BacklogBatch.PARALLEL} at once", api.maxRunning in 2..BacklogBatch.PARALLEL)
        assertNull(c.state.value.progress)
        assertEquals(BatchResult(FieldChange.State("Resolved"), 7), c.state.value.result)
        assertEquals(7, saved.size)
        assertEquals(1, refreshed)
    }

    @Test fun `items already as asked are not saved again`() = runTest {
        val api = Api()
        val c = controller(api)
        c.runBatch(items(1, 2), FieldChange.State("Active"))
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), api.updates)
        assertEquals(2, c.state.value.result?.updated)
    }

    @Test fun `failures are listed with their reason and can be retried`() = runTest {
        val api = Api().apply {
            failures[2] = AdoException(403, "HTTP 403")
            failures[3] = AdoException(500, "HTTP 500")
        }
        val c = controller(api)
        c.runBatch(items(1, 2, 3), FieldChange.Priority(1))
        advanceUntilIdle()
        val result = c.state.value.result!!
        assertEquals(1, result.updated)
        assertEquals(listOf(2 to SaveProblem.DENIED, 3 to SaveProblem.OTHER), result.failures.map { it.item.id to it.problem })
        assertEquals("HTTP 500", result.failures[1].message)
        assertTrue(result.denied)

        api.failures.clear()
        api.updates.clear()
        c.retryBatch()
        advanceUntilIdle()
        assertEquals(listOf(2, 3), api.updates.map { it.first }.sorted())
        assertEquals(BatchResult(FieldChange.Priority(1), 2), c.state.value.result)
        c.dismissBatch()
        assertNull(c.state.value.result)
    }

    @Test fun `someone else's change in between is read again and saved over once`() = runTest {
        val api = Api().apply { conflictOnce += 1 }
        val c = controller(api)
        c.runBatch(items(1), FieldChange.Priority(1))
        advanceUntilIdle()
        assertEquals(listOf(1, 2), api.updates.map { it.second })
        assertEquals(1, c.state.value.result?.updated)
    }

    @Test fun `assigning to me says so, for no notification`() = runTest {
        val api = Api()
        val c = controller(api)
        c.runBatch(items(1), FieldChange.AssignedTo(Person(Fakes.me.id, Fakes.me.name, "me@contoso.com")))
        advanceUntilIdle()
        assertEquals(listOf(1 to true), saved)
    }

    @Test fun `the menus get the states of each type and the next sprint`() = runTest {
        val api = Api()
        val c = controller(api)
        val withTasks = sprint.copy(groups = sprint.groups + SprintGroup(item(9, "Task")))
        c.loadOptions(withTasks)
        advanceUntilIdle()
        val options = c.state.value.options
        assertEquals(listOf("New", "Active", "Resolved", "Closed"), options.states["user story"]?.map { it.name })
        assertEquals(listOf("New", "Active", "Closed"), options.states["task"]?.map { it.name })
        assertEquals("Contoso\\Release 3\\Sprint 43", options.nextSprint?.path)
        assertEquals("Contoso", options.areas?.name)
        assertEquals(Fakes.me.name, c.state.value.me?.name)
    }

    private fun top() = shown.groups.map { it.parent.id }

    @Test fun `a move shows at once, is sent with its neighbours and takes the new rank`() = runTest {
        val api = Api().apply { gate = CompletableDeferred() }
        val c = controller(api)
        c.reorder(shown, BacklogMove(3, 0, 0, 1))
        assertEquals(listOf(3, 1, 2), top())
        advanceUntilIdle()
        assertEquals(listOf(BacklogReorder(listOf(3), "Contoso\\Release 3\\Sprint 42", 0, 0, 1)), api.reorders)
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(3, 1, 2), top())
        assertEquals(0.5, shown.groups[0].parent.rank)
        assertNull(c.state.value.reorderError)
    }

    @Test fun `a refused move goes back where it was, with the reason`() = runTest {
        val api = Api().apply { reorderFails = AdoException(500, "HTTP 500") }
        val c = controller(api)
        c.reorder(shown, BacklogMove(1, 0, 3, 0))
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), top())
        assertEquals(ReorderError(1, SaveProblem.OTHER, "HTTP 500"), c.state.value.reorderError)
        c.dismissReorderError()
        assertNull(c.state.value.reorderError)
    }

    @Test fun `without permission the reason says so`() = runTest {
        val api = Api().apply { reorderFails = AdoException(403, "HTTP 403") }
        val c = controller(api)
        c.reorder(shown, BacklogMove(2, 0, 0, 1))
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), top())
        assertEquals(SaveProblem.DENIED, c.state.value.reorderError?.problem)
    }

    @Test fun `a move to where it already is sends nothing`() = runTest {
        val api = Api()
        val c = controller(api)
        c.reorder(shown, BacklogMove(2, 0, 1, 3))
        advanceUntilIdle()
        assertEquals(emptyList<BacklogReorder>(), api.reorders)
    }
}
