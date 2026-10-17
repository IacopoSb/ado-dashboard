package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.Build
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.Pipeline
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PipelinesControllerTest {
    private class Api : AdoApi {
        var pipelines = listOf(Pipeline(5, "backend-ci", "\\Backend", "Contoso"))
        var failWith: AdoException? = null
        var gate: CompletableDeferred<Unit>? = null
        var pipelineCalls = 0
        val runCalls = mutableListOf<Int>()

        override suspend fun pipelines(project: String): List<Pipeline> {
            pipelineCalls++
            gate?.await()
            failWith?.let { throw it }
            return pipelines
        }
        override suspend fun pipelineRuns(project: String, definitionId: Int, top: Int): List<Build> {
            runCalls += definitionId
            return listOf(Fakes.build(981, definition = definitionId))
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

    @Test fun `read on open, shown at once the next time while read again`() = runTest {
        val api = Api()
        val controller = PipelinesController(this, { api }, { it.message.orEmpty() })
        controller.open("Contoso")
        assertNull(controller.state.value!!.pipelines)
        assertTrue(controller.state.value!!.refreshing)
        advanceUntilIdle()
        assertEquals(listOf(5), controller.state.value!!.pipelines!!.map { it.id })
        assertFalse(controller.state.value!!.refreshing)
        controller.close()
        assertNull(controller.state.value)

        api.pipelines = api.pipelines + Pipeline(6, "web-ci", "\\", "Contoso")
        api.gate = CompletableDeferred()
        controller.open("contoso")
        assertEquals("the session's copy meanwhile", listOf(5), controller.state.value!!.pipelines!!.map { it.id })
        assertTrue(controller.state.value!!.refreshing)
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(5, 6), controller.state.value!!.pipelines!!.map { it.id })
        assertEquals(2, api.pipelineCalls)
    }

    @Test fun `runs read once per opening`() = runTest {
        val api = Api()
        val controller = PipelinesController(this, { api }, { it.message.orEmpty() })
        controller.open("Contoso")
        advanceUntilIdle()
        controller.loadRuns(5)
        assertEquals(RunsState.Loading, controller.state.value!!.runs[5])
        controller.loadRuns(5)
        advanceUntilIdle()
        assertEquals(listOf(981), (controller.state.value!!.runs[5] as RunsState.Loaded).runs.map { it.id })
        controller.loadRuns(5)
        advanceUntilIdle()
        assertEquals(listOf(5), api.runCalls)
        controller.open("Contoso")
        advanceUntilIdle()
        assertTrue("a new opening reads them again", controller.state.value!!.runs.isEmpty())
    }

    @Test fun `refused builds say so, other errors can be retried`() = runTest {
        val api = Api().apply { failWith = AdoException(403, "no") }
        val controller = PipelinesController(this, { api }, { "error: ${it.message}" })
        controller.open("Contoso")
        advanceUntilIdle()
        assertTrue(controller.state.value!!.denied)
        assertNull(controller.state.value!!.error)

        api.failWith = AdoException(500, "down")
        controller.retry()
        advanceUntilIdle()
        assertFalse(controller.state.value!!.denied)
        assertEquals("error: down", controller.state.value!!.error)

        api.failWith = null
        controller.retry()
        advanceUntilIdle()
        assertNull(controller.state.value!!.error)
        assertEquals(1, controller.state.value!!.pipelines!!.size)
    }
}
