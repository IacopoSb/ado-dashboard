package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.ChangeType
import io.github.iacoposb.adodash.api.PrFileChange
import org.junit.Assert.assertEquals
import org.junit.Test

class PrFileListTest {
    private fun file(path: String) = PrFileChange(path, ChangeType.EDIT)

    @Test fun `folders differing only by case stay apart, none of their files lost`() {
        val changes = listOf(file("/docs/b.md"), file("/Docs/a.md"), file("/src/Main.kt"), file("/docs/a.md"))
        val groups = PrFileList.groups(changes)
        assertEquals(listOf("/Docs", "/docs", "/src"), groups.map { it.first })
        assertEquals(4, PrFileList.ordered(changes).size)
        assertEquals(listOf("/docs/a.md", "/docs/b.md"), groups[1].second.map { it.path })
    }
}
