package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The catalog as the settings show it: kept while it reloads, with the error if that fails. */
data class CatalogState(val catalog: FilterCatalog? = null, val loading: Boolean = false, val error: String? = null)

/** A work item type across projects, with the states a filter can hide. */
data class TypeStates(
    val name: String,
    /** Hex RGB from Azure Boards, for types the app has no colour of its own for. */
    val color: String?,
    val states: List<String>,
    val projects: Set<String>,
)

/** What the global filters choose from. */
data class FilterCatalog(val projects: List<AreaNode>, val types: List<TypeStates>) {
    companion object {
        /**
         * Only states that can reach the dashboard (not started or in progress): the closed
         * ones never do. Types are merged by name and ordered as on a backlog.
         */
        fun merge(types: Map<String, List<WorkItemTypeInfo>>): List<TypeStates> {
            val merged = linkedMapOf<String, TypeStates>()
            types.forEach { (project, list) ->
                list.filterNot { it.hidden }.forEach { type ->
                    val states = type.states.filter { it.category in SyncEngine.OPEN_CATEGORIES }.map { it.name }
                    if (states.isEmpty()) return@forEach
                    val key = type.name.lowercase()
                    val old = merged[key]
                    merged[key] = if (old == null) {
                        TypeStates(type.name, type.color, states, setOf(project))
                    } else {
                        old.copy(
                            color = old.color ?: type.color,
                            states = old.states + states.filter { s -> old.states.none { it.equals(s, ignoreCase = true) } },
                            projects = old.projects + project,
                        )
                    }
                }
            }
            return merged.values.sortedWith(compareBy<TypeStates>({ rank(it.name) }, { it.name.lowercase() }))
        }

        private fun rank(type: String) = when (type.lowercase()) {
            "epic" -> 0
            "feature" -> 1
            "user story", "product backlog item", "requirement" -> 2
            "bug" -> 3
            "task" -> 4
            "issue", "impediment" -> 5
            else -> 6
        }

        /**
         * Reads the catalog. A project whose areas cannot be read is shown without them, so it
         * can still be chosen whole; one whose types cannot be read just adds none.
         */
        suspend fun load(api: AdoApi): FilterCatalog = coroutineScope {
            val limit = Semaphore(4)
            val projects = api.projects()
            val read = projects.map { project ->
                async {
                    limit.withPermit {
                        val areas = runCatching { api.areas(project) }.getOrElse { AreaNode(project, project) }
                        val types = runCatching { api.workItemTypes(project) }.getOrDefault(emptyList())
                        Triple(project, areas, types)
                    }
                }
            }.awaitAll()
            FilterCatalog(
                projects = read.map { it.second },
                types = merge(read.associate { it.first to it.third }),
            )
        }
    }
}
