package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.BacklogReorder
import io.github.iacoposb.adodash.api.FieldChange
import io.github.iacoposb.adodash.api.Iteration
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.StateInfo
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemDetail
import io.github.iacoposb.adodash.api.isConflict
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.sync.BacklogMove
import io.github.iacoposb.adodash.sync.BacklogOrder
import io.github.iacoposb.adodash.sync.FilterCatalog
import io.github.iacoposb.adodash.sync.Sprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Why a move was not saved: the row is back where it was. */
data class ReorderError(val id: Int, val problem: SaveProblem, val message: String = "")

/** A change being made to the selection: [done] of [total] work items so far. */
data class BatchProgress(val done: Int, val total: Int)

/**
 * What the selection's menus offer: the states of each type (by lowercase name), the project's
 * iterations and areas, the sprint after the current one. Null while read.
 */
data class BatchOptions(
    val states: Map<String, List<StateInfo>> = emptyMap(),
    val iterations: List<Iteration>? = null,
    val areas: AreaNode? = null,
    val nextSprint: Iteration? = null,
)

/** What the sprint backlog's changes show: a move that failed, the selection's menus and changes. */
data class BacklogEditState(
    val reorderError: ReorderError? = null,
    val options: BatchOptions = BatchOptions(),
    val people: PeopleSearch = PeopleSearch(),
    /** Me, offered first in "Assign to". */
    val me: Person? = null,
    val progress: BatchProgress? = null,
    val result: BatchResult? = null,
)

/**
 * Saves the changes made in the sprint backlog: its order, shown at once and put back when
 * Azure DevOps refuses it, and a field changed on all the selected work items at once.
 */
