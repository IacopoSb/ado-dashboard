package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.StateInfo
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import io.github.iacoposb.adodash.data.CatalogCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private class FakeApi(val projects: List<String> = listOf("Contoso", "Clienti")) : AdoApi {
        var areaCalls = 0
        /** Completed to let the reads finish. */
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        override suspend fun me() = Fakes.me
        override suspend fun projects(): List<String> {
            gate?.await()
            if (fail) error("offline")
            return projects
        }
        override suspend fun queryWorkItemIds(wiql: String, top: Int) = emptyList<Int>()
        override suspend fun workItems(ids: List<Int>) = emptyList<WorkItem>()
        override suspend fun stateCategories(project: String, type: String) = emptyMap<String, String>()
        override suspend fun areas(project: String): AreaNode {
            areaCalls++
            return AreaNode(project, project)
        }
        override suspend fun workItemTypes(project: String) =
            listOf(WorkItemTypeInfo("Bug", null, listOf(StateInfo("Active", "InProgress"))))
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter) = emptyList<PullRequest>()
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()
    }

    private fun TestScope.repository(cache: CatalogCache, now: Long = DAY * 10) =
        // Not backgroundScope: advanceUntilIdle leaves its work alone
        CatalogRepository(cache, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), { it.message.orEmpty() }, now = { now })

    private fun TestScope.cache() =
        CatalogCache(File(folder.root, "catalog.json"), StandardTestDispatcher(testScheduler))

    @Test fun `the saved catalog shows at once, then the fresh one replaces it`() = runTest {
        val cache = cache()
        cache.saveCatalog("contoso", Fakes.catalog, at = 1)
        cache.saveUsedTypes("contoso", setOf("Bug"))
        val api = FakeApi().apply { gate = CompletableDeferred() }
        val repo = repository(cache)

        repo.open("contoso") { api }
        advanceUntilIdle()
        assertEquals(Fakes.catalog, repo.state.value.catalog)
        assertEquals(setOf("Bug"), repo.state.value.usedTypes)
        assertTrue(repo.state.value.refreshing)

        api.gate!!.complete(Unit)
        advanceUntilIdle()
        val fresh = repo.state.value
        assertFalse(fresh.loading)
        assertEquals(listOf("Clienti", "Contoso"), fresh.catalog!!.projects.map { it.name }.sorted())
        assertEquals(fresh.catalog, cache.load("contoso")!!.catalog)
    }

    @Test fun `a failed refresh keeps the saved catalog`() = runTest {
        val cache = cache()
        cache.saveCatalog("contoso", Fakes.catalog, at = 1)
        val repo = repository(cache)
        repo.open("contoso") { FakeApi().apply { fail = true } }
        advanceUntilIdle()
        assertEquals(Fakes.catalog, repo.state.value.catalog)
        assertEquals("offline", repo.state.value.error)
        assertFalse(repo.state.value.loading)
    }

    @Test fun `after a sync the catalog is read again only once it is a day old`() = runTest {
        val cache = cache()
        val api = FakeApi()
        cache.saveCatalog("contoso", Fakes.catalog, at = DAY * 10 - 1_000)
        repository(cache).synced("contoso", setOf("Task")) { api }
        advanceUntilIdle()
        assertEquals(0, api.areaCalls)
        assertEquals(setOf("Task"), cache.load("contoso")!!.usedTypes)

        cache.saveCatalog("contoso", Fakes.catalog, at = DAY * 9 - 1_000)
        repository(cache).synced("contoso", setOf("Task")) { api }
        advanceUntilIdle()
        assertEquals(2, api.areaCalls)
    }

    @Test fun `a catalog saved before the views is read again, for the teams`() = runTest {
        val cache = cache()
        val api = FakeApi()
        cache.saveCatalog("contoso", Fakes.catalog.copy(teams = emptyMap(), version = 1), at = DAY * 10 - 1_000)
        repository(cache).synced("contoso", setOf("Task")) { api }
        advanceUntilIdle()
        assertEquals(2, api.areaCalls)
        assertEquals(FilterCatalog.VERSION, cache.load("contoso")!!.catalog!!.version)
    }

    @Test fun `the view pickers read the catalog only when none is saved`() = runTest {
        val cache = cache()
        val api = FakeApi()
        cache.saveCatalog("contoso", Fakes.catalog, at = 0)
        val repo = repository(cache)
        repo.openForViews("contoso") { api }
        advanceUntilIdle()
        assertEquals("even an old one is good enough to pick a board", 0, api.areaCalls)
        assertEquals(Fakes.catalog.teams, repo.state.value.catalog!!.teams)

        val empty = repository(CatalogCache(File(folder.root, "empty.json"), StandardTestDispatcher(testScheduler)))
        empty.openForViews("contoso") { api }
        advanceUntilIdle()
        assertEquals(2, api.areaCalls)
    }

    @Test fun `forgetting drops the saved catalog`() = runTest {
        val cache = cache()
        val repo = repository(cache)
        repo.open("contoso") { FakeApi() }
        advanceUntilIdle()
        assertTrue(repo.state.value.catalog != null)

        repo.forget()
        assertNull(repo.state.value.catalog)
        assertNull(cache.load("contoso"))
    }

    private companion object {
        const val DAY = CatalogRepository.DAY_MILLIS
    }
}
