package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiffRowsTest {
    /** 30 lines, line 10 changed and a line added after line 25. */
    private val old = (1..30).map { "line $it" }
    private val new = old.toMutableList().apply {
        this[9] = "line 10 changed"
        add(25, "added")
    }
    private val lines = LineDiff.diff(old, new)

    private fun describe(rows: List<DiffRow>) = rows.map {
        when (it) {
            is DiffRow.Header -> it.text
            is DiffRow.Gap -> "gap ${it.start}+${it.count}"
            is DiffRow.Line -> "${it.line.oldNo}/${it.line.newNo}"
            is DiffRow.Threads -> "threads ${it.threads.map { t -> t.id }}"
            is DiffRow.NewThread -> "composer"
        }
    }

    private fun thread(id: Int, line: Int?, left: Boolean = false) =
        PrThread(id, "active", listOf(PrComment(id, 1, Fakes.anna, "Comment", Fakes.now)), "/src/A.kt", line, left)

    @Test fun `each change with three lines around it, the rest hidden`() {
        assertEquals(
            listOf(
                "gap 0+6",
                "@@ -7,7 +7,7 @@", "7/7", "8/8", "9/9", "10/0", "0/10", "11/11", "12/12", "13/13",
                "gap 14+9",
                "@@ -23,6 +23,7 @@", "23/23", "24/24", "25/25", "0/26", "26/27", "27/28", "28/29",
                "gap 30+2",
            ),
            describe(DiffRows.rows(lines)),
        )
    }

    @Test fun `a hidden stretch tapped is shown, joining the hunks around it`() {
        val rows = describe(DiffRows.rows(lines, expanded = setOf(14)))
        assertEquals(listOf("gap 0+6", "@@ -7,22 +7,23 @@"), rows.take(2))
        assertEquals("gap 30+2", rows.last())
        assertEquals(1, rows.count { it.startsWith("@@") })
        // Everything shown: one header, no gaps
        val all = describe(DiffRows.rows(lines, expanded = setOf(0, 14, 30)))
        assertEquals("@@ -1,30 +1,31 @@", all.first())
        assertEquals(0, all.count { it.startsWith("gap") })
        assertEquals(lines.size + 1, all.size)
    }

    @Test fun `close changes share a hunk`() {
        val rows = describe(DiffRows.rows(LineDiff.diff(old, old.toMutableList().apply { this[9] = "x"; this[15] = "y" })))
        assertEquals(1, rows.count { it.startsWith("@@") })
    }

    @Test fun `only additions, the header's old side starts at the line before`() {
        val rows = DiffRows.rows(LineDiff.diff(listOf("a", "b"), listOf("a", "b", "c")))
        assertEquals("@@ -1,2 +1,3 @@", (rows.first() as DiffRow.Header).text)
        val added = DiffRows.rows(LineDiff.diff(emptyList(), listOf("a", "b")))
        assertEquals("@@ -0,0 +1,2 @@", (added.first() as DiffRow.Header).text)
    }

    @Test fun `no change, nothing but a gap`() =
        assertEquals(listOf("gap 0+30"), describe(DiffRows.rows(LineDiff.diff(old, old))))

    @Test fun `threads go under their line, new lines on the right and deleted ones on the left`() {
        val placed = DiffRows.place(
            lines,
            listOf(thread(1, 26), thread(2, 10, left = true), thread(3, 10), thread(4, 999), thread(5, null), thread(6, 2)),
        )
        // Right line 26 is the added one, left line 10 the deleted one, right line 10 the new one
        assertEquals(listOf(1), placed.byIndex[lines.indexOfFirst { it.newNo == 26 }]?.map { it.id })
        assertEquals(listOf(2), placed.byIndex[lines.indexOfFirst { it.oldNo == 10 }]?.map { it.id })
        assertEquals(listOf(3), placed.byIndex[lines.indexOfFirst { it.newNo == 10 }]?.map { it.id })
        assertEquals(listOf(4, 5), placed.elsewhere.map { it.id })
        // A thread on a hidden line shows it, with its context
        val rows = describe(DiffRows.rows(lines, threads = placed.byIndex))
        assertEquals(listOf("@@ -1,5 +1,5 @@", "1/1", "2/2", "threads [6]", "3/3", "4/4", "5/5", "gap 5+1"), rows.take(8))
        val i = rows.indexOf("0/26")
        assertEquals("threads [1]", rows[i + 1])
    }

    @Test fun `the line of a comment on either side`() {
        assertEquals(lines.indexOfFirst { it.kind == DiffKind.DELETE }, DiffRows.indexOf(lines, LineRef(10, left = true)))
        assertEquals(lines.indexOfFirst { it.kind == DiffKind.ADD }, DiffRows.indexOf(lines, LineRef(10, left = false)))
        // An unchanged line on the left by its old number
        assertEquals(lines.indexOfFirst { it.oldNo == 26 }, DiffRows.indexOf(lines, LineRef(26, left = true)))
        assertNull(DiffRows.indexOf(lines, LineRef(40, left = false)))
    }

    @Test fun `the composer goes under its line`() {
        val index = lines.indexOfFirst { it.newNo == 2 }
        val rows = describe(DiffRows.rows(lines, composerAt = index))
        assertEquals("composer", rows[rows.indexOf("2/2") + 1])
    }

    @Test fun `side by side, deletions next to the additions after them, unchanged lines on both sides`() {
        val diff = LineDiff.diff(listOf("a", "b", "c", "d"), listOf("a", "B", "C", "x", "d"))
        val side = DiffRows.sideBySide(DiffRows.rows(diff))
        val pairs = side.filterIsInstance<SideRow.Pair>().map { (it.left?.line?.text ?: "_") + "|" + (it.right?.line?.text ?: "_") }
        assertEquals(listOf("a|a", "b|B", "c|C", "_|x", "d|d"), pairs)
        assertEquals(SideRow.Header(DiffRows.rows(diff).first() as DiffRow.Header), side.first())
    }

    @Test fun `counts of added and deleted lines`() = assertEquals(2 to 1, DiffRows.counts(lines))
}