class BacklogEditController(
    private val scope: CoroutineScope,
    private val api: suspend () -> AdoApi,
    /** Changes the sprint on screen (see [io.github.iacoposb.adodash.sync.SyncRepository.applyOwnSprint]). */
    private val onSprint: (BoardRef, (Sprint) -> Sprint) -> Unit,
    private val errorText: (Throwable) -> String,
    private val catalog: () -> FilterCatalog? = { null },
    private val me: () -> Person? = { null },
    /** A work item saved: its rows show it at once, and assigning it to me is not news. */
    private val onSaved: suspend (WorkItemDetail, assignedToMe: Boolean) -> Unit = { _, _ -> },
    /** A change of the selection finished: the backlog read again. */
    private val onBatchDone: () -> Unit = {},
) {
    private val _state = MutableStateFlow(BacklogEditState())
    val state: StateFlow<BacklogEditState> = _state.asStateFlow()

    /** Moves are sent one at a time, in the order they were made. */
    private val moves = Mutex()
    private var search: Job? = null
    private var optionsFor: BoardRef? = null
    private val statesCache = mutableMapOf<String, List<StateInfo>>()

    /**
     * Shows [move] at once and sends it. Refused, the item goes back between the neighbours it
     * had in [sprint] and the reason is shown; done, the items moved get their new rank.
     */
    fun reorder(sprint: Sprint, move: BacklogMove) {
        val back = BacklogOrder.current(sprint, move.id) ?: return
        if (back.previousId == move.previousId && back.nextId == move.nextId) return
        val board = sprint.board
        onSprint(board) { BacklogOrder.apply(it, move) }
        _state.update { it.copy(reorderError = null) }
        scope.launch {
            moves.withLock {
                try {
                    val ranks = api().reorderSprintItems(
                        board.project, board.team, sprint.iteration.id,
                        BacklogReorder(listOf(move.id), sprint.iteration.path, move.parentId, move.previousId, move.nextId),
                    )
                    onSprint(board) { BacklogOrder.withRanks(it, ranks) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onSprint(board) { BacklogOrder.apply(it, back) }
                    val problem = if (BacklogBatch.problemOf(e) == SaveProblem.DENIED) SaveProblem.DENIED else SaveProblem.OTHER
                    _state.update { it.copy(reorderError = ReorderError(move.id, problem, errorText(e))) }
                }
            }
        }
    }

    fun dismissReorderError() = _state.update { it.copy(reorderError = null) }

    /**
     * The selection's menus for [sprint]: the states of its types, the iterations with the next
     * sprint, the areas. Read once per board; what cannot be read stays empty.
     */
    fun loadOptions(sprint: Sprint) {
        _state.update { it.copy(me = me()) }
        val board = sprint.board
        if (optionsFor == board) return
        optionsFor = board
        _state.update { it.copy(options = BatchOptions(states = it.options.states)) }
        val project = board.project
        scope.launch {
            val api = api()
            sprint.items.map { it.item.type }.distinctBy { it.lowercase() }.forEach { type ->
                launch {
                    val key = "$project/$type".lowercase()
                    val states = statesCache[key] ?: quietly { api.stateCategories(project, type) }
                        ?.map { (name, category) -> StateInfo(name, category) }?.also { statesCache[key] = it }
                        ?: emptyList()
                    _state.update { it.copy(options = it.options.copy(states = it.options.states + (type.lowercase() to states))) }
                }
            }
            launch {
                val areas = catalog()?.projects?.firstOrNull { it.name.equals(project, ignoreCase = true) }
                    ?: quietly { api.areas(project) }
                _state.update { it.copy(options = it.options.copy(areas = areas)) }
            }
            launch {
                val iterations = quietly { api.iterations(project) }.orEmpty()
                val team = quietly { api.teamIterations(project, board.team) }.orEmpty()
                val next = BacklogBatch.nextIteration(sprint.iteration, team, iterations)
                _state.update { it.copy(options = it.options.copy(iterations = iterations, nextSprint = next)) }
            }
        }
    }

    /** People for "Assign to": the directory's search, or the board team's members matching. */
    fun searchPeople(board: BoardRef, query: String) {
        val q = query.trim()
        search?.cancel()
        if (q.isEmpty()) {
            _state.update { it.copy(people = PeopleSearch()) }
            return
        }
        _state.update { it.copy(people = it.people.copy(query = q, loading = true)) }
        search = scope.launch {
            delay(SEARCH_DELAY_MS)
            val api = api()
            val found = quietly { api.searchPeople(q) }?.takeIf { it.isNotEmpty() }
                ?: quietly { api.teamMembers(board.project, board.team) }.orEmpty().filter { it.name.contains(q, ignoreCase = true) }
            _state.update { it.copy(people = PeopleSearch(q, loading = false, results = found.take(MAX_PEOPLE))) }
        }
    }

    /**
     * Makes [change] on every one of [items], a few at a time, each read first so that it goes
     * against its latest revision (and once more after someone else's change in between). Then
     * "N updated, M failed", and the backlog read again.
     */
    fun runBatch(items: List<WorkItem>, change: FieldChange) {
        if (items.isEmpty() || _state.value.progress != null) return
        _state.update { it.copy(progress = BatchProgress(0, items.size), result = null, people = PeopleSearch()) }
        scope.launch {
            val api = api()
            val sent = resolved(api, change)
            val limit = Semaphore(BacklogBatch.PARALLEL)
            val outcomes = coroutineScope {
                items.map { item ->
                    async {
                        limit.withPermit {
                            val outcome = save(api, item.id, sent)
                            _state.update { s -> s.copy(progress = s.progress?.let { it.copy(done = it.done + 1) }) }
                            item to outcome
                        }
                    }
                }.awaitAll()
            }
            _state.update { it.copy(progress = null, result = BacklogBatch.result(change, outcomes)) }
            onBatchDone()
        }
    }

    /** The work items that failed, changed again. */
    fun retryBatch() {
        val result = _state.value.result ?: return
        runBatch(result.failures.map { it.item }, result.change)
    }

    fun dismissBatch() = _state.update { it.copy(result = null) }

    private suspend fun save(api: AdoApi, id: Int, change: FieldChange): BatchOutcome {
        repeat(ATTEMPTS) { attempt ->
            try {
                val detail = api.workItemDetail(id)
                if (detail.with(change) == detail) return BatchOutcome.Done
                val result = api.updateWorkItem(id, detail.rev, change)
                val mine = me()
                val assignedToMe = change is FieldChange.AssignedTo && change.person != null && mine != null &&
                    (change.person.id.equals(mine.id, ignoreCase = true) || change.person.name == mine.name)
                onSaved(result, assignedToMe)
                return BatchOutcome.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Someone else saved in between: read it again and try once more
                if (e is AdoException && e.isConflict && attempt < ATTEMPTS - 1) return@repeat
                return BatchOutcome.Failed(BacklogBatch.problemOf(e), errorText(e))
            }
        }
        return BatchOutcome.Failed(SaveProblem.CONFLICT)
    }

    /** Me without a unique name (the sign-in gives only the name): looked up first. */
    private suspend fun resolved(api: AdoApi, change: FieldChange): FieldChange {
        val person = (change as? FieldChange.AssignedTo)?.person ?: return change
        if (person.uniqueName.isNotBlank()) return change
        val found = quietly { api.searchPeople(person.name) }.orEmpty()
            .firstOrNull { it.id.equals(person.id, ignoreCase = true) } ?: return change
        return FieldChange.AssignedTo(person.copy(uniqueName = found.uniqueName))
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val SEARCH_DELAY_MS = 250L
        const val MAX_PEOPLE = 20
        /** Reads and saves per work item: the second after a conflict. */
        const val ATTEMPTS = 2
    }
}
