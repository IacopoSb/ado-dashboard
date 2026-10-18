package io.github.iacoposb.adodash.widget

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.marco
import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.Fakes.now
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventKind
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.sync.BuildSignal
import io.github.iacoposb.adodash.sync.PrSignals
import io.github.iacoposb.adodash.sync.Shown
import io.github.iacoposb.adodash.sync.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSummaryTest {
    private fun snapshot(shown: Shown? = null, signals: Map<Int, PrSignals> = emptyMap()) = Snapshot(
        me = me,
        workItems = listOf(Fakes.workItem(1), Fakes.workItem(2), Fakes.workItem(3)),
        reviewPrs = listOf(Fakes.pr(311, anna), Fakes.pr(305, marco)),
        myPrs = listOf(Fakes.pr(318, me), Fakes.pr(320, me), Fakes.pr(321, me)),
        syncedAt = now,
        shown = shown,
        signals = signals,
    )

    private fun mention(key: String, ago: Long, read: Boolean = false, author: String? = "Anna Verdi", area: String? = "Contoso\\Backend") = Event(
        key = key, kind = EventKind.MENTION, title = "Anna Verdi mentioned you in #4812", body = "Fix the login\nCan you look at it?",
        url = "https://dev.azure.com/org/x", at = now.minusSeconds(ago).toEpochMilli(), read = read,
        project = "Contoso", area = area, author = author,
    )

    @Test fun `the counters come from the snapshot, failed builds only of my pull requests`() {
        val signals = mapOf(
            318 to PrSignals(build = BuildSignal.FAILED),
            320 to PrSignals(build = BuildSignal.SUCCEEDED),
            321 to PrSignals(build = BuildSignal.FAILED),
            // To review, not mine
            311 to PrSignals(build = BuildSignal.FAILED),
        )
        val s = WidgetSummaries.synced(WidgetSummary(), "contoso", snapshot(signals = signals))
        assertEquals("contoso", s.organization)
        assertEquals(now.toEpochMilli(), s.syncedAt)
        assertEquals(2, s.toReview)
        assertEquals(3, s.workItems)
        assertEquals(2, s.failedBuilds)
    }

    @Test fun `a board view open in the app counts like the global view`() {
        // The board shows someone else's work item and none of my pull requests
        val board = DashboardView.forBoard(BoardRef("Contoso", "Backend"))
        val shown = Shown(board.queryKey, workItems = listOf(Fakes.workItem(9)), reviewPrs = emptyList(), myPrs = emptyList())
        val s = WidgetSummaries.synced(WidgetSummary(), "contoso", snapshot(shown, mapOf(318 to PrSignals(build = BuildSignal.FAILED))))
        assertEquals(3, s.workItems)
        assertEquals(1, s.failedBuilds)
        assertEquals(2, s.toReview)
    }

    @Test fun `a sync keeps the mentions it does not recompute`() {
        val previous = WidgetSummary(unreadMentions = 4, mentions = listOf(WidgetSummaries.mention(mention("wi:1:c1", 60))))
        val s = WidgetSummaries.synced(previous, "contoso", snapshot())
        assertEquals(4, s.unreadMentions)
        assertEquals(previous.mentions, s.mentions)
    }

    @Test fun `the latest three mentions, newest first, and the unread ones counted`() {
        val events = listOf(
            mention("wi:1:c1", ago = 600),
            mention("wi:2:c2", ago = 60, read = true),
            mention("pr:311:t1:c1", ago = 3000),
            mention("wi:3:c3", ago = 30),
            mention("wi:4:c4", ago = 9000, read = true),
            Event("pr:311:review", EventKind.PR_REVIEW, "review", "", "u", now.toEpochMilli()),
            mention("wi:5:c5", ago = 10).copy(hidden = true),
        )
        val s = WidgetSummaries.withMentions(WidgetSummary(), events, Filters())
        assertEquals(listOf("wi:3:c3", "wi:2:c2", "wi:1:c1"), s.mentions.map { it.key })
        assertEquals(3, s.unreadMentions)
    }

    @Test fun `mentions the global filters leave out are neither counted nor listed`() {
        val events = listOf(
            mention("wi:1:c1", ago = 60, area = "Contoso\\Backend"),
            mention("wi:2:c2", ago = 30, area = "Contoso\\Frontend"),
        )
        val s = WidgetSummaries.withMentions(WidgetSummary(), events, Filters(areas = setOf("Contoso\\Backend")))
        assertEquals(listOf("wi:1:c1"), s.mentions.map { it.key })
        assertEquals(1, s.unreadMentions)
    }

    @Test fun `a mention says who, where and the comment`() {
        val wi = WidgetSummaries.mention(mention("wi:4812:c7", ago = 60))
        assertEquals("Anna Verdi", wi.who)
        assertEquals("#4812 · Can you look at it?", wi.context)
        val pr = WidgetSummaries.mention(mention("pr:311:t2:c3", ago = 60))
        assertEquals("PR !311 · Can you look at it?", pr.context)
        // Its notification's routing: the comment of the work item, the thread of the PR
        assertEquals(4812, wi.toEvent().workItemComment?.workItemId)
        assertEquals(7, wi.toEvent().workItemComment?.commentId)
        assertEquals(2, pr.toEvent().pullRequest?.threadId)
    }

    @Test fun `a mention stored before authors were saved shows its notification's title`() {
        val m = WidgetSummaries.mention(mention("wi:4812:c7", ago = 60, author = null))
        assertEquals("Anna Verdi mentioned you in #4812", m.who)
    }

    @Test fun `stale after an hour, a refresh forgotten after a while`() {
        val t = now.toEpochMilli()
        val s = WidgetSummary(syncedAt = t, refreshingSince = t)
        assertFalse(s.stale(t + 59 * 60_000))
        assertTrue(s.stale(t + 61 * 60_000))
        assertFalse(WidgetSummary().stale(t))
        assertTrue(s.refreshing(t + 60_000))
        assertFalse(s.refreshing(t + 10 * 60_000))
        assertFalse(s.copy(refreshingSince = 0).refreshing(t))
    }

    @Test fun `two mentions fit in the 4x2 of the design, none when too low, three at most`() {
        assertEquals(2, WidgetSummaries.mentionRows(260f))
        assertEquals(0, WidgetSummaries.mentionRows(120f))
        assertEquals(3, WidgetSummaries.mentionRows(600f))
    }
}
