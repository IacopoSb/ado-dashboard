package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.Iteration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class PathTreeTest {
    private val now = Instant.parse("2026-10-16T10:00:00Z")

    private fun day(d: String) = Instant.parse("${d}T00:00:00Z")
    private fun iter(path: String, start: String? = null, finish: String? = null) =
        Iteration(path.substringAfterLast('\\'), path, start?.let(::day), finish?.let(::day))

    /** As the API lists them: the root first, then depth first in its own order. */
    private val iterations = listOf(
        iter("Progress Rail"),
        iter("Progress Rail\\Backlog"),
        iter("Progress Rail\\PTD"),
        iter("Progress Rail\\PTD\\PTD-S7", "2026-10-19", "2026-10-30"),
        iter("Progress Rail\\PTD\\PTD-S5", "2026-09-21", "2026-10-02"),
        iter("Progress Rail\\PTD\\PTD-S6", "2026-10-05", "2026-10-16"),
        iter("Progress Rail\\Release 1"),
        iter("Progress Rail\\Release 1\\Sprint 1", "2026-03-02", "2026-03-13"),
    )
    private val tree = PathTree.fromIterations("Progress Rail", iterations)

    private fun names(node: PathNode) = node.children.map { it.name }

    /** Node names, past sprints lines as "past N". */
    private fun List<PathLine>.names() = map { if (it is PathRow) it.node.name else "past ${(it as PastRow).count}" }
    private fun List<PathLine>.nodes() = filterIsInstance<PathRow>()

    @Test fun `the path below the project, the project alone by its name`() {
        assertEquals(listOf("PTD", "PTD-S6"), PathTree.segments("Progress Rail\\PTD\\PTD-S6"))
        assertEquals("PTD › PTD-S6", PathTree.label("Progress Rail\\PTD\\PTD-S6"))
        assertEquals("Release › Q4 › Sprint 3", PathTree.label("P\\Release\\Q4\\Sprint 3"))
        assertEquals("Progress Rail", PathTree.label("Progress Rail"))
        assertEquals("Contoso", PathTree.label("\\Contoso\\"))
        assertEquals(emptyList<String>(), PathTree.segments(""))
    }

    @Test fun `iterations as a tree, siblings by date and undated last`() {
        assertEquals("Progress Rail", tree.path)
        // Release 1 began in March, PTD in September, Backlog has no dates
        assertEquals(listOf("Release 1", "PTD", "Backlog"), names(tree))
        val ptd = tree.children[1]
        assertEquals("Progress Rail\\PTD", ptd.path)
        assertEquals(listOf("PTD-S5", "PTD-S6", "PTD-S7"), names(ptd))
        assertEquals(day("2026-10-05"), ptd.children[1].start)
    }

    @Test fun `missing folders are made up and the project's case kept`() {
        val t = PathTree.fromIterations("Contoso", listOf(iter("CONTOSO\\R2\\Sprint 9", "2026-10-12", "2026-10-23")))
        assertEquals(listOf("R2"), names(t))
        assertEquals("Contoso\\R2\\Sprint 9", t.children.single().children.single().path)
    }

    @Test fun `areas keep their tree`() {
        val area = AreaNode("Contoso", "Contoso", listOf(AreaNode("Backend", "Contoso\\Backend", listOf(AreaNode("Api", "Contoso\\Backend\\Api")))))
        val t = PathTree.fromArea(area)
        assertEquals("Contoso\\Backend\\Api", t.children.single().children.single().path)
        assertNull(t.start)
    }

    @Test fun `current includes the last day, past is over, folders by their sprints`() {
        val ptd = tree.children[1]
        val (s5, s6, s7) = ptd.children
        // PTD-S6 ends on the 16th: still current all that day
        assertTrue(PathTree.isCurrent(s6, now))
        assertFalse(PathTree.isCurrent(s5, now))
        assertFalse(PathTree.isCurrent(s7, now))
        assertFalse(PathTree.isCurrent(ptd, now))
        assertTrue(PathTree.isPast(s5, now))
        assertFalse(PathTree.isPast(s6, now))
        assertFalse(PathTree.isPast(s7, now))
        assertTrue(PathTree.isCurrent(s6, Instant.parse("2026-10-16T23:59:00Z")))
        assertFalse(PathTree.isCurrent(s6, Instant.parse("2026-10-17T00:00:01Z")))
        // A folder whose sprints are all over, and one with no dates at all
        assertTrue(PathTree.isPast(tree.children[0], now))
        assertFalse(PathTree.isPast(ptd, now))
        assertFalse(PathTree.isPast(tree.children[2], now))
    }

    @Test fun `dates in the language, with years only when not this year`() {
        val s6 = tree.children[1].children[1]
        assertEquals("5 Oct – 16 Oct", PathTree.dates(s6, now, Locale.ENGLISH))
        assertEquals("5 ott – 16 ott", PathTree.dates(s6, now, Locale.ITALIAN))
        val nextYear = PathNode("S", "P\\S", day("2026-12-28"), day("2027-01-08"))
        assertEquals("28 Dec 2026 – 8 Jan 2027", PathTree.dates(nextYear, now, Locale.ENGLISH))
        assertNull(PathTree.dates(tree, now, Locale.ENGLISH))
    }

    @Test fun `open at first the way to the selected and to the current iterations`() {
        assertEquals(
            setOf("Progress Rail", "Progress Rail\\PTD"),
            PathTree.initiallyExpanded(tree, "Progress Rail\\PTD\\PTD-S6", now),
        )
        // Selected elsewhere: both ways open, Release 1 being over its past sprints line too
        assertEquals(
            setOf("Progress Rail", PathTree.pastKey("Progress Rail"), "Progress Rail\\Release 1", "Progress Rail\\PTD"),
            PathTree.initiallyExpanded(tree, "progress rail\\release 1\\sprint 1", now),
        )
        // Areas have no current node; a path not in the tree opens only the root
        assertEquals(setOf("Progress Rail"), PathTree.initiallyExpanded(tree, "Progress Rail\\Gone", null))
    }

    @Test fun `rows of the expanded nodes`() {
        val rows = PathTree.rows(tree, setOf("Progress Rail", "Progress Rail\\PTD"), null)
        assertEquals(
            listOf("Progress Rail", "Release 1", "PTD", "PTD-S5", "PTD-S6", "PTD-S7", "Backlog"),
            rows.names(),
        )
        assertEquals(listOf(0, 1, 1, 2, 2, 2, 1), rows.map { it.level })
        val release = rows[1] as PathRow
        assertTrue(release.expandable)
        assertFalse(release.open)
        assertFalse((rows.last() as PathRow).expandable)
        assertTrue(rows.nodes().none { it.match })
        assertEquals(listOf("Progress Rail"), PathTree.rows(tree, emptySet(), null).names())
    }

    @Test fun `search keeps the ancestors open and marks the matches`() {
        val search = PathTree.search(tree, " s6 ")!!
        assertEquals("s6", search.query)
        val rows = PathTree.rows(tree, emptySet(), search, now).nodes()
        assertEquals(listOf("Progress Rail", "PTD", "PTD-S6"), rows.map { it.node.name })
        assertEquals(listOf(false, false, true), rows.map { it.match })
        assertTrue(rows.dropLast(1).all { it.open })

        // A folder and a sprint below another one both match; past ones are not gathered
        val sprint = PathTree.rows(tree, emptySet(), PathTree.search(tree, "PTD"), now)
        assertEquals(listOf("Progress Rail", "PTD", "PTD-S5", "PTD-S6", "PTD-S7"), sprint.names())
        assertEquals(listOf(false, true, true, true, true), sprint.nodes().map { it.match })
        assertEquals(
            listOf("Progress Rail", "Release 1", "Sprint 1"),
            PathTree.rows(tree, emptySet(), PathTree.search(tree, "sprint"), now).names(),
        )

        assertTrue(PathTree.rows(tree, emptySet(), PathTree.search(tree, "nothing")).isEmpty())
        assertNull(PathTree.search(tree, "  "))
    }

    @Test fun `past sprints gathered first in each folder, closed`() {
        val all = setOf("Progress Rail", "Progress Rail\\PTD", "Progress Rail\\Release 1")
        val rows = PathTree.rows(tree, all, null, now)
        // Release 1 is over at the root, PTD-S5 in PTD; Release 1's sprints all are, so not gathered there
        assertEquals(listOf("Progress Rail", "past 1", "PTD", "past 1", "PTD-S6", "PTD-S7", "Backlog"), rows.names())
        assertEquals(listOf(0, 1, 1, 2, 2, 2, 1), rows.map { it.level })
        assertTrue(rows.filterIsInstance<PastRow>().none { it.open })
        assertEquals(PathTree.pastKey("Progress Rail\\PTD"), rows[3].key)

        // Open: below their line, one level deeper
        val open = PathTree.rows(tree, all + PathTree.pastKey("Progress Rail") + PathTree.pastKey("Progress Rail\\PTD"), null, now)
        assertEquals(
            listOf("Progress Rail", "past 1", "Release 1", "Sprint 1", "PTD", "past 1", "PTD-S5", "PTD-S6", "PTD-S7", "Backlog"),
            open.names(),
        )
        assertEquals(listOf(0, 1, 2, 3, 1, 2, 3, 2, 2, 1), open.map { it.level })
        assertTrue(open.filterIsInstance<PastRow>().all { it.open })

        // Without dates (areas) nothing is gathered
        assertTrue(PathTree.rows(tree, all, null, null).all { it is PathRow })
    }

    @Test fun `a past selected sprint opens its line, a current one does not`() {
        val expanded = PathTree.initiallyExpanded(tree, "Progress Rail\\PTD\\PTD-S5", now)
        assertTrue(PathTree.pastKey("Progress Rail\\PTD") in expanded)
        assertFalse(PathTree.pastKey("Progress Rail") in expanded)
        assertEquals(
            listOf("Progress Rail", "past 1", "PTD", "past 1", "PTD-S5", "PTD-S6", "PTD-S7", "Backlog"),
            PathTree.rows(tree, expanded, null, now).names(),
        )
        assertTrue(PathTree.initiallyExpanded(tree, "Progress Rail\\PTD\\PTD-S6", now).none { it.startsWith(PathTree.pastKey("")) })
    }

    @Test fun `lineage down to a node, any case`() {
        assertEquals(
            listOf("Progress Rail", "PTD", "PTD-S6"),
            PathTree.lineage(tree, "PROGRESS RAIL\\ptd\\ptd-s6")?.map { it.name },
        )
        assertNull(PathTree.lineage(tree, "Other\\PTD"))
    }
}
