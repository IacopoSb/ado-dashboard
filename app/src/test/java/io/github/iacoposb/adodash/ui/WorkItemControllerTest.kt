package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.FieldChange
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.WiFields
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemDetail
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkItemControllerTest {
    private val item = Fakes.workItem(4812, "Bug", "SSO sign-in fails", "Active", 1)

    private inner class Api : AdoApi {
        var detail = WorkItemDetail(item, rev = 7)
        var failWith: AdoException? = null
        /** Completed by the test to let a save finish. */
        var gate: CompletableDeferred<Unit>? = null
        val sent = mutableListOf<Pair<Int, FieldChange>>()

        override suspend fun workItemDetail(id: Int) = detail
        override suspend fun updateWorkItem(id: Int, rev: Int, change: FieldChange): WorkItemDetail {
            sent += rev to change
            gate?.await()
            failWith?.let { throw it }
            detail = detail.with(change).copy(rev = rev + 1)
            return detail
        }
        override suspend fun stateCategories(project: String, type: String) =
            linkedMapOf("New" to "Proposed", "Active" to "InProgress", "Resolved" to "Resolved", "Closed" to "Completed")
        override suspend fun typeFields(project: String, type: String) = setOf(WiFields.STATE)

        override suspend fun me() = Identity("", "")
        override suspend fun projects() = emptyList<String>()
        override suspend fun queryWorkItemIds(wiql: String, top: Int) = emptyList<Int>()
        override suspend fun workItems(ids: List<Int>) = emptyList<WorkItem>()
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter) = emptyList<PullRequest>()
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()
    }

    private val saved = mutableListOf<Pair<WorkItem, Boolean>>()

    private fun TestScope.controller(api: Api) = WorkItemController(
        this, { api }, { null }, { Person(Fakes.me.id, Fakes.me.name, "") },
        onSaved = { wi, mine -> saved += wi to mine }, errorText = { it.message ?: "" }, now = { Fakes.now },
    )

    @Test fun `opens with the row, then the work item and its states`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(item.id, item)
        assertEquals(item, c.state.value!!.page.item)
        assertNull(c.state.value!!.page.saved)
        advanceUntilIdle()
        val page = c.state.value!!.page
        assertEquals(7, page.saved!!.rev)
        assertEquals(listOf("New", "Active", "Resolved", "Closed"), page.states!!.map { it.name })
        assertFalse(page.hasRemainingWork)
    }

    @Test fun `a change shows at once, saves against the revision and updates the dashboard`() = runTest {
        val api = Api().apply { gate = CompletableDeferred() }
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        c.change(FieldChange.State("Resolved"))
        advanceUntilIdle()
        assertEquals("Resolved", c.state.value!!.page.item!!.state)
        assertTrue(c.state.value!!.page.saving(WiFields.STATE))
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        val page = c.state.value!!.page
        assertFalse(page.saving(WiFields.STATE))
        assertEquals(8, page.saved!!.rev)
        assertEquals(listOf(7 to FieldChange.State("Resolved")), api.sent)
        assertEquals("Resolved", saved.single().first.state)
        assertFalse(saved.single().second)
    }

    @Test fun `two changes go one after the other, each on the new revision`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        c.change(FieldChange.State("Resolved"))
        c.change(FieldChange.Priority(3))
        advanceUntilIdle()
        assertEquals(listOf(7, 8), api.sent.map { it.first })
        assertEquals(9, c.state.value!!.page.saved!!.rev)
    }

    @Test fun `a conflict shows the saved value again with the reason`() = runTest {
        val api = Api().apply { failWith = AdoException(412, "TF26071") }
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        c.change(FieldChange.Priority(3))
        advanceUntilIdle()
        val page = c.state.value!!.page
        assertEquals(1, page.item!!.priority)
        assertEquals(FieldError(WiFields.PRIORITY, SaveProblem.CONFLICT, "TF26071"), page.fieldError)
        assertFalse(c.state.value!!.readOnly)
        assertTrue(saved.isEmpty())
    }

    @Test fun `refused once, read-only for the session`() = runTest {
        val api = Api().apply { failWith = AdoException(403, "no") }
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        c.change(FieldChange.State("Closed"))
        advanceUntilIdle()
        assertTrue(c.state.value!!.readOnly)
        assertEquals(SaveProblem.DENIED, c.state.value!!.page.fieldError!!.problem)
        c.close()
        c.open(item.id, item)
        assertTrue(c.state.value!!.readOnly)
        c.change(FieldChange.State("Closed"))
        advanceUntilIdle()
        assertEquals(1, api.sent.size)
    }

    @Test fun `assigning myself is my own change`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        c.change(FieldChange.AssignedTo(Person(Fakes.me.id, Fakes.me.name, "")))
        advanceUntilIdle()
        assertTrue(saved.single().second)
    }

    @Test fun `links open over the work item and back returns to it`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        c.openLinked(4790)
        assertEquals(listOf(4812, 4790), c.state.value!!.pages.map { it.id })
        assertTrue(c.state.value!!.canGoBack)
        c.back()
        assertEquals(4812, c.state.value!!.page.id)
        c.back()
        assertNull(c.state.value)
    }
}
