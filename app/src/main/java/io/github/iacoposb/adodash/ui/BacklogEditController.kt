package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.BacklogReorder
import io.github.iacoposb.adodash.api.isDenied
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.sync.BacklogMove
import io.github.iacoposb.adodash.sync.BacklogOrder
import io.github.iacoposb.adodash.sync.Sprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a move was not saved: the row is back where it was. */
data class ReorderError(val id: Int, val problem: SaveProblem, val message: String = "")

/** What the sprint backlog's changes show: a move that failed. */
data class BacklogEditState(
    val reorderError: ReorderError? = null,
)

/**
 * Saves the changes made in the sprint backlog: its order, shown at once and put back when
 * Azure DevOps refuses it.
 */
class BacklogEditController(
    private val scope: CoroutineScope,
    private val api: suspend () -> AdoApi,
    /** Changes the sprint on screen (see [io.github.iacoposb.adodash.sync.SyncRepository.applyOwnSprint]). */
    private val onSprint: (BoardRef, (Sprint) -> Sprint) -> Unit,
    private val errorText: (Throwable) -> String,
) {
    private val _state = MutableStateFlow(BacklogEditState())
    val state: StateFlow<BacklogEditState> = _state.asStateFlow()

    /** Moves are sent one at a time, in the order they were made. */
    private val moves = Mutex()

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
                    val problem = if (e is AdoException && e.isDenied) SaveProblem.DENIED else SaveProblem.OTHER
                    _state.update { it.copy(reorderError = ReorderError(move.id, problem, errorText(e))) }
                }
            }
        }
    }

    fun dismissReorderError() = _state.update { it.copy(reorderError = null) }
}
