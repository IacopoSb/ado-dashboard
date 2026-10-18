package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.Fakes.now
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.sync.Shown
import io.github.iacoposb.adodash.sync.Snapshot
import io.github.iacoposb.adodash.sync.SyncFailure
import io.github.iacoposb.adodash.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStatusTest {
    private val view = DashboardView.global
    private val snapshot = Snapshot(me, listOf(Fakes.workItem(1)), emptyList(), emptyList(), syncedAt = now)
        .let { it.copy(shown = Shown(view.queryKey, it.workItems, emptyList(), emptyList())) }
    private val empty = Snapshot(me, emptyList(), emptyList(), emptyList(), syncedAt = now)
        .let { it.copy(shown = Shown(view.queryKey, emptyList(), emptyList(), emptyList())) }

    private fun of(state: SyncState, online: Boolean = true, v: DashboardView = view) = DashboardStatus.of(state, online, v)

    @Test fun `loading, nothing saved, the first sync running or about to start`() {
        for (state in listOf(SyncState(), SyncState(syncing = true), SyncState(syncing = true, error = "x", failure = SyncFailure.OTHER))) {
            val s = of(state)
            assertEquals(state.toString(), PanelsContent.LOADING, s.content)
            assertFalse(s.refreshing)
        }
        assertNull(of(SyncState(syncing = true)).banner)
    }

    @Test fun `cached and syncing, the saved data at once, no placeholders`() {
        val s = of(SyncState(snapshot, syncing = true))
        assertEquals(PanelsContent.DATA, s.content)
        assertTrue(s.refreshing)
        assertNull(s.banner)
        assertFalse(s.dimmed)
    }

    @Test fun `another view's data waits for its sync`() {
        val board = DashboardView.forBoard(BoardRef("Contoso", "Backend"))
        assertEquals(PanelsContent.LOADING, of(SyncState(snapshot, syncing = true), v = board).content)
    }

    @Test fun `empty, data with nothing in it is not loading`() {
        val s = of(SyncState(empty))
        assertEquals(PanelsContent.DATA, s.content)
        assertNull(s.banner)
    }

    @Test fun `offline, the saved data with the time it was read`() {
        val s = of(SyncState(snapshot), online = false)
        assertEquals(StatusBanner.Offline(now), s.banner)
        assertEquals(PanelsContent.DATA, s.content)
        assertFalse(s.dimmed)
        assertEquals(
            "a sync failed for the network",
            StatusBanner.Offline(now), of(SyncState(snapshot, error = "no host", failure = SyncFailure.NETWORK)).banner,
        )
    }

    @Test fun `offline without anything saved, no endless placeholders`() {
        val s = of(SyncState(), online = false)
        assertEquals(StatusBanner.Offline(null), s.banner)
        assertEquals(PanelsContent.NO_DATA, s.content)
    }

    @Test fun `offline wins over an older failure`() {
        assertEquals(StatusBanner.Offline(now), of(SyncState(snapshot, error = "x", failure = SyncFailure.AUTH), online = false).banner)
    }

    @Test fun `session expired, the data dimmed, sign in`() {
        val s = of(SyncState(snapshot, error = "expired", failure = SyncFailure.AUTH))
        assertEquals(StatusBanner.SessionExpired, s.banner)
        assertEquals(PanelsContent.DATA, s.content)
        assertTrue(s.dimmed)
        val none = of(SyncState(error = "expired", failure = SyncFailure.AUTH))
        assertEquals(PanelsContent.NO_DATA, none.content)
        assertFalse("nothing to dim", none.dimmed)
    }

    @Test fun `other errors, the reason, the data as it was`() {
        val s = of(SyncState(snapshot, error = "HTTP 503", failure = SyncFailure.OTHER))
        assertEquals(StatusBanner.Error("HTTP 503"), s.banner)
        assertEquals(PanelsContent.DATA, s.content)
        assertFalse(s.dimmed)
        assertEquals(PanelsContent.NO_DATA, of(SyncState(error = "HTTP 503", failure = SyncFailure.OTHER)).content)
    }
}
