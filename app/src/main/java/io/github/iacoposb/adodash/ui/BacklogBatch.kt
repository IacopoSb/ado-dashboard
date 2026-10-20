package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.FieldChange
import io.github.iacoposb.adodash.api.Iteration
import io.github.iacoposb.adodash.api.StateInfo
import io.github.iacoposb.adodash.api.TeamIteration
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.isConflict
import io.github.iacoposb.adodash.api.isDenied

/** The changes the backlog's selection offers, each with its menu. */
enum class BatchKind { STATE, ASSIGN, ITERATION, AREA, PRIORITY }

/** How the change of one work item went. */
sealed interface BatchOutcome {
    /** Changed, or already as asked. */
    data object Done : BatchOutcome
    data class Failed(val problem: SaveProblem, val message: String = "") : BatchOutcome
}

/** A work item a change was not saved to, and why. */
data class BatchFailure(val item: WorkItem, val problem: SaveProblem, val message: String = "")

/** "N updated, M failed" for a change made to the selection. */
data class BatchResult(val change: FieldChange, val updated: Int, val failures: List<BatchFailure> = emptyList()) {
    /** Some refused for lack of permission: said in so many words. */
    val denied get() = failures.any { it.problem == SaveProblem.DENIED }
}

/** The selection's changes, as far as they are logic: what to offer, and how they went. */
object BacklogBatch {
    /** Work items changed at the same time. */
    const val PARALLEL = 4

    /** The outcomes of a change in one result, the failures in the order the items were listed. */
    fun result(change: FieldChange, outcomes: List<Pair<WorkItem, BatchOutcome>>) = BatchResult(
        change,
        updated = outcomes.count { it.second == BatchOutcome.Done },
        failures = outcomes.mapNotNull { (item, outcome) -> (outcome as? BatchOutcome.Failed)?.let { BatchFailure(item, it.problem, it.message) } },
    )

    fun problemOf(e: Throwable): SaveProblem = when {
        e is AdoException && e.isDenied -> SaveProblem.DENIED
        e is AdoException && e.isConflict -> SaveProblem.CONFLICT
        else -> SaveProblem.OTHER
    }

    /**
     * The states every type of the selection has, in the first type's process order (names
     * compared ignoring case, as Azure DevOps does): a state one of them lacks would fail on
     * those items. Null while the states of some type are not read yet.
     */
    fun commonStates(types: Collection<String>, statesByType: Map<String, List<StateInfo>>): List<StateInfo>? {
        val lists = types.map { it.lowercase() }.distinct().map { statesByType[it] ?: return null }
        if (lists.isEmpty()) return emptyList()
        return lists.first().filter { s -> lists.all { l -> l.any { it.name.equals(s.name, ignoreCase = true) } } }
    }

    /**
     * The sprint after [current]: the team's next one by start date; without the team's sprints,
     * the project's iteration that starts next, under the same parent first (other teams may run
     * sprints of their own elsewhere in the tree). Null when there is none.
     */
    fun nextIteration(current: TeamIteration, team: List<TeamIteration>, project: List<Iteration>): Iteration? {
        val start = current.start ?: return null
        team.filter { it.start != null && it.start > start && !it.path.equals(current.path, ignoreCase = true) }
            .minByOrNull { it.start!! }
            ?.let { return Iteration(it.name, it.path, it.start, it.finish) }
        val later = project.filter { it.start != null && it.start > start && !it.path.equals(current.path, ignoreCase = true) }
        val parent = current.path.substringBeforeLast('\\')
        return (later.filter { it.path.substringBeforeLast('\\').equals(parent, ignoreCase = true) }.ifEmpty { later })
            .minByOrNull { it.start!! }
    }
}
