package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.StateInfo
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterCatalogTest {
    private fun type(name: String, vararg states: Pair<String, String>, hidden: Boolean = false) =
        WorkItemTypeInfo(name, null, states.map { StateInfo(it.first, it.second) }, hidden)

    @Test fun `types merge by name with only their open states, in backlog order`() {
        val merged = FilterCatalog.merge(
            mapOf(
                "Contoso" to listOf(
                    type("Bug", "Active" to "InProgress", "Closed" to "Completed"),
                    type("Epic", "New" to "Proposed", "Resolved" to "InProgress", "Closed" to "Completed"),
                    type("Code Review Request", "Requested" to "Proposed", hidden = true),
                    type("Test Plan", "Inactive" to "Completed"),
                ),
                "Clienti" to listOf(
                    type("bug", "Active" to "InProgress", "Triage" to "Proposed"),
                ),
            ),
        )
        assertEquals(listOf("Epic", "Bug"), merged.map { it.name })
        assertEquals(listOf("New", "Resolved"), merged[0].states)
        assertEquals(listOf("Active", "Triage"), merged[1].states)
        assertEquals(setOf("Contoso", "Clienti"), merged[1].projects)
    }
}
