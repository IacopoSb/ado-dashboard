package io.github.iacoposb.adodash.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class WorkItemPatchTest {
    private fun patch(change: FieldChange): JsonArray = WorkItemPatch.build(12, change)

    private fun expected(field: String, value: String) = Json.parseToJsonElement(
        """[{"op":"test","path":"/rev","value":12},{"op":"add","path":"/fields/$field","value":$value}]""",
    )

    @Test fun `each change tests the revision first`() {
        assertEquals(expected("System.State", "\"Resolved\""), patch(FieldChange.State("Resolved")))
        assertEquals(expected("Microsoft.VSTS.Common.Priority", "2"), patch(FieldChange.Priority(2)))
        assertEquals(expected("System.IterationPath", "\"Contoso\\\\Sprint 42\""), patch(FieldChange.IterationPath("Contoso\\Sprint 42")))
        assertEquals(expected("System.AreaPath", "\"Contoso\\\\Backend\""), patch(FieldChange.AreaPath("Contoso\\Backend")))
        assertEquals(expected("Microsoft.VSTS.Scheduling.RemainingWork", "3.5"), patch(FieldChange.RemainingWork(3.5)))
    }

    @Test fun `assigning sends the unique name, unassigning an empty value`() {
        assertEquals(
            expected("System.AssignedTo", "\"anna@contoso.com\""),
            patch(FieldChange.AssignedTo(Person("u2", "Anna Verdi", "anna@contoso.com"))),
        )
        assertEquals(expected("System.AssignedTo", "\"\""), patch(FieldChange.AssignedTo(null)))
        // Without a unique name, the name
        assertEquals(expected("System.AssignedTo", "\"Anna Verdi\""), patch(FieldChange.AssignedTo(Person("u2", "Anna Verdi", ""))))
    }

    @Test fun `clearing the remaining work`() {
        assertEquals(expected("Microsoft.VSTS.Scheduling.RemainingWork", "\"\""), patch(FieldChange.RemainingWork(null)))
    }

    @Test fun `a change applied to the details`() {
        val item = WorkItem(1, "Contoso", "Task", "t", "Active", 2, "Anna Verdi", Instant.EPOCH, "u", "Contoso")
        val detail = WorkItemDetail(item, rev = 3)
        assertEquals("Closed", detail.with(FieldChange.State("Closed")).item.state)
        assertEquals(null, detail.with(FieldChange.AssignedTo(null)).item.assignedTo)
        assertEquals("Contoso\\Web", detail.with(FieldChange.AreaPath("Contoso\\Web")).item.areaPath)
        assertEquals(4, detail.with(FieldChange.Priority(4)).item.priority)
    }

    @Test fun `iterations current first, then next, past and undated`() {
        val now = Instant.parse("2026-10-16T10:00:00Z")
        fun it(name: String, start: String?, finish: String?) =
            Iteration(name, "P\\$name", start?.let { Instant.parse("${it}T00:00:00Z") }, finish?.let { Instant.parse("${it}T00:00:00Z") })
        val list = listOf(
            Iteration("P", "P"),
            it("Sprint 40", "2026-09-14", "2026-09-25"),
            it("Sprint 41", "2026-09-28", "2026-10-09"),
            it("Sprint 42", "2026-10-12", "2026-10-16"),
            it("Sprint 44", "2026-11-09", "2026-11-20"),
            it("Sprint 43", "2026-10-26", "2026-11-06"),
            it("Backlog", null, null),
        )
        assertEquals(
            listOf("Sprint 42", "Sprint 43", "Sprint 44", "Sprint 41", "Sprint 40", "P", "Backlog"),
            Iterations.ordered(list, now).map { it.name },
        )
    }
}
