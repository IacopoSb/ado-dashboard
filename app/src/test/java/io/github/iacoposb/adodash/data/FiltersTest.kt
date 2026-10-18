package io.github.iacoposb.adodash.data

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.Vote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {
    private val gateway = Fakes.workItem(1, area = "Contoso\\Backend\\Gateway")
    private val mobile = Fakes.workItem(2, area = "Contoso\\Mobile")
    private val root = Fakes.workItem(3, area = "Contoso")
    private val other = Fakes.workItem(4, area = "Interno\\HR")

    @Test fun `the defaults filter nothing`() {
        val f = Filters()
        listOf(gateway, mobile, root, other).forEach { assertTrue(f.accepts(it)) }
        assertTrue(f.acceptsReview(Fakes.pr(1, anna, draft = true), me))
        assertEquals("", f.wiqlConditions())
        assertEquals(f, Filters.decode(f.encode()))
        assertEquals(Filters(), Filters.decode(null))
        assertEquals(Filters(), Filters.decode("not json"))
    }

    @Test fun `areas include their sub-areas, projects all of their areas`() {
        val f = Filters(areas = setOf("Contoso\\Backend", "Interno"))
        assertTrue(f.accepts(gateway))
        assertFalse(f.accepts(mobile))
        assertFalse("items at the project root need the whole project", f.accepts(root))
        assertTrue(f.accepts(other))
        assertFalse("a sibling with a longer name is not below", f.coversArea("Contoso\\Backend2"))
        assertTrue(f.includesProject("contoso"))
        assertFalse(f.includesProject("Clienti"))
    }

    @Test fun `nothing selected shows nothing`() {
        val f = Filters(areas = emptySet())
        assertTrue(f.selectsNothing)
        assertFalse(f.accepts(gateway))
        assertFalse(f.acceptsMine(Fakes.pr(1, me)))
    }

    @Test fun `hidden states are per type`() {
        val f = Filters(hiddenStates = mapOf("Epic" to setOf("Resolved")))
        assertFalse(f.accepts(Fakes.workItem(1, type = "Epic", state = "Resolved")))
        assertFalse(f.accepts(Fakes.workItem(1, type = "epic", state = "resolved")))
        assertTrue(f.accepts(Fakes.workItem(2, type = "Epic", state = "New")))
        assertTrue(f.accepts(Fakes.workItem(3, type = "Feature", state = "Resolved")))
    }

    @Test fun `pull requests by project, drafts and my approval`() {
        val approved = Fakes.pr(1, anna, reviewers = listOf(Reviewer(me.id, me.name, Vote.APPROVED, false)))
        val suggestions = Fakes.pr(2, anna, reviewers = listOf(Reviewer(me.id, me.name, Vote.APPROVED_WITH_SUGGESTIONS, false)))
        val waiting = Fakes.pr(3, anna, reviewers = listOf(Reviewer(me.id, me.name, Vote.WAITING, false)))
        val draft = Fakes.pr(4, anna, draft = true)
        val elsewhere = Fakes.pr(5, anna, project = "Clienti")

        val defaults = Filters(areas = setOf("Contoso\\Backend"))
        assertTrue(defaults.acceptsReview(approved, me))
        assertTrue(defaults.acceptsReview(draft, me))
        assertFalse(defaults.acceptsReview(elsewhere, me))
        assertFalse(defaults.acceptsMine(elsewhere))

        val strict = Filters(draftsToReview = false, hideApprovedByMe = true)
        assertFalse(strict.acceptsReview(approved, me))
        assertFalse(strict.acceptsReview(suggestions, me))
        assertTrue(strict.acceptsReview(waiting, me))
        assertFalse(strict.acceptsReview(draft, me))
        assertTrue("my own drafts stay", strict.acceptsMine(Fakes.pr(6, me, draft = true)))
    }

    @Test fun `stored events follow the filters, old ones without a project stay`() {
        val f = Filters(areas = setOf("Contoso\\Backend"))
        fun event(project: String?, area: String?) = Event("k", EventKind.ASSIGNED, "", "", "", 0, project = project, area = area)
        assertTrue(f.acceptsEvent(event("Contoso", "Contoso\\Backend\\Gateway")))
        assertFalse(f.acceptsEvent(event("Contoso", "Contoso\\Mobile")))
        assertTrue("pull requests go by project", f.acceptsEvent(event("Contoso", null)))
        assertFalse(f.acceptsEvent(event("Clienti", null)))
        assertTrue(f.acceptsEvent(event(null, null)))
    }

    @Test fun `wiql conditions quote names`() {
        val f = Filters(
            areas = setOf("Contoso\\Backend", "Bob's"),
            hiddenStates = mapOf("Epic" to setOf("Resolved", "New"), "Bug" to emptySet()),
        )
        assertEquals(
            " AND ([System.AreaPath] UNDER 'Bob''s' OR [System.AreaPath] UNDER 'Contoso\\Backend')" +
                " AND ([System.WorkItemType] <> 'Epic' OR [System.State] NOT IN ('New','Resolved'))",
            f.wiqlConditions(),
        )
    }

    @Test fun `areas and projects that no longer exist are left out, case aside`() {
        val f = Filters(areas = setOf("contoso\\backend", "Contoso\\Gone", "Interno", "Old project"))
        assertEquals(setOf("contoso\\backend", "Interno"), f.withoutAreasMissingFrom(Fakes.projects).areas)
        val all = Filters(areas = setOf("Contoso\\Backend\\Gateway"))
        assertTrue(all.withoutAreasMissingFrom(Fakes.projects) === all)
        assertEquals(Filters(), Filters().withoutAreasMissingFrom(Fakes.projects))
    }
}
