package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.marco
import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.Fakes.now
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.data.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventsTest {
    private val baseline = now.minusSeconds(86_400)

    private fun fetched(
        workItems: List<io.github.iacoposb.adodash.api.WorkItem> = emptyList(),
        reviewPrs: List<io.github.iacoposb.adodash.api.PullRequest> = emptyList(),
        myPrs: List<io.github.iacoposb.adodash.api.PullRequest> = emptyList(),
        mentioned: List<Pair<io.github.iacoposb.adodash.api.WorkItem, List<Comment>>> = emptyList(),
        prComments: Map<Int, List<io.github.iacoposb.adodash.api.PrComment>> = emptyMap(),
    ) = Fetched(Snapshot(me, workItems, reviewPrs, myPrs, now), mentioned, prComments)

    @Test fun `work item mention needs my id and someone else as author`() {
        val wi = Fakes.workItem(7)
        val tag = """<a href="#" data-vss-mention="version:2.0,${me.id}">@Iacopo</a> puoi guardare?"""
        val events = Events.from(
            fetched(
                mentioned = listOf(
                    wi to listOf(
                        Comment(1, 7, anna, tag, now),
                        Comment(2, 7, anna, "nessuna menzione", now),
                        Comment(3, 7, me, tag, now),
                        Comment(4, 7, anna, tag, baseline.minusSeconds(1)),
                    ),
                ),
            ),
            baseline,
            Fakes.Texts,
        )
        assertEquals(listOf("wi:7:c1"), events.map { it.key })
        assertEquals(EventKind.MENTION, events.single().kind)
        assertEquals("Work item 7\n@Iacopo puoi guardare?", events.single().body)
        // For the widgets' latest mentions
        assertEquals(anna.name, events.single().author)
    }

    @Test fun `a comment posted from the app is stored as seen and never notified`() {
        val wi = Fakes.workItem(7)
        // Mentioning myself too: still mine
        val text = """<a href="#" data-vss-mention="version:2.0,${me.id}">@Iacopo</a> note to self"""
        val posted = Comment(9, 7, me, text, now)
        val own = Events.ownComment(wi, posted, me, Fakes.Texts)
        assertEquals("wi:7:c9", own.key)
        assertTrue(own.read && own.hidden)
        assertEquals(io.github.iacoposb.adodash.data.WorkItemCommentRef(7, 9), own.workItemComment)
        // The next sync finds nothing of its own to notify, and the key is the one a mention would take
        assertTrue(Events.from(fetched(mentioned = listOf(wi to listOf(posted))), baseline, Fakes.Texts).isEmpty())
        val other = Events.from(fetched(mentioned = listOf(wi to listOf(posted.copy(author = anna)))), baseline, Fakes.Texts)
        assertEquals(listOf(own.key), other.map { it.key })
    }

    @Test fun `only a work item mention opens its comment, PR mentions keep their page`() {
        val wi = io.github.iacoposb.adodash.data.Event("wi:4812:c55", EventKind.MENTION, "", "", "", 0)
        assertEquals(io.github.iacoposb.adodash.data.WorkItemCommentRef(4812, 55), wi.workItemComment)
        assertEquals(null, wi.copy(key = "pr:311:t2:c3").workItemComment)
        assertEquals(null, wi.copy(kind = EventKind.ASSIGNED, key = "wi:4812:assigned").workItemComment)
    }

    @Test fun `my PRs report every comment, reviewed ones only my threads and mentions`() {
        val mine = Fakes.pr(1, me)
        val theirs = Fakes.pr(2, anna)
        val events = Events.from(
            fetched(
                reviewPrs = listOf(theirs),
                myPrs = listOf(mine),
                prComments = mapOf(
                    1 to listOf(Fakes.comment(10, 1, anna, "rinomina questa variabile")),
                    2 to listOf(
                        Fakes.comment(20, 1, marco, "thread altrui"),
                        Fakes.comment(21, 1, me, "domanda mia"),
                        Fakes.comment(21, 2, anna, "risposta"),
                        Fakes.comment(22, 1, marco, "@<${me.id.uppercase()}> che ne pensi?"),
                    ),
                ),
            ),
            baseline,
            Fakes.Texts,
        )
        val byKey = events.associateBy { it.key }
        assertTrue("pr:1:t10:c1" in byKey)
        assertTrue("pr:2:t21:c2" in byKey)
        assertTrue("pr:2:t20:c1" !in byKey)
        assertTrue("pr:2:t21:c1" !in byKey)
        assertEquals(EventKind.MENTION, byKey.getValue("pr:2:t22:c1").kind)
        assertTrue(byKey.getValue("pr:2:t22:c1").body.endsWith("@Iacopo Sbalchiero che ne pensi?"))
        assertTrue(byKey.getValue("pr:2:t22:c1").author!!.isNotBlank())
        assertEquals(EventKind.PR_REVIEW, byKey.getValue("pr:2:review").kind)
    }

    @Test fun `votes on my PRs are keyed by value, so a change is news`() {
        val pr = Fakes.pr(3, me, reviewers = listOf(
            Reviewer(anna.id, anna.name, Vote.APPROVED, false),
            Reviewer(marco.id, marco.name, Vote.NONE, true),
        ))
        val keys = Events.from(fetched(myPrs = listOf(pr)), baseline, Fakes.Texts).map { it.key }
        assertEquals(listOf("pr:3:vote:${anna.id}:10"), keys)
    }

    @Test fun `titles come from the texts`() {
        val pr = Fakes.pr(3, me, reviewers = listOf(Reviewer(anna.id, anna.name, Vote.APPROVED, false)))
        val events = Events.from(fetched(workItems = listOf(Fakes.workItem(1)), myPrs = listOf(pr)), baseline, Fakes.Texts)
        assertEquals(listOf("assigned #1 Work item 1", "Anna Verdi voted 10"), events.map { it.title })
    }

    @Test fun `assigned work items become events`() {
        val keys = Events.from(fetched(workItems = listOf(Fakes.workItem(1), Fakes.workItem(2))), baseline, Fakes.Texts).map { it.key }
        assertEquals(listOf("wi:1:assigned", "wi:2:assigned"), keys)
    }

    @Test fun `plain text drops html and entities`() {
        assertEquals("a & b c", Events.plainText("<p>a &amp; b</p><div>c</div>", me))
        // Decoded once: the text "&lt;b&gt;" stays as it was written
        assertEquals("&lt;b&gt; più", Events.plainText("<p>&amp;lt;b&amp;gt;&nbsp;pi&ugrave;</p>", me))
    }

    @Test fun `events know their project and area, for the filters`() {
        val events = Events.from(
            fetched(workItems = listOf(Fakes.workItem(1, area = "Contoso\\Backend")), reviewPrs = listOf(Fakes.pr(2, anna))),
            baseline, Fakes.Texts,
        )
        assertEquals(listOf("Contoso" to "Contoso\\Backend", "Contoso" to null), events.map { it.project to it.area })
    }

    @Test fun `after a filter change what happened before the last sync is history`() {
        fun e(key: String, at: Long) = io.github.iacoposb.adodash.data.Event(key, EventKind.ASSIGNED, "", "", "", at)
        val found = listOf(e("old", 100), e("new", 300))
        assertEquals(found to emptyList<Any>(), Events.splitHistory(found, firstSync = true, lastSyncAt = 200, filtersChangedAt = 0))
        assertEquals(emptyList<Any>() to found, Events.splitHistory(found, firstSync = false, lastSyncAt = 200, filtersChangedAt = 150))
        assertEquals(
            listOf(found[0]) to listOf(found[1]),
            Events.splitHistory(found, firstSync = false, lastSyncAt = 200, filtersChangedAt = 250),
        )
    }
}
