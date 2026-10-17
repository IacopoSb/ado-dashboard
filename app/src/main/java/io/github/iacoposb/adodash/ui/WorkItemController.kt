package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.FieldChange
import io.github.iacoposb.adodash.api.Iteration
import io.github.iacoposb.adodash.api.Iterations
import io.github.iacoposb.adodash.api.LinkKind
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.StateInfo
import io.github.iacoposb.adodash.api.WiFields
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemDetail
import io.github.iacoposb.adodash.api.isConflict
import io.github.iacoposb.adodash.api.isDenied
import io.github.iacoposb.adodash.sync.FilterCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** A link of the work item open, with what is known of its target. */
data class LinkRow(
    val kind: LinkKind,
    val id: Int,
    /** The work item's type, title and state; null for pull requests or when unreadable. */
    val type: String? = null,
    val title: String? = null,
    val state: String? = null,
    /** A pull request's status ("active", "completed", "abandoned"). */
    val prStatus: String? = null,
    val draft: Boolean = false,
    val url: String = "",
)

enum class SaveProblem { CONFLICT, DENIED, OTHER }

/** Why the change of [field] was not saved: it shows its saved value again. */
data class FieldError(val field: String, val problem: SaveProblem, val message: String = "")

/** "Assigned to"'s search: [results] are for [query]. */
data class PeopleSearch(val query: String = "", val loading: Boolean = false, val results: List<Person> = emptyList())

/**
 * A work item in the details. [saved] is what Azure DevOps has (null while it is read, [known]
 * from the dashboard row standing in); [pending] the changes being saved, shown already.
 */
data class WorkItemPage(
    val id: Int,
    val known: WorkItem? = null,
    val saved: WorkItemDetail? = null,
    val pending: Map<String, FieldChange> = emptyMap(),
    val error: String? = null,
    val links: List<LinkRow>? = null,
    val fieldError: FieldError? = null,
    /** All the states of the type, in the process' order; null while read. */
    val states: List<StateInfo>? = null,
    val iterations: List<Iteration>? = null,
    val areas: AreaNode? = null,
    /** The type has Remaining Work (tasks and the like). */
    val hasRemainingWork: Boolean = false,
) {
    /** As shown: saved, with the pending changes over it. */
    val detail: WorkItemDetail? get() = saved?.let { pending.values.fold(it) { d, c -> d.with(c) } }
    val item: WorkItem? get() = detail?.item ?: known

    fun saving(field: String) = field in pending
}

/**
 * The work item details open over the dashboard: [pages] is the way there, the last one shown
 * (a link opens its work item over the one before, back returns to it).
 */
data class WorkItemModal(
    val pages: List<WorkItemPage>,
    /** Changes were refused for lack of permission: fields read-only until the app restarts. */
    val readOnly: Boolean = false,
    val people: PeopleSearch = PeopleSearch(),
    /** Me, offered first in "Assigned to". */
    val me: Person? = null,
) {
    val page: WorkItemPage get() = pages.last()
    val canGoBack get() = pages.size > 1
}

/**
 * Reads the work items the details show and saves their changes, one at a time and each against
 * the revision last read, so that nobody else's change is overwritten.
 */
