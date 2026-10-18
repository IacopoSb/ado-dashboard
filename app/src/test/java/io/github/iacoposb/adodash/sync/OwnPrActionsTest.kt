package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.marco
import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.Fakes.now
import io.github.iacoposb.adodash.api.PrThread
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventKind
import io.github.iacoposb.adodash.data.PrCommentRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What I do on a pull request from the app never comes back to me as a notification. */
class OwnPrActionsTest {
    private val baseline = now.minusSeconds(86_400)

    @Test fun `a thread I replied to is stored as seen, so the next sync notifies none of it`() {
        // Anna's earlier comment becomes relevant once I take part in the thread
        val pr = Fakes.pr(311, marco, reviewers = listOf(Reviewer(me.id, me.name, Vote.NONE, false)))
        val thread = PrThread(5, "active", listOf(Fakes.comment(5, 1, anna, "Thoughts?"), Fakes.comment(5, 2, me, "@<${me.id}> note to self")))
        val stored = Events.ownPrThread(pr, thread, me, Fakes.Texts)
        assertTrue(stored.all { it.read && it.hidden })
        val fetched = Fetched(Snapshot(me, emptyList(), listOf(pr), emptyList(), now), prComments = mapOf(311 to thread.comments))
        val found = Events.from(fetched, baseline, Fakes.Texts).filter { it.kind != EventKind.PR_REVIEW }
        // Every comment the sync finds has its key already stored: the database ignores it
        assertEquals(found.map { it.key }.toSet(), stored.map { it.key }.toSet() intersect found.map { it.key }.toSet())
        assertEquals(listOf("pr:311:t5:c1"), found.map { it.key })
    }

    @Test fun `my own vote is never an event, others' votes on my pull requests are`() {
        val pr = Fakes.pr(320, me, reviewers = listOf(Reviewer(me.id, me.name, Vote.APPROVED, false), Reviewer(anna.id, anna.name, Vote.WAITING, true)))
        val events = Events.from(Fetched(Snapshot(me, emptyList(), emptyList(), listOf(pr), now)), baseline, Fakes.Texts)
        assertEquals(listOf("pr:320:vote:${anna.id}:${Vote.WAITING}"), events.filter { it.kind == EventKind.PR_VOTE }.map { it.key })
    }

    @Test fun `my vote shows on the dashboard rows at once`() {
        val pr = Fakes.pr(311, anna, reviewers = listOf(Reviewer(marco.id, marco.name, Vote.APPROVED, false)))
        val snapshot = Snapshot(me, emptyList(), listOf(pr), emptyList(), now).withMyVote(311, Vote.APPROVED_WITH_SUGGESTIONS)
        assertEquals(Vote.APPROVED_WITH_SUGGESTIONS, snapshot.reviewPrs.single().reviewers.single { it.id == me.id }.vote)
        assertEquals(pr, snapshot.pullRequest(311)?.copy(reviewers = pr.reviewers))
    }

    @Test fun `pull request events open their page, comments at their thread`() {
        fun ref(key: String) = Event(key, EventKind.PR_COMMENT, "", "", "", 0).pullRequest
        assertEquals(PrCommentRef(311, 5, 2), ref("pr:311:t5:c2"))
        assertEquals(PrCommentRef(311), ref("pr:311:review"))
        assertEquals(PrCommentRef(318), ref("pr:318:vote:${anna.id}:10"))
        assertNull(ref("wi:4812:c3"))
        assertNull(ref("build:981:failed"))
    }
}
