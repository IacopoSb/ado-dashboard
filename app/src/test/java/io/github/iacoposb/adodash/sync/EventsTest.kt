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