class WorkItemController(
    private val scope: CoroutineScope,
    private val api: suspend () -> AdoApi,
    private val catalog: () -> FilterCatalog?,
    private val me: () -> Person?,
    /** After a save: the dashboard row and, for my own assignment, no notification. */
    private val onSaved: suspend (WorkItem, assignedToMe: Boolean) -> Unit,
    private val errorText: (Throwable) -> String,
    private val now: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow<WorkItemModal?>(null)
    val state: StateFlow<WorkItemModal?> = _state.asStateFlow()

    private val _readOnly = MutableStateFlow(false)
    /** Refused once, refused for the session. */
    val readOnly: StateFlow<Boolean> = _readOnly.asStateFlow()
    private val saves = Mutex()
    private val jobs = mutableListOf<Job>()
    private var search: Job? = null

    private val statesCache = mutableMapOf<String, List<StateInfo>>()
    private val iterationsCache = mutableMapOf<String, List<Iteration>>()
    private val fieldsCache = mutableMapOf<String, Set<String>>()
    private val membersCache = mutableMapOf<String, List<Person>>()

    fun open(id: Int, known: WorkItem? = null) {
        cancelAll()
        _state.value = WorkItemModal(listOf(WorkItemPage(id, known)), readOnly = _readOnly.value, me = me())
        load(id)
    }

    /** A linked work item, over the one open. */
    fun openLinked(id: Int) {
        val modal = _state.value ?: return open(id)
        if (modal.page.id == id) return
        _state.value = modal.copy(pages = modal.pages + WorkItemPage(id), people = PeopleSearch())
        load(id)
    }

    /** Back to the previous work item, or closed. */
    fun back() {
        val modal = _state.value ?: return
        if (modal.canGoBack) _state.value = modal.copy(pages = modal.pages.dropLast(1), people = PeopleSearch()) else close()
    }

    fun close() {
        cancelAll()
        _state.value = null
    }

    /** Reads the work item shown again, after a conflict. */
    fun reload() {
        val page = _state.value?.page ?: return
        updatePage(page.id) { WorkItemPage(page.id, page.saved?.item ?: page.known) }
        load(page.id)
    }

    private fun cancelAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        search?.cancel()
    }

    private fun updatePage(id: Int, change: (WorkItemPage) -> WorkItemPage) {
        _state.update { modal -> modal?.copy(pages = modal.pages.map { if (it.id == id) change(it) else it }) }
    }

    private fun load(id: Int) {
        jobs += scope.launch {
            val api = api()
            val detail = try {
                api.workItemDetail(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updatePage(id) { it.copy(error = errorText(e)) }
                return@launch
            }
            updatePage(id) { it.copy(saved = detail, error = null) }
            val item = detail.item
            launch { links(api, detail).let { l -> updatePage(id) { it.copy(links = l) } } }
            launch { states(api, item).let { l -> updatePage(id) { it.copy(states = l) } } }
            launch { iterations(api, item.project).let { l -> updatePage(id) { it.copy(iterations = l) } } }
            launch { areas(api, item.project).let { a -> updatePage(id) { it.copy(areas = a) } } }
            launch {
                val fields = fields(api, item)
                updatePage(id) { it.copy(hasRemainingWork = WiFields.REMAINING_WORK in fields || detail.remainingWork != null) }
            }
        }
    }

    private suspend fun links(api: AdoApi, detail: WorkItemDetail): List<LinkRow> = kotlinx.coroutines.coroutineScope {
        val order = listOf(LinkKind.PARENT, LinkKind.CHILD, LinkKind.RELATED, LinkKind.PULL_REQUEST)
        val links = detail.links.sortedBy { order.indexOf(it.kind) }
        val ids = links.filter { it.kind != LinkKind.PULL_REQUEST }.map { it.targetId }.distinct()
        val items = async { runCatchingNotCancelled { api.workItems(ids) }.orEmpty().associateBy { it.id } }
        val prs = links.filter { it.kind == LinkKind.PULL_REQUEST }.map { link ->
            async { link.targetId to runCatchingNotCancelled { api.pullRequestById(link.targetId) } }
        }.awaitAll().toMap()
        val found = items.await()
        links.map { link ->
            if (link.kind == LinkKind.PULL_REQUEST) {
                val pr = prs[link.targetId]
                LinkRow(link.kind, link.targetId, title = pr?.title, prStatus = pr?.status, draft = pr?.draft == true, url = pr?.url ?: link.url)
            } else {
                val wi = found[link.targetId]
                LinkRow(link.kind, link.targetId, wi?.type, wi?.title, wi?.state, url = wi?.url.orEmpty())
            }
        }
    }

    private suspend fun states(api: AdoApi, item: WorkItem): List<StateInfo> {
        val key = "${item.project}/${item.type}".lowercase()
        statesCache[key]?.let { return it }
        val read = runCatchingNotCancelled { api.stateCategories(item.project, item.type) }
            ?.map { (name, category) -> StateInfo(name, category) }
        if (read != null) statesCache[key] = read
        // The saved catalog has only the open states: better than nothing
        return read ?: catalog()?.types?.firstOrNull { it.name.equals(item.type, ignoreCase = true) }
            ?.states?.map { StateInfo(it, "") }.orEmpty().let { open ->
                if (open.any { it.name == item.state }) open else listOf(StateInfo(item.state, "")) + open
            }
    }

    private suspend fun iterations(api: AdoApi, project: String): List<Iteration> {
        val key = project.lowercase()
        iterationsCache[key]?.let { return it }
        val read = runCatchingNotCancelled { Iterations.ordered(api.iterations(project), now()) } ?: return emptyList()
        iterationsCache[key] = read
        return read
    }

    private suspend fun areas(api: AdoApi, project: String): AreaNode? =
        catalog()?.projects?.firstOrNull { it.name.equals(project, ignoreCase = true) }
            ?: runCatchingNotCancelled { api.areas(project) }

    private suspend fun fields(api: AdoApi, item: WorkItem): Set<String> {
        val key = "${item.project}/${item.type}".lowercase()
        fieldsCache[key]?.let { return it }
        val read = runCatchingNotCancelled { api.typeFields(item.project, item.type) } ?: return emptySet()
        fieldsCache[key] = read
        return read
    }

    /**
     * Shows [change] at once and saves it. Saves go one at a time, each against the revision the
     * previous one returned; a failure shows the saved value again with the reason.
     */
    fun change(change: FieldChange) {
        val modal = _state.value ?: return
        if (modal.readOnly) return
        val page = modal.page
        val shown = page.detail ?: return
        if (shown.with(change) == shown) return
        val id = page.id
        updatePage(id) { it.copy(pending = it.pending + (change.field to change), fieldError = null) }
        jobs += scope.launch {
            saves.withLock {
                val api = api()
                val saved = _state.value?.pages?.firstOrNull { it.id == id }?.saved ?: return@withLock
                try {
                    val sent = resolved(api, change)
                    val result = api.updateWorkItem(id, saved.rev, sent)
                    updatePage(id) {
                        it.copy(
                            saved = result,
                            pending = if (it.pending[change.field] == change) it.pending - change.field else it.pending,
                        )
                    }
                    val mine = me()
                    val assignedToMe = sent is FieldChange.AssignedTo && sent.person != null && mine != null &&
                        (sent.person.id.equals(mine.id, ignoreCase = true) || sent.person.name == mine.name)
                    onSaved(result.item, assignedToMe)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val problem = when {
                        e is AdoException && e.isConflict -> SaveProblem.CONFLICT
                        e is AdoException && e.isDenied -> SaveProblem.DENIED
                        else -> SaveProblem.OTHER
                    }
                    if (problem == SaveProblem.DENIED) {
                        _readOnly.value = true
                        _state.update { it?.copy(readOnly = true) }
                    }
                    updatePage(id) {
                        it.copy(
                            pending = it.pending - change.field,
                            fieldError = FieldError(change.field, problem, errorText(e)),
                        )
                    }
                }
            }
        }
    }

    /** Me without a unique name (the sign-in gives only the name): looked up first. */
    private suspend fun resolved(api: AdoApi, change: FieldChange): FieldChange {
        val person = (change as? FieldChange.AssignedTo)?.person ?: return change
        if (person.uniqueName.isNotBlank()) return change
        val found = runCatchingNotCancelled { api.searchPeople(person.name) }.orEmpty()
            .firstOrNull { it.id.equals(person.id, ignoreCase = true) } ?: return change
        return FieldChange.AssignedTo(person.copy(uniqueName = found.uniqueName))
    }

    /**
     * People for "Assigned to": the directory's search, or where it is not available (Azure
     * DevOps Server without it) the members of the project's teams whose name matches.
     */
    fun searchPeople(query: String) {
        val modal = _state.value ?: return
        val q = query.trim()
        search?.cancel()
        if (q.isEmpty()) {
            _state.update { it?.copy(people = PeopleSearch()) }
            return
        }
        _state.update { it?.copy(people = it.people.copy(query = q, loading = true)) }
        val project = modal.page.item?.project
        search = scope.launch {
            delay(SEARCH_DELAY_MS)
            val api = api()
            val found = runCatchingNotCancelled { api.searchPeople(q) }?.takeIf { it.isNotEmpty() }
                ?: project?.let { p -> members(api, p).filter { it.name.contains(q, ignoreCase = true) } }.orEmpty()
            _state.update { it?.copy(people = PeopleSearch(q, loading = false, results = found.take(MAX_PEOPLE))) }
        }
    }

    private suspend fun members(api: AdoApi, project: String): List<Person> {
        val key = project.lowercase()
        membersCache[key]?.let { return it }
        val teams = catalog()?.teams?.entries?.firstOrNull { it.key.equals(project, ignoreCase = true) }?.value?.map { it.name }
            ?: runCatchingNotCancelled { api.teams(project) }.orEmpty()
        val read = kotlinx.coroutines.coroutineScope {
            teams.take(MAX_TEAMS).map { team -> async { runCatchingNotCancelled { api.teamMembers(project, team) }.orEmpty() } }
                .awaitAll().flatten().distinctBy { it.id }.sortedBy { it.name.lowercase() }
        }
        membersCache[key] = read
        return read
    }

    private suspend fun <T> runCatchingNotCancelled(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val SEARCH_DELAY_MS = 250L
        const val MAX_PEOPLE = 20
        const val MAX_TEAMS = 5
    }
}
