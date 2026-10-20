package io.github.iacoposb.adodash.data

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.WorkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewsTest {
    private val board = BoardRef("Contoso", "Backend")
    private val bugs = DashboardView(
        "bugs", "Urgent bugs",
        workItems = WorkItemsPanel(
            conditions = listOf(
                Condition(WiField.TYPE, values = listOf("Bug")),
                Condition.assignedTo(Condition.ANYONE),
                Condition.notClosed,
            ),
            order = WiOrder.CHANGED,
        ),
        pullRequests = PullRequestsPanel(enabled = false),
        activity = ActivityPanel(kinds = setOf(ActivityKind.MENTIONS)),
    )

    @Test fun `nothing stored is the global view alone`() {
        for (stored in listOf(null, "", "not json", "{\"views\": 3}")) {
            val set = ViewSet.decode(stored)
            assertEquals(listOf(DashboardView.global), set.saved)
            assertEquals(DashboardView.GLOBAL_ID, set.current.id)
            assertFalse(set.alwaysOpen)
        }
    }

    @Test fun `the global view is as before views`() {
        val global = DashboardView.global
        assertTrue(global.workItems.enabled && global.pullRequests.enabled && global.activity.enabled)
        assertEquals(setOf(PrScope.TO_REVIEW, PrScope.MINE), global.pullRequests.show)
        assertEquals(Condition.ME, global.workItems.assignee)
        assertTrue(global.workItems.withinMine)
        assertTrue(global.activity.showsAll)
        assertEquals(ViewNeeds(workItemQuery = false, allPullRequests = false, teamAreas = false), global.needs())
    }

    @Test fun `views survive a round trip`() {
        val set = ViewSet().put(bugs).openBoard(board).copy(alwaysOpen = true, chosenAtLaunch = true)
        val back = ViewSet.decode(set.encode())
        assertEquals(set.encode(), back.encode())
        assertEquals(board.viewId, back.current.id)
        assertEquals(bugs, back.find("bugs"))
        assertTrue(back.alwaysOpen)
        assertEquals("boards are not among my views", listOf(DashboardView.GLOBAL_ID, "bugs"), back.saved.map { it.id })
    }

    @Test fun `a stored set without the global view gets it back first`() {
        val set = ViewSet.decode(ViewSet(views = listOf(bugs), currentId = "bugs").encode())
        assertEquals(listOf(DashboardView.GLOBAL_ID, "bugs"), set.saved.map { it.id })
        assertEquals("bugs", set.current.id)
    }

    @Test fun `fields added later keep their defaults`() {
        val set = ViewSet.decode("""{"views":[{"id":"global"},{"id":"x","name":"Only PRs","workItems":{"enabled":false}}],"currentId":"x"}""")
        val x = set.current
        assertFalse(x.workItems.enabled)
        assertEquals(DashboardView.global.pullRequests, x.pullRequests)
        assertTrue(x.activity.kinds.contains(ActivityKind.BUILDS_FAILED))
    }

    @Test fun `the global view cannot be deleted, deleting the open view opens it`() {
        val set = ViewSet().put(bugs)
        assertEquals(set, set.delete(DashboardView.GLOBAL_ID))
        val deleted = set.delete("bugs")
        assertEquals(DashboardView.GLOBAL_ID, deleted.current.id)
        assertEquals(listOf(DashboardView.GLOBAL_ID), deleted.saved.map { it.id })
    }

    @Test fun `editing the global view keeps it the global view`() {
        val edited = DashboardView.global.copy(name = "Everything", pullRequests = PullRequestsPanel(drafts = false))
        val set = ViewSet().put(edited)
        assertEquals(1, set.saved.size)
        assertEquals("Everything", set.global.name)
        assertEquals(set.global, set.delete(DashboardView.GLOBAL_ID).global)
    }

    @Test fun `a board opens with the global panels and keeps its edits`() {
        val fresh = ViewSet().board(board)
        assertEquals(DashboardView.global.workItems, fresh.workItems)
        assertEquals(board, fresh.board)
        val edited = fresh.copy(pullRequests = PullRequestsPanel(show = setOf(PrScope.ALL)))
        assertEquals(edited, ViewSet().put(edited).openBoard(board).current)
    }

    @Test fun `what each view needs from the sync`() {
        assertEquals(ViewNeeds(workItemQuery = true, allPullRequests = false, teamAreas = false), bugs.needs())
        val all = DashboardView("all", "All", pullRequests = PullRequestsPanel(show = setOf(PrScope.ALL)))
        assertEquals(ViewNeeds(workItemQuery = false, allPullRequests = true, teamAreas = false), all.needs())
        assertEquals(ViewNeeds(workItemQuery = false, allPullRequests = false, teamAreas = true), DashboardView.forBoard(board).needs())
        val boardAll = DashboardView.forBoard(board).copy(pullRequests = PullRequestsPanel(show = setOf(PrScope.ALL)))
        assertFalse("a board without repositories reads no other PRs", boardAll.needs().allPullRequests)
        val chosen = boardAll.copy(pullRequests = boardAll.pullRequests.copy(repositories = setOf("backend")))
        assertTrue(chosen.needs().allPullRequests)
        val closedToo = DashboardView("c", "Closed too", workItems = WorkItemsPanel(conditions = listOf(Condition.assignedTo(Condition.ME))))
        assertTrue("closed ones are not among those read anyway", closedToo.needs().workItemQuery)
        val anna = DashboardView("a", "Anna", workItems = WorkItemsPanel(conditions = listOf(Condition.assignedTo("Anna Verdi"), Condition.notClosed)))
        assertTrue(anna.needs().workItemQuery)
        val off = bugs.copy(workItems = bugs.workItems.copy(enabled = false))
        assertFalse("a panel switched off reads nothing", off.needs().workItemQuery)
    }

    @Test fun `the query key ignores the name and the activity`() {
        assertEquals(bugs.queryKey, bugs.copy(id = "other", name = "Renamed", activity = ActivityPanel()).queryKey)
        assertNotEquals(bugs.queryKey, bugs.copy(workItems = bugs.workItems.copy(order = WiOrder.PRIORITY)).queryKey)
        assertNotEquals(DashboardView.global.queryKey, DashboardView.forBoard(board).queryKey)
    }

    private val closed: (WorkItem) -> Boolean = { it.state == "Closed" || it.state == "Removed" }

    @Test fun `conditions combine with and`() {
        val panel = bugs.workItems
        val me = Fakes.me
        assertTrue(panel.accepts(Fakes.workItem(1, type = "Bug", state = "Active"), me, closed))
        assertFalse(panel.accepts(Fakes.workItem(2, type = "Task", state = "Active"), me, closed))
        assertFalse(panel.accepts(Fakes.workItem(3, type = "bug", state = "Closed"), me, closed))
    }

    @Test fun `condition evaluation`() {
        val me = Fakes.me
        val mine = Fakes.workItem(1, type = "Task", state = "Active")
        val annas = mine.copy(assignedTo = "Anna Verdi")
        val unassigned = mine.copy(assignedTo = null)
        val toMe = Condition.assignedTo(Condition.ME)
        assertTrue(toMe.accepts(mine, me, closed))
        assertFalse(toMe.accepts(annas, me, closed))
        assertFalse(toMe.accepts(unassigned, me, closed))
        assertTrue("the query chose them", toMe.accepts(annas, me, closed, checkAssignee = false))
        assertTrue(Condition.assignedTo("anna verdi").accepts(annas, me, closed))
        assertTrue(Condition.assignedTo(Condition.ANYONE).accepts(unassigned, me, closed))

        val notBugs = Condition(WiField.TYPE, negate = true, values = listOf("Bug", "Epic"))
        assertTrue(notBugs.accepts(mine, me, closed))
        assertFalse(notBugs.accepts(mine.copy(type = "epic"), me, closed))

        val activeOrClosed = Condition(WiField.STATE, values = listOf("Active", Condition.CLOSED))
        assertTrue(activeOrClosed.accepts(mine, me, closed))
        assertTrue(activeOrClosed.accepts(mine.copy(state = "Removed"), me, closed))
        assertFalse(activeOrClosed.accepts(mine.copy(state = "New"), me, closed))
        assertFalse(Condition.notClosed.accepts(mine.copy(state = "Closed"), me, closed))

        assertTrue("no values, no constraint", Condition(WiField.TYPE).accepts(mine, me, closed))
    }

    @Test fun `ordering`() {
        val items = listOf(
            Fakes.workItem(1, priority = 2, ago = 10),
            Fakes.workItem(2, priority = 1, ago = 500),
            Fakes.workItem(3, priority = null, ago = 1),
            Fakes.workItem(4, priority = 1, ago = 100),
        )
        assertEquals(listOf(4, 2, 1, 3), WorkItemsPanel(order = WiOrder.PRIORITY).sort(items).map { it.id })
        assertEquals(listOf(3, 1, 4, 2), WorkItemsPanel(order = WiOrder.CHANGED).sort(items).map { it.id })
    }

    @Test fun `pull requests by repository and drafts`() {
        val panel = PullRequestsPanel(repositories = setOf("Backend"), drafts = false)
        assertTrue(panel.accepts(Fakes.pr(1, Fakes.anna)))
        assertFalse(panel.accepts(Fakes.pr(2, Fakes.anna, draft = true)))
        assertFalse(panel.accepts(Fakes.pr(3, Fakes.anna).copy(repoName = "payments")))
    }

    @Test fun `a board shows only the repositories chosen for it, none until then`() {
        val fresh = DashboardView.forBoard(board)
        assertTrue(fresh.needsRepositories)
        assertFalse(fresh.acceptsRepository("backend"))
        assertFalse(fresh.acceptsPullRequest(Fakes.pr(1, Fakes.anna)))
        // Every scope: to review, mine and all go through the same check
        val chosen = fresh.copy(pullRequests = fresh.pullRequests.copy(repositories = setOf("Backend")))
        assertFalse(chosen.needsRepositories)
        assertTrue(chosen.acceptsRepository("backend"))
        assertFalse(chosen.acceptsRepository("payments"))
        assertTrue(chosen.acceptsPullRequest(Fakes.pr(2, Fakes.anna)))
        assertFalse(chosen.acceptsPullRequest(Fakes.pr(3, Fakes.anna).copy(repoName = "payments")))
        // Global and saved views as before: none chosen is all of them
        assertFalse(DashboardView.global.needsRepositories)
        assertTrue(DashboardView.global.acceptsRepository("anything"))
        assertTrue(DashboardView.global.acceptsPullRequest(Fakes.pr(4, Fakes.anna).copy(repoName = "payments")))
    }

    @Test fun `a board saved before its repositories were chosen asks for them`() {
        // As stored by 0.1.0: a board with its pull requests panel and no repositories
        val stored = ViewSet().openBoard(board).encode()
        val view = ViewSet.decode(stored).board(board)
        assertTrue(view.pullRequests.repositories.isEmpty())
        assertTrue(view.needsRepositories)
    }

    @Test fun `events by kind and, for a board, by project`() {
        fun event(kind: EventKind, project: String?) = Event("k", kind, "", "", "", 0, project = project)
        assertTrue(bugs.acceptsEvent(event(EventKind.MENTION, "Interno")))
        assertFalse(bugs.acceptsEvent(event(EventKind.PR_VOTE, "Contoso")))
        val boardView = DashboardView.forBoard(board)
        assertTrue(boardView.acceptsEvent(event(EventKind.PR_VOTE, "contoso")))
        assertFalse(boardView.acceptsEvent(event(EventKind.PR_VOTE, "Interno")))
        assertFalse("before 0.0.4 events had no project", boardView.acceptsEvent(event(EventKind.PR_VOTE, null)))
        assertTrue(DashboardView.global.acceptsEvent(event(EventKind.ASSIGNED, null)))
    }
}
