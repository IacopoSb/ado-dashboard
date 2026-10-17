package io.github.iacoposb.adodash.ui

import androidx.compose.ui.text.font.FontWeight
import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.data.ViewSet
import io.github.iacoposb.adodash.sync.CatalogState
import io.github.iacoposb.adodash.sync.TeamInfo
import io.github.iacoposb.adodash.ui.components.highlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardMenuTest {
    private val boards = listOf(
        ProjectBoards("Contoso", listOf(TeamInfo("Backend"), TeamInfo("Frontend"), TeamInfo("Mobile"))),
        ProjectBoards("Interno", listOf(TeamInfo("Infrastruttura"), TeamInfo("Contabilità"))),
        ProjectBoards("Payments", listOf(TeamInfo("Gateway"))),
    )
    private val recent = listOf(BoardRef("Interno", "Infrastruttura"), BoardRef("Contoso", "Backend"))

    @Test fun `without a search only the expanded projects are open, after the recent boards`() {
        val menu = boardMenu(boards, recent, "", setOf("Contoso"))
        assertEquals(recent, menu.recent)
        assertEquals(listOf("Contoso", "Interno", "Payments"), menu.groups.map { it.project })
        assertEquals(listOf(true, false, false), menu.groups.map { it.expanded })
        assertEquals(listOf(3, 2, 1), menu.groups.map { it.total })
        assertEquals(3, menu.groups.first().teams.size)
    }

    @Test fun `only the project of the board open is expanded at first`() {
        assertEquals(setOf("Interno"), initialExpanded(BoardRef("Interno", "Infrastruttura")))
        assertEquals(emptySet<String>(), initialExpanded(null))
    }

    @Test fun `a search lists the matching teams of every project, all open, without the recent ones`() {
        val menu = boardMenu(boards, recent, "  END ", emptySet())
        assertTrue(menu.searching)
        assertEquals("END", menu.query)
        assertEquals(emptyList<BoardRef>(), menu.recent)
        assertEquals(listOf("Contoso"), menu.groups.map { it.project })
        val contoso = menu.groups.single()
        assertEquals(listOf("Backend", "Frontend"), contoso.teams.map { it.name })
        assertEquals(3, contoso.total)
        assertTrue(contoso.expanded)
    }

    @Test fun `a project whose name matches keeps all of its teams`() {
        val menu = boardMenu(boards, recent, "cont", emptySet())
        val contoso = menu.groups.first { it.project == "Contoso" }
        assertTrue(contoso.projectMatch)
        assertEquals(3, contoso.teams.size)
        // "Contabilità" matches as a team, not its project
        val interno = menu.groups.first { it.project == "Interno" }
        assertEquals(listOf("Contabilità"), interno.teams.map { it.name })
        assertTrue(!interno.projectMatch)
    }

    @Test fun `a search with no match leaves nothing`() {
        val menu = boardMenu(boards, recent, "zzz", emptySet())
        assertTrue(menu.isEmpty)
    }

    @Test fun `the match is in bold, whatever its case`() {
        val text = highlight("Frontend", "END")
        assertEquals("Frontend", text.text)
        val span = text.spanStyles.single()
        assertEquals(5, span.start)
        assertEquals(8, span.end)
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertTrue(highlight("Frontend", "x").spanStyles.isEmpty())
        assertTrue(highlight("Frontend", null).spanStyles.isEmpty())
    }

    @Test fun `opening boards keeps the last three, most recent first, without repeats`() {
        val a = BoardRef("Contoso", "Backend")
        val b = BoardRef("Contoso", "Frontend")
        val c = BoardRef("Interno", "Infrastruttura")
        val d = BoardRef("Payments", "Gateway")
        var set = ViewSet().openBoard(a).openBoard(b).openBoard(c).openBoard(a)
        assertEquals(listOf(a, c, b), set.recentBoards)
        set = set.openBoard(d)
        assertEquals(listOf(d, a, c), set.recentBoards)
        // Saved views do not count; a board view opened by its id does
        set = set.open(DashboardView.GLOBAL_ID).open(c.viewId)
        assertEquals(listOf(c, d, a), set.recentBoards)
        assertEquals(set.recentBoards, ViewSet.decode(set.encode()).recentBoards)
    }

    @Test fun `the boards are loading until a catalog with teams arrives or reading it fails`() {
        val views = ViewSet()
        val filters = Filters()
        // Before the read starts, and while it runs
        assertNull(viewChoices(views, CatalogState(), filters, null).boards)
        assertNull(viewChoices(views, CatalogState(loading = true), filters, null).boards)
        // A catalog saved before the views: it has no teams yet
        val old = Fakes.catalog.copy(teams = emptyMap(), version = 1)
        assertNull(viewChoices(views, CatalogState(old, loading = true), filters, null).boards)
        assertNull(viewChoices(views, CatalogState(old), filters, null).boards)
        // Loaded
        val loaded = viewChoices(views, CatalogState(Fakes.catalog), filters, null)
        assertEquals(listOf("Contoso", "Interno"), loaded.boards!!.map { it.project })
        assertNull(loaded.boardsError)
        // Failed: no boards, and why
        val failed = viewChoices(views, CatalogState(old, error = "offline"), filters, null)
        assertEquals(emptyList<ProjectBoards>(), failed.boards)
        assertEquals("offline", failed.boardsError)
    }

    @Test fun `recent boards outside the global filters are not offered`() {
        val views = ViewSet().openBoard(BoardRef("Interno", "Infrastruttura")).openBoard(BoardRef("Contoso", "Backend"))
        val filters = Filters(areas = setOf("Contoso"))
        val choices = viewChoices(views, CatalogState(Fakes.catalog), filters, null)
        assertEquals(listOf(BoardRef("Contoso", "Backend")), choices.recentBoards)
    }
}
