package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.StateInfo
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import io.github.iacoposb.adodash.data.Filters
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

    private fun states(name: String, vararg projects: String) = TypeStates(name, null, listOf("New", "Active"), projects.toSet())

    private val catalog = FilterCatalog(
        emptyList(),
        listOf(
            states("Epic", "Contoso"), states("Feature", "Contoso"), states("User Story", "Contoso"),
            states("Bug", "Contoso", "Clienti"), states("Task", "Contoso"), states("Issue", "Clienti"),
        ),
    )

    private fun TypesToShow.names() = shown.map { it.name } to more.map { it.name }

    @Test fun `the types I use come first, in backlog order, the others on request`() {
        val types = catalog.typesToShow(Filters(), used = setOf("task", "Bug"))
        assertEquals(listOf("Bug", "Task") to listOf("Epic", "Feature", "User Story", "Issue"), types.names())
    }

    @Test fun `a type with hidden states stays, so it can be shown again`() {
        val filters = Filters(hiddenStates = mapOf("epic" to setOf("Resolved"), "Feature" to emptySet()))
        val types = catalog.typesToShow(filters, used = setOf("Bug"))
        assertEquals(listOf("Epic", "Bug") to listOf("Feature", "User Story", "Task", "Issue"), types.names())
    }

    @Test fun `all types while the used ones are unknown or none of them is listed`() {
        val all = catalog.types.map { it.name }
        assertEquals(all to emptyList<String>(), catalog.typesToShow(Filters(), used = null).names())
        assertEquals(all to emptyList<String>(), catalog.typesToShow(Filters(), used = setOf("Change Request")).names())
    }

    @Test fun `only the types of the projects selected`() {
        val filters = Filters(areas = setOf("Clienti\\Acme"))
        assertEquals(listOf("Bug") to listOf("Issue"), catalog.typesToShow(filters, used = setOf("Bug", "Task")).names())
    }
}
