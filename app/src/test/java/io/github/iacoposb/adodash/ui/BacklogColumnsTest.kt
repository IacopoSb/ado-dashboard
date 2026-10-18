package io.github.iacoposb.adodash.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BacklogColumnsTest {
    @Test fun `the state column fits the longest state with the chip's padding`() {
        // "To be Integrated" is about 95 dp as chip text: 12 + 16 + 1 more
        assertEquals(124.dp, BacklogColumns.stateWidth(95.dp))
    }

    @Test fun `short states keep room for the header`() {
        assertEquals(BacklogColumns.STATE_MIN, BacklogColumns.stateWidth(20.dp))
        assertEquals(BacklogColumns.STATE_MIN, BacklogColumns.stateWidth(0.dp))
    }

    @Test fun `long states stop at the maximum and wrap in their chip`() {
        assertEquals(BacklogColumns.STATE_MAX, BacklogColumns.stateWidth(300.dp))
        assertEquals(170.dp, BacklogColumns.STATE_MAX)
    }
}
