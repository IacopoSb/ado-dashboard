package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import io.github.iacoposb.adodash.data.Filters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/**
 * The catalog as the settings show it: the saved one while a fresh one is read, with the error
 * if that fails.
 */
data class CatalogState(
    val catalog: FilterCatalog? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** Types among my work items at the last sync; null while unknown. */
    val usedTypes: Set<String>? = null,
) {
    /** Reading a fresh catalog over the one shown. */
    val refreshing get() = loading && catalog != null
}

/** What "States to hide" lists: [shown] at first, [more] behind "Show all types". */
data class TypesToShow(val shown: List<TypeStates>, val more: List<TypeStates>)

/** A work item type across projects, with the states a filter can hide. */
@Serializable
data class TypeStates(
    val name: String,
    /** Hex RGB from Azure Boards, for types the app has no colour of its own for. */
    val color: String?,
    val states: List<String>,
    val projects: Set<String>,
)

/** A team, with its current sprint when it has one. */
@Serializable
data class TeamInfo(val name: String, val sprint: String? = null)

/**
 * What the global filters choose from, and the views too: the teams of each project (their
 * boards) and its repositories. [version] tells a catalog saved by an older release, which
 * has neither: it is read again.
 */
@Serializable
data class FilterCatalog(
    val projects: List<AreaNode>,
    val types: List<TypeStates>,
    val teams: Map<String, List<TeamInfo>> = emptyMap(),
    val repositories: Map<String, List<String>> = emptyMap(),
    val version: Int = 1,
) {

    /**
     * The types of the projects [filters] include (all of them while none is), in backlog
     * order. Shown first: those among my work items, [used], and those with states hidden,
     * so they can be shown again; the rest only on request. All are shown when [used] is
     * unknown or nothing would be.
     */
    fun typesToShow(filters: Filters, used: Set<String>?): TypesToShow {
        val types = types.filter { t -> t.projects.any(filters::includesProject) }.ifEmpty { types }
        if (used == null) return TypesToShow(types, emptyList())
        val (shown, more) = types.partition { t ->
            used.any { it.equals(t.name, ignoreCase = true) } ||
                filters.hiddenStates.any { (name, states) -> states.isNotEmpty() && name.equals(t.name, ignoreCase = true) }
        }
        return if (shown.isEmpty()) TypesToShow(more, emptyList()) else TypesToShow(shown, more)
    }

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

        const val PARALLEL_PROJECTS = 4
        /** 2: teams with their sprint and repositories, for the views. */
        const val VERSION = 2

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
            // Four projects at a time, each with its areas, types, teams and repositories read
            // together; the teams' sprints four at a time too
            val limit = Semaphore(PARALLEL_PROJECTS)
            val sprints = Semaphore(PARALLEL_PROJECTS)
            val projects = api.projects()
            val read = projects.map { project ->
                async {
                    limit.withPermit {
                        coroutineScope {
                            val areas = async { runCatching { api.areas(project) }.getOrElse { AreaNode(project, project) } }
                            val types = async { runCatching { api.workItemTypes(project) }.getOrDefault(emptyList()) }
                            val repos = async { runCatching { api.repositories(project) }.getOrDefault(emptyList()) }
                            val teams = runCatching { api.teams(project) }.getOrDefault(emptyList()).map { team ->
                                async {
                                    val sprint = sprints.withPermit { runCatching { api.currentSprint(project, team) }.getOrNull() }
                                    TeamInfo(team, sprint)
                                }
                            }.awaitAll()
                            ProjectRead(project, areas.await(), types.await(), teams, repos.await())
                        }
                    }
                }
            }.awaitAll()
            FilterCatalog(
                projects = read.map { it.areas },
                types = merge(read.associate { it.name to it.types }),
                teams = read.associate { it.name to it.teams },
                repositories = read.associate { it.name to it.repositories },
                version = VERSION,
            )
        }

        private class ProjectRead(
            val name: String,
            val areas: AreaNode,
            val types: List<WorkItemTypeInfo>,
            val teams: List<TeamInfo>,
            val repositories: List<String>,
        )
    }
}
