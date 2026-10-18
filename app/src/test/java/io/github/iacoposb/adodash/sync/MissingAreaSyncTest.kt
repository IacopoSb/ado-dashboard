package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import io.github.iacoposb.adodash.data.Filters
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A global filter on an area renamed or deleted in Azure DevOps, which then refuses the query. */
class MissingAreaSyncTest {
    private class FakeApi : AdoApi {
        val wiqls = mutableListOf<String>()
        val items = listOf(Fakes.workItem(1, area = "Contoso\\Backend"))
        val mine = listOf(Fakes.pr(318, Fakes.me))
        override suspend fun me() = Fakes.me
        override suspend fun projects() = listOf("Contoso")
        override suspend fun queryWorkItemIds(wiql: String, top: Int): List<Int> {
            wiqls += wiql
            if ("Contoso\\Gone" in wiql) throw AdoException(400, "HTTP 400: TF51011: The specified area path does not exist.")
            return items.map { it.id }
        }
        override suspend fun workItems(ids: List<Int>) = items.filter { it.id in ids }
        override suspend fun stateCategories(project: String, type: String) = mapOf("Active" to "InProgress")
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter) =
            if (filter is PrFilter.Creator) mine else emptyList()
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()
    }

    private val filters = Filters(areas = setOf("Contoso\\Backend", "Contoso\\Gone"))

    @Test fun `the areas the saved catalog does not know are left out and the query runs again`() = runTest {
        val api = FakeApi()
        val fetched = SyncEngine(api, texts = Fakes.Texts, catalog = { Fakes.catalog }).fetch(Fakes.me, since = null, filters = filters)
        assertEquals(listOf(1), fetched.snapshot.workItems.map { it.id })
        assertEquals(listOf(318), fetched.snapshot.myPrs.map { it.id })
        assertEquals(listOf("missing area"), fetched.snapshot.warnings)
        assertTrue("Contoso\\Gone" !in api.wiqls.last())
        assertTrue("Contoso\\Backend" in api.wiqls.last())
    }

    @Test fun `without a saved catalog the pull requests still show, with the warning`() = runTest {
        val api = FakeApi()
        val fetched = SyncEngine(api, texts = Fakes.Texts).fetch(Fakes.me, since = null, filters = filters)
        assertTrue(fetched.snapshot.workItems.isEmpty())
        assertEquals(listOf(318), fetched.snapshot.myPrs.map { it.id })
        assertEquals(listOf("missing area"), fetched.snapshot.warnings)
    }

    @Test fun `other refusals still fail the sync`() = runTest {
        val api = object : AdoApi by FakeApi() {
            override suspend fun queryWorkItemIds(wiql: String, top: Int): List<Int> = throw AdoException(401, "no")
        }
        val failure = runCatching { SyncEngine(api, texts = Fakes.Texts).fetch(Fakes.me, since = null, filters = filters) }
        assertEquals(401, (failure.exceptionOrNull() as? AdoException)?.code)
    }
}
