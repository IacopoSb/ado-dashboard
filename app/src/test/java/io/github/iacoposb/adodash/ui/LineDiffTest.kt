package io.github.iacoposb.adodash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LineDiffTest {
    private fun diff(old: String, new: String) = LineDiff.diff(old.split(' ').filter { it.isNotEmpty() }, new.split(' ').filter { it.isNotEmpty() })

    /** "a +b -c" for a diff: unchanged, added, deleted. */
    private fun render(lines: List<DiffLine>) = lines.joinToString(" ") {
        when (it.kind) {
            DiffKind.CONTEXT -> it.text
            DiffKind.ADD -> "+${it.text}"
            DiffKind.DELETE -> "-${it.text}"
        }
    }

    @Test fun `identical files have only context`() {
        val lines = diff("a b c", "a b c")
        assertEquals("a b c", render(lines))
        assertEquals(listOf(1 to 1, 2 to 2, 3 to 3), lines.map { it.oldNo to it.newNo })
    }

    @Test fun `lines added in the middle and at the end`() {
        val lines = diff("a b c", "a x b c y")
        assertEquals("a +x b c +y", render(lines))
        assertEquals(listOf(1 to 1, 0 to 2, 2 to 3, 3 to 4, 0 to 5), lines.map { it.oldNo to it.newNo })
    }

    @Test fun `lines deleted`() {
        val lines = diff("a b c d", "a d")
        assertEquals("a -b -c d", render(lines))
        assertEquals(listOf(1 to 1, 2 to 0, 3 to 0, 4 to 2), lines.map { it.oldNo to it.newNo })
    }

    @Test fun `a changed line is deleted then added`() = assertEquals("a -b +B c", render(diff("a b c", "a B c")))

    @Test fun `a new file and a deleted one`() {
        assertEquals("+a +b", render(diff("", "a b")))
        assertEquals("-a -b", render(diff("a b", "")))
        assertEquals(emptyList<DiffLine>(), diff("", ""))
    }

    @Test fun `a moved line is deleted where it was and added where it is`() {
        val lines = diff("a b c d e", "a c d e b")
        assertEquals("a -b c d e +b", render(lines))
    }

    @Test fun `the edit script is a shortest one`() {
        val random = Random(42)
        repeat(300) {
            val a = List(random.nextInt(0, 30)) { "${random.nextInt(0, 5)}" }
            val b = List(random.nextInt(0, 30)) { "${random.nextInt(0, 5)}" }
            val lines = LineDiff.diff(a, b)
            // Applying it gives both files back
            assertEquals(a, lines.filter { it.kind != DiffKind.ADD }.map { it.text })
            assertEquals(b, lines.filter { it.kind != DiffKind.DELETE }.map { it.text })
            assertEquals((1..a.size).toList(), lines.filter { it.kind != DiffKind.ADD }.map { it.oldNo })
            assertEquals((1..b.size).toList(), lines.filter { it.kind != DiffKind.DELETE }.map { it.newNo })
            // As many unchanged lines as the longest common subsequence
            assertEquals(lcs(a, b), lines.count { it.kind == DiffKind.CONTEXT })
        }
    }

    @Test fun `over budget a part is all deleted and all added, still correct`() {
        val a = (1..50).map { "a$it" } + "same"
        val b = (1..50).map { "b$it" } + "same"
        val lines = LineDiff.diff(a, b, budget = 10)
        assertEquals(a, lines.filter { it.kind != DiffKind.ADD }.map { it.text })
        assertEquals(b, lines.filter { it.kind != DiffKind.DELETE }.map { it.text })
        assertEquals(DiffKind.CONTEXT, lines.last().kind)
    }

    @Test fun `twenty thousand lines diff quickly`() {
        val a = (1..20_000).map { "line $it" }
        val b = a.toMutableList().apply {
            for (i in 0 until size step 97) this[i] = "changed $i"
            add(5000, "inserted")
            removeAt(15_000)
        }
        val start = System.nanoTime()
        val lines = LineDiff.diff(a, b)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
        assertEquals(b, lines.filter { it.kind != DiffKind.DELETE }.map { it.text })
    }

    @Test fun `a text's lines, whatever the line endings`() {
        assertEquals(listOf("a", "b", "c"), LineDiff.lines("a\r\nb\nc\n"))
        assertEquals(listOf("a", ""), LineDiff.lines("a\n\n"))
        assertEquals(emptyList<String>(), LineDiff.lines(""))
        assertEquals(listOf("a"), LineDiff.lines("a"))
    }

    private fun lcs(a: List<String>, b: List<String>): Int {
        val t = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            t[i][j] = if (a[i] == b[j]) t[i + 1][j + 1] + 1 else maxOf(t[i + 1][j], t[i][j + 1])
        }
        return t[0][0]
    }
}
