package io.github.iacoposb.adodash.data

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.AreaNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaSelectionTest {
    private val roots = Fakes.projects
    private fun node(path: String): AreaNode {
        fun find(n: AreaNode): AreaNode? = if (n.path == path) n else n.children.firstNotNullOfOrNull(::find)
        return roots.firstNotNullOf(::find)
    }
    private fun sel(selected: Set<String>?) = AreaSelection(roots, selected)

    @Test fun `everything is selected by default`() {
        val s = sel(null)
        assertEquals(Check.ON, s.check(node("Contoso\\Backend\\Gateway")))
        assertEquals(4, s.selectedProjects)
        assertEquals(8 + 4 + 6, s.selectedAreas)
    }

    @Test fun `a selected node checks its subtree, its ancestors are partial`() {
        val s = sel(Fakes.designAreas)
        assertEquals(Check.PARTIAL, s.check(node("Contoso")))
        assertEquals(Check.ON, s.check(node("Contoso\\Backend")))
        assertEquals(Check.ON, s.check(node("Contoso\\Backend\\Pagamenti")))
        assertEquals(Check.PARTIAL, s.check(node("Contoso\\Frontend")))
        assertEquals(Check.OFF, s.check(node("Contoso\\Mobile")))
        assertEquals(Check.OFF, s.check(node("Interno")))
        assertEquals(5, s.selectedAreaCount(node("Contoso")))
        assertEquals(8, s.areaCount(node("Contoso")))
        assertEquals(0, s.areaCount(node("Formazione")))
        assertEquals(1, s.selectedProjects)
        assertEquals(5, s.selectedAreas)
    }

    @Test fun `unchecking an area of a whole project keeps its siblings`() {
        val after = sel(null).toggle(node("Contoso\\Mobile"))
        assertEquals(setOf("Contoso\\Backend", "Contoso\\Frontend", "Interno", "Clienti", "Formazione"), after)
    }

    @Test fun `unchecking a leaf splits its parent`() {
        val after = sel(setOf("Contoso\\Backend")).toggle(node("Contoso\\Backend\\Gateway"))
        assertEquals(setOf("Contoso\\Backend\\Autenticazione", "Contoso\\Backend\\Pagamenti"), after)
    }

    @Test fun `checking the last child selects the parent, which then includes future areas`() {
        val start = setOf("Contoso\\Frontend\\Portale", "Contoso\\Frontend\\Web")
        val after = sel(start).toggle(node("Contoso\\Frontend\\Design system"))
        assertEquals(setOf("Contoso\\Frontend"), after)
    }

    @Test fun `checking a partial node checks all of it`() {
        val after = sel(Fakes.designAreas).toggle(node("Contoso"))
        assertEquals(setOf("Contoso"), after)
    }

    @Test fun `every project whole is everything again, future projects included`() {
        val almost = roots.filter { it.name != "Formazione" }.map { it.path }.toSet()
        assertNull(sel(almost).toggle(node("Formazione")))
    }

    @Test fun `unchecking the only selection leaves nothing`() {
        assertEquals(emptySet<String>(), sel(setOf("Interno")).toggle(node("Interno")))
    }

    @Test fun `long press selects only that node`() {
        assertEquals(setOf("Clienti\\Acme"), sel(null).only(node("Clienti\\Acme")))
        assertEquals(setOf("Formazione"), sel(Fakes.designAreas).only(node("Formazione")))
    }

    @Test fun `paths not in the tree any more are dropped on the next change`() {
        val after = sel(setOf("Contoso\\Old area", "Interno")).toggle(node("Formazione"))
        assertEquals(setOf("Interno", "Formazione"), after)
    }

    @Test fun `search shows matches with their ancestors`() {
        val result = sel(null).search(" pag ")!!
        assertEquals(listOf("Contoso\\Backend\\Pagamenti", "Clienti\\Portale pagamenti"), result.matches.map { it.path })
        assertEquals(setOf("Contoso", "Contoso\\Backend", "Contoso\\Backend\\Pagamenti", "Clienti", "Clienti\\Portale pagamenti"), result.visible)
        assertEquals(1, result.matchesUnder(node("Contoso")))
        assertNull(sel(null).search("  "))
    }

    @Test fun `select and deselect results touch only the matches`() {
        val s = sel(setOf("Contoso\\Backend"))
        val matches = s.search("pag")!!.matches
        assertEquals(setOf("Contoso\\Backend", "Clienti\\Portale pagamenti"), s.set(matches, on = true))
        assertEquals(setOf("Contoso\\Backend\\Autenticazione", "Contoso\\Backend\\Gateway"), s.set(matches, on = false))
    }

    @Test fun `matching is case insensitive like Azure DevOps`() {
        assertTrue(sel(setOf("contoso\\backend")).covers("Contoso\\Backend\\Gateway"))
    }
}
