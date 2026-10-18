package io.github.iacoposb.adodash.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTextTest {
    private val line = 20f

    @Test fun `up to twelve lines are shown whole`() {
        assertFalse(LongText.collapses(0, line))
        assertFalse(LongText.collapses(12 * 20, line))
    }

    @Test fun `longer than twelve lines is collapsed to eight`() {
        assertTrue(LongText.collapses(12 * 20 + 1, line))
        assertEquals(160, LongText.collapsedHeight(line))
    }

    @Test fun `nothing collapses before the line height is known`() = assertFalse(LongText.collapses(1000, 0f))

    @Test fun `expanded for the session until toggled again`() {
        val key = "test:${System.nanoTime()}"
        assertFalse(key in ExpandedComments)
        ExpandedComments.toggle(key)
        assertTrue(key in ExpandedComments)
        ExpandedComments.toggle(key)
        assertFalse(key in ExpandedComments)
    }
}
