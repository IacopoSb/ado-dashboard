package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.Fakes.now
import io.github.iacoposb.adodash.sync.Snapshot
import io.github.iacoposb.adodash.sync.SyncState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwaitingFirstSyncTest {
    private val snapshot = Snapshot(me, emptyList(), emptyList(), emptyList(), syncedAt = now)

    @Test fun `before the first sync starts`() = assertTrue(SyncState().awaitingFirstSync)

    @Test fun `while the first sync runs`() = assertTrue(SyncState(syncing = true).awaitingFirstSync)

    @Test fun `retrying after a failure`() = assertTrue(SyncState(syncing = true, error = "x").awaitingFirstSync)

    @Test fun `after a failure, empty texts`() = assertFalse(SyncState(error = "x").awaitingFirstSync)

    @Test fun `with data, even if empty or refreshing`() {
        assertFalse(SyncState(snapshot).awaitingFirstSync)
        assertFalse(SyncState(snapshot, syncing = true).awaitingFirstSync)
    }
}
