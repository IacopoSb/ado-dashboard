package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.FieldChange
import io.github.iacoposb.adodash.api.Iteration
import io.github.iacoposb.adodash.api.StateInfo
import io.github.iacoposb.adodash.api.TeamIteration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BacklogBatchTest {
    private fun states(vararg names: String) = names.map { StateInfo(it, "") }

    private val byType = mapOf(
        "user story" to states("New", "Active", "Resolved", "Closed", "Removed"),
        "bug" to states("New", "Active", "Resolved", "Closed"),
        "task" to states("New", "Active", "Closed", "Removed"),
    )

    @Test fun `one type offers all its states`() {
        assertEquals(byType["bug"], BacklogBatch.commonStates(listOf("Bug", "bug"), byType))
    }

    @Test fun `mixed types offer only the states all of them have, in the first one's order`() {
        assertEquals(states("New", "Active", "Resolved", "Closed"), BacklogBatch.commonStates(listOf("User Story", "Bug"), byType))
        assertEquals(states("New", "Active", "Closed"), BacklogBatch.commonStates(listOf("User Story", "Bug", "Task"), byType))
    }

    @Test fun `state names match ignoring case, and none in common is an empty list`() {
        val odd = byType + ("chore" to states("active", "Done"))
        assertEquals(states("Active"), BacklogBatch.commonStates(listOf("Task", "Chore"), odd))
        assertEquals(emptyList<StateInfo>(), BacklogBatch.commonStates(listOf("Bug", "Doc"), byType + ("doc" to states("Draft"))))
    }

    @Test fun `states not read yet for a type are null`() {
        assertNull(BacklogBatch.commonStates(listOf("Bug", "Epic"), byType))
    }

    @Test fun `the result counts the updated and lists the failures in order`() {
        val a = Fakes.workItem(1)
        val b = Fakes.workItem(2)
        val c = Fakes.workItem(3)
        val change = FieldChange.Priority(1)
        val result = BacklogBatch.result(
            change,
            listOf(
                a to BatchOutcome.Done,
                b to BatchOutcome.Failed(SaveProblem.OTHER, "HTTP 500"),
                c to BatchOutcome.Failed(SaveProblem.DENIED, "HTTP 403"),
            ),
        )
        assertEquals(1, result.updated)
        assertEquals(listOf(2, 3), result.failures.map { it.item.id })
        assertTrue(result.denied)
        assertFalse(BacklogBatch.result(change, listOf(b to BatchOutcome.Failed(SaveProblem.OTHER))).denied)
    }

    @Test fun `problems from the errors`() {
        assertEquals(SaveProblem.DENIED, BacklogBatch.problemOf(AdoException(403, "")))
        assertEquals(SaveProblem.DENIED, BacklogBatch.problemOf(AdoException(401, "")))
        assertEquals(SaveProblem.CONFLICT, BacklogBatch.problemOf(AdoException(412, "")))
        assertEquals(SaveProblem.OTHER, BacklogBatch.problemOf(AdoException(500, "")))
        assertEquals(SaveProblem.OTHER, BacklogBatch.problemOf(IllegalStateException()))
    }

    private fun day(d: String) = Instant.parse("${d}T00:00:00Z")
    private val current = TeamIteration("it-42", "Sprint 42", "Contoso\\Release 3\\Sprint 42", day("2026-10-12"), day("2026-10-23"))

    @Test fun `the next sprint is the team's next by start date`() {
        val team = listOf(
            TeamIteration("41", "Sprint 41", "Contoso\\Release 3\\Sprint 41", day("2026-09-28"), day("2026-10-09")),
            current,
            TeamIteration("44", "Sprint 44", "Contoso\\Release 4\\Sprint 44", day("2026-11-09"), day("2026-11-20")),
            TeamIteration("43", "Sprint 43", "Contoso\\Release 3\\Sprint 43", day("2026-10-26"), day("2026-11-06")),
            TeamIteration("x", "Future", "Contoso\\Future"),
        )
        assertEquals("Contoso\\Release 3\\Sprint 43", BacklogBatch.nextIteration(current, team, emptyList())?.path)
    }

    @Test fun `without the team's sprints, the project's next under the same parent first`() {
        val project = listOf(
            Iteration("Contoso", "Contoso"),
            Iteration("Mobile 7", "Contoso\\Mobile\\Mobile 7", day("2026-10-19"), day("2026-10-30")),
            Iteration("Sprint 42", "Contoso\\Release 3\\Sprint 42", day("2026-10-12"), day("2026-10-23")),
            Iteration("Sprint 43", "Contoso\\Release 3\\Sprint 43", day("2026-10-26"), day("2026-11-06")),
        )
        assertEquals("Contoso\\Release 3\\Sprint 43", BacklogBatch.nextIteration(current, emptyList(), project)?.path)
        // The last of its folder: the next one anywhere
        val other = listOf(project[1], project[2], Iteration("Sprint 44", "Contoso\\Release 4\\Sprint 44", day("2026-11-09")))
        assertEquals("Contoso\\Mobile\\Mobile 7", BacklogBatch.nextIteration(current, emptyList(), other)?.path)
        assertNull(BacklogBatch.nextIteration(current.copy(start = null), emptyList(), project))
        assertNull(BacklogBatch.nextIteration(current, emptyList(), project.take(1)))
    }
}
