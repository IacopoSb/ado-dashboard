package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.BacklogReorder
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.TeamIteration
import io.github.iacoposb.adodash.api.WorkItem
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
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BacklogEditControllerTest {
    private val board = BoardRef("Contoso", "Backend")

    private fun item(id: Int, type: String = "User Story", state: String = "Active") =
        SprintItem(Fakes.workItem(id, type, state = state), rank = id.toDouble())

    private val sprint = Sprint(
        board,
        TeamIteration("it-42", "Sprint 42", "Contoso\\Release 3\\Sprint 42"),
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

        override suspend fun me() = Identity("", "")
        override suspend fun projects() = emptyList<String>()
        override suspend fun queryWorkItemIds(wiql: String, top: Int) = emptyList<Int>()
        override suspend fun workItems(ids: List<Int>) = emptyList<WorkItem>()
        override suspend fun stateCategories(project: String, type: String) = emptyMap<String, String>()
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter) = emptyList<PullRequest>()
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()
    }

    /** The sprint on screen, as the repository keeps it. */
    private var shown = sprint

    private fun TestScope.controller(api: Api) = BacklogEditController(
        this, { api },
        onSprint = { b, change -> if (b == shown.board) shown = change(shown) },
        errorText = { it.message ?: "" },
    )

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
