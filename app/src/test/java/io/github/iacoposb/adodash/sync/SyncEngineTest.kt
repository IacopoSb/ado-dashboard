package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.WorkItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncEngineTest {
    private class FakeApi(val items: List<WorkItem>, val categories: Map<String, String>?) : AdoApi {
        var stateCalls = 0
        override suspend fun me() = Fakes.me
        override suspend fun projects() = listOf("Contoso")
        override suspend fun queryWorkItemIds(wiql: String, top: Int) = items.map { it.id }
        override suspend fun workItems(ids: List<Int>) = items.filter { it.id in ids }
        override suspend fun stateCategories(project: String, type: String): Map<String, String> {
            stateCalls++
            return categories ?: error("403")
        }
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter) = emptyList<PullRequest>()
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()
    }

    private val items = listOf(
        Fakes.workItem(1, state = "New"),
        Fakes.workItem(2, state = "Active"),
        Fakes.workItem(3, state = "Resolved"),
        Fakes.workItem(4, state = "Custom state"),
    )
    private val categories = mapOf("New" to "Proposed", "Active" to "InProgress", "Resolved" to "Resolved")

    @Test fun `only proposed and in progress work items, unknown states kept`() = runTest {
        val cache = mutableMapOf<String, Map<String, String>>()
        val api = FakeApi(items, categories)
        val ids = SyncEngine(api, cache, texts = Fakes.Texts).fetch(Fakes.me, since = null).snapshot.workItems.map { it.id }.sorted()
        assertEquals(listOf(1, 2, 4), ids)
        SyncEngine(api, cache, texts = Fakes.Texts).fetch(Fakes.me, since = null)
        assertEquals("categories are cached across syncs", 1, api.stateCalls)
    }

    @Test fun `unreadable states keep everything and warn`() = runTest {
        val snapshot = SyncEngine(FakeApi(items, null), texts = Fakes.Texts).fetch(Identity("x", "x"), since = null).snapshot
        assertEquals(4, snapshot.workItems.size)
        assertEquals(listOf("Task states: 403"), snapshot.warnings)
    }
}
