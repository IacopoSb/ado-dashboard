package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.Fakes.now
import io.github.iacoposb.adodash.api.BuildState
import io.github.iacoposb.adodash.api.DayRange
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.TeamIteration
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.DashboardView
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SnapshotStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val board = DashboardView.forBoard(BoardRef("Contoso", "Backend"))

    /** Everything a snapshot can hold: a board's sprint and pipeline, signals, warnings. */
    private val snapshot = run {
        val pr = Fakes.pr(318, me, reviewers = listOf(Reviewer(anna.id, anna.name, Vote.APPROVED, true, votedFor = listOf("x"))))
            .copy(mergeStatus = "conflicts", lastCommit = "abc")
        val item = Fakes.workItem(4812, "Bug", priority = null)
        val sprint = Sprint(
            BoardRef("Contoso", "Backend"),
            TeamIteration("it-42", "Sprint 42", "Contoso\\Sprint 42", now.minusSeconds(86_400), now.plusSeconds(86_400 * 9)),
            listOf(SprintGroup(SprintItem(item, me.id, 3.5, 1.0, "InProgress"), listOf(SprintItem(Fakes.workItem(4813))))),
            workingDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            daysOff = listOf(DayRange(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21))),
        )
        Snapshot(
            me = me,
            workItems = listOf(item),
            reviewPrs = listOf(Fakes.pr(311, anna)),
            myPrs = listOf(pr),
            syncedAt = now,
            warnings = listOf("Clienti: HTTP 500"),
            shown = Shown(
                board.queryKey, listOf(item), emptyList(), listOf(pr),
                pipeline = listOf(Fakes.build(981, BuildState.FAILED, prId = 318), Fakes.build(980, BuildState.RUNNING)),
                pipelineProject = "Contoso",
                sprint = sprint,
            ),
            signals = mapOf(318 to PrSignals(BuildSignal.FAILED, activeThreads = 2, conflicts = true)),
        )
    }

    private fun store(dir: File = folder.root, keep: Int = 8, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        SnapshotStore(dir, UnconfinedTestDispatcher(scheduler), keep)

    @Test fun `a snapshot comes back as it was saved`() = runTest {
        val store = store(scheduler = testScheduler)
        store.save("contoso", me.id, board.queryKey, snapshot)
        assertEquals(snapshot, store.load("contoso", me.id, board.queryKey))
        assertEquals("the organization's case does not matter", snapshot, store.load("Contoso", me.id, board.queryKey))
    }

    @Test fun `each view has its own, and nothing for another account or organization`() = runTest {
        val store = store(scheduler = testScheduler)
        store.save("contoso", me.id, board.queryKey, snapshot)
        val global = snapshot.copy(shown = snapshot.shown!!.copy(viewKey = DashboardView.global.queryKey, sprint = null))
        store.save("contoso", me.id, DashboardView.global.queryKey, global)
        assertEquals(global, store.load("contoso", me.id, DashboardView.global.queryKey))
        assertEquals(snapshot, store.load("contoso", me.id, board.queryKey))
        assertNull(store.load("fabrikam", me.id, board.queryKey))
        assertNull(store.load("contoso", anna.id, board.queryKey))
        assertNull(store.load("contoso", me.id, DashboardView.forBoard(BoardRef("Contoso", "Frontend")).queryKey))
    }

    @Test fun `saved under the view it was read for`() = runTest {
        val store = store(scheduler = testScheduler)
        // The view changed while the sync ran: the snapshot says which view it is
        store.save("contoso", me.id, DashboardView.global.queryKey, snapshot)
        assertNull(store.load("contoso", me.id, DashboardView.global.queryKey))
        assertEquals(snapshot, store.load("contoso", me.id, board.queryKey))
    }

    @Test fun `cleared on sign-out or another organization`() = runTest {
        val store = store(scheduler = testScheduler)
        store.save("contoso", me.id, board.queryKey, snapshot)
        store.clear()
        assertNull(store.load("contoso", me.id, board.queryKey))
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test fun `an unreadable or older file is ignored and dropped`() = runTest {
        val store = store(scheduler = testScheduler)
        store.save("contoso", me.id, board.queryKey, snapshot)
        val file = folder.root.listFiles()!!.single()
        file.writeText(file.readText().replace("\"version\":${SnapshotStore.VERSION}", "\"version\":0"))
        assertNull(store.load("contoso", me.id, board.queryKey))
        assertEquals(false, file.exists())
        store.save("contoso", me.id, board.queryKey, snapshot)
        folder.root.listFiles()!!.single().writeText("{\"version\":")
        assertNull(store.load("contoso", me.id, board.queryKey))
    }

    @Test fun `only the views synced last are kept`() = runTest {
        val store = store(keep = 2, scheduler = testScheduler)
        val views = (1..3).map { DashboardView.forBoard(BoardRef("Contoso", "Team $it")) }
        views.forEachIndexed { i, v ->
            store.save("contoso", me.id, v.queryKey, snapshot.copy(shown = snapshot.shown!!.copy(viewKey = v.queryKey)))
            // Distinct modification times, as syncs a minute apart have
            folder.root.listFiles()!!.filter { it.lastModified() > 0 }.forEach { f ->
                if (f.lastModified() >= System.currentTimeMillis() - 5_000) f.setLastModified(1_000_000L * (i + 1))
            }
        }
        assertNull(store.load("contoso", me.id, views[0].queryKey))
        assertEquals(views[2].queryKey, store.load("contoso", me.id, views[2].queryKey)?.shown?.viewKey)
    }
}
