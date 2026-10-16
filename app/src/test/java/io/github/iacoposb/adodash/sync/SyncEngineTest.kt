package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.data.Filters
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {
    private class FakeApi(
        val items: List<WorkItem>,
        val categories: Map<String, String>?,
        val toReview: List<PullRequest> = emptyList(),
        val mine: List<PullRequest> = emptyList(),
    ) : AdoApi {
        var stateCalls = 0
        val wiqls = mutableListOf<String>()
        val prProjects = mutableListOf<String>()
        override suspend fun me() = Fakes.me
        override suspend fun projects() = listOf("Contoso", "Clienti")
        override suspend fun queryWorkItemIds(wiql: String, top: Int): List<Int> {
            wiqls += wiql
            return items.map { it.id }
        }
        override suspend fun workItems(ids: List<Int>) = items.filter { it.id in ids }
        override suspend fun stateCategories(project: String, type: String): Map<String, String> {
            stateCalls++
            return categories ?: error("403")
        }
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter): List<PullRequest> {
            prProjects += project
            return (if (filter is PrFilter.Reviewer) toReview else mine).filter { it.project == project }
        }
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

    @Test fun `filters reach the query and leave out what they hide`() = runTest {
        val items = listOf(
            Fakes.workItem(1, area = "Contoso\\Backend"),
            Fakes.workItem(2, area = "Contoso\\Mobile"),
            Fakes.workItem(3, type = "Epic", state = "Resolved", area = "Contoso\\Backend\\Gateway"),
        )
        val draft = Fakes.pr(10, Fakes.anna, draft = true)
        val approved = Fakes.pr(11, Fakes.anna, reviewers = listOf(Reviewer(Fakes.me.id, Fakes.me.name, Vote.APPROVED, false)))
        val open = Fakes.pr(12, Fakes.anna)
        val api = FakeApi(items, categories, toReview = listOf(draft, approved, open), mine = listOf(Fakes.pr(13, Fakes.me)))
        val filters = Filters(
            areas = setOf("Contoso\\Backend"),
            hiddenStates = mapOf("Epic" to setOf("Resolved")),
            draftsToReview = false,
            hideApprovedByMe = true,
        )
        val snapshot = SyncEngine(api, texts = Fakes.Texts).fetch(Fakes.me, since = null, filters = filters).snapshot

        assertEquals(listOf(1), snapshot.workItems.map { it.id })
        assertTrue(api.wiqls.single().contains("[System.AreaPath] UNDER 'Contoso\\Backend'"))
        assertTrue(api.wiqls.single().contains("[System.WorkItemType] <> 'Epic' OR [System.State] NOT IN ('Resolved')"))
        assertEquals("projects without a selected area are not read", setOf("Contoso"), api.prProjects.toSet())
        assertEquals(listOf(12), snapshot.reviewPrs.map { it.id })
        assertEquals(listOf(13), snapshot.myPrs.map { it.id })
    }

    @Test fun `nothing selected reads nothing`() = runTest {
        val api = FakeApi(items, categories)
        val snapshot = SyncEngine(api, texts = Fakes.Texts).fetch(Fakes.me, since = null, filters = Filters(areas = emptySet())).snapshot
        assertTrue(snapshot.workItems.isEmpty())
        assertTrue(api.wiqls.isEmpty())
        assertTrue(api.prProjects.isEmpty())
    }
}
