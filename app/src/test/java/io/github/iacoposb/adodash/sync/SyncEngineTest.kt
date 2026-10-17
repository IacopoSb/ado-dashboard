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
import io.github.iacoposb.adodash.api.TeamArea
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.data.ActivityPanel
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.Condition
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.data.PrScope
import io.github.iacoposb.adodash.data.PullRequestsPanel
import io.github.iacoposb.adodash.data.WiField
import io.github.iacoposb.adodash.data.WorkItemsPanel
import io.github.iacoposb.adodash.data.Event
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
        val all: List<PullRequest> = emptyList(),
        /** What a query without @Me finds: the views' own queries. */
        val others: List<WorkItem> = emptyList(),
        val areas: List<TeamArea>? = null,
    ) : AdoApi {
        var stateCalls = 0
        var teamAreaCalls = 0
        val allProjects = mutableListOf<String>()
        val wiqls = mutableListOf<String>()
        val prProjects = mutableListOf<String>()
        override suspend fun me() = Fakes.me
        override suspend fun projects() = listOf("Contoso", "Clienti")
        override suspend fun queryWorkItemIds(wiql: String, top: Int): List<Int> {
            wiqls += wiql
            return (if ("@Me" in wiql) items else others).map { it.id }
        }
        override suspend fun workItems(ids: List<Int>) = (items + others).filter { it.id in ids }
        override suspend fun teamAreas(project: String, team: String): List<TeamArea>? {
            teamAreaCalls++
            return areas
        }
        override suspend fun stateCategories(project: String, type: String): Map<String, String> {
            stateCalls++
            return categories ?: error("403")
        }
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter): List<PullRequest> {
            if (filter == PrFilter.All) {
                allProjects += project
                return all.filter { it.project == project }
            }
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

    @Test fun `the global view shows my items without another query`() = runTest {
        val api = FakeApi(items, categories, toReview = listOf(Fakes.pr(10, Fakes.anna)), mine = listOf(Fakes.pr(11, Fakes.me)))
        val snapshot = SyncEngine(api, texts = Fakes.Texts).fetch(Fakes.me, since = null).snapshot
        val shown = snapshot.shown!!
        assertEquals(1, api.wiqls.size)
        assertEquals(snapshot.workItems, shown.workItems)
        assertEquals(listOf(10), shown.reviewPrs.map { it.id })
        assertEquals(listOf(11), shown.myPrs.map { it.id })
        assertTrue(api.allProjects.isEmpty())
        assertEquals(shown, snapshot.shownFor(DashboardView.global))
        assertEquals("another view's data is not shown", null, snapshot.shownFor(DashboardView("x", "X", activity = ActivityPanel(), pullRequests = PullRequestsPanel(drafts = false))))
    }

    @Test fun `a view of someone else's work reads it, the notifications stay mine`() = runTest {
        val annas = listOf(
            Fakes.workItem(20, type = "Bug", state = "Active").copy(assignedTo = "Anna Verdi"),
            Fakes.workItem(21, type = "Bug", state = "Resolved").copy(assignedTo = "Anna Verdi"),
            Fakes.workItem(22, type = "Bug", state = "New", area = "Contoso\\Hidden").copy(assignedTo = "Anna Verdi"),
        )
        val view = DashboardView(
            "anna", "Anna's bugs",
            workItems = WorkItemsPanel(
                conditions = listOf(Condition(WiField.TYPE, values = listOf("Bug")), Condition.assignedTo("Anna Verdi"), Condition.notClosed),
            ),
            pullRequests = PullRequestsPanel(show = setOf(PrScope.ALL), drafts = false),
        )
        val others = listOf(Fakes.pr(30, Fakes.anna), Fakes.pr(31, Fakes.marco, draft = true), Fakes.pr(32, Fakes.marco, project = "Clienti"))
        val api = FakeApi(items, categories, toReview = listOf(Fakes.pr(30, Fakes.anna)), mine = listOf(Fakes.pr(33, Fakes.me)), all = others, others = annas)
        val filters = Filters(hiddenStates = mapOf("Bug" to setOf("New")))
        val fetched = SyncEngine(api, texts = Fakes.Texts).fetch(Fakes.me, since = null, filters = filters, view = view)
        val shown = fetched.snapshot.shown!!

        assertEquals(listOf(20), shown.workItems.map { it.id })
        val viewWiql = api.wiqls.single { "Anna" in it }
        assertTrue("global filters reach the view's query too", viewWiql.contains("[System.WorkItemType] <> 'Bug'"))
        assertTrue(shown.reviewPrs.isEmpty() && shown.myPrs.isEmpty())
        assertEquals("all PRs, without drafts", listOf(30, 32), shown.otherPrs.map { it.id }.sorted())
        assertEquals(setOf("Contoso", "Clienti"), api.allProjects.toSet())

        // What notifies is still mine: my work items and PRs, never Anna's
        assertEquals(listOf(1, 2, 4), fetched.snapshot.workItems.map { it.id }.sorted())
        val events: List<Event> = Events.from(fetched, Fakes.now.minusSeconds(86_400), Fakes.Texts)
        assertTrue(events.none { it.key.startsWith("wi:20") || it.key.startsWith("pr:32") })
        assertEquals(listOf("pr:30:review"), events.filter { it.key.endsWith(":review") }.map { it.key })
    }

    @Test fun `a board shows the team's areas and the project's pull requests`() = runTest {
        val items = listOf(
            Fakes.workItem(1, area = "Contoso\\Backend"),
            Fakes.workItem(2, area = "Contoso\\Backend\\Gateway"),
            Fakes.workItem(3, area = "Contoso\\Frontend"),
            Fakes.workItem(4, area = "Clienti"),
        )
        val board = BoardRef("Contoso", "Backend")
        val api = FakeApi(
            items, categories,
            toReview = listOf(Fakes.pr(10, Fakes.anna), Fakes.pr(11, Fakes.anna, project = "Clienti")),
            mine = listOf(Fakes.pr(12, Fakes.me, project = "Clienti")),
            areas = listOf(TeamArea("Contoso\\Backend", includeChildren = true)),
        )
        val cache = mutableMapOf<BoardRef, List<TeamArea>>()
        val engine = SyncEngine(api, teamAreas = cache, texts = Fakes.Texts)
        val fetched = engine.fetch(Fakes.me, since = null, view = DashboardView.forBoard(board))
        val shown = fetched.snapshot.shown!!
        assertEquals(listOf(1, 2), shown.workItems.map { it.id }.sorted())
        assertEquals(listOf(10), shown.reviewPrs.map { it.id })
        assertTrue(shown.myPrs.isEmpty())
        assertEquals("my own items still all there", 4, fetched.snapshot.workItems.size)
        assertEquals(1, api.wiqls.size)
        engine.fetch(Fakes.me, since = null, view = DashboardView.forBoard(board))
        assertEquals("team areas are cached across syncs", 1, api.teamAreaCalls)
    }

    @Test fun `a team field other than the area path shows the whole project`() = runTest {
        val items = listOf(Fakes.workItem(1, area = "Contoso\\Backend"), Fakes.workItem(2, area = "Clienti"))
        val api = FakeApi(items, categories, areas = null)
        val shown = SyncEngine(api, texts = Fakes.Texts)
            .fetch(Fakes.me, since = null, view = DashboardView.forBoard(BoardRef("Contoso", "Backend"))).snapshot.shown!!
        assertEquals(listOf(1), shown.workItems.map { it.id })
    }
}
