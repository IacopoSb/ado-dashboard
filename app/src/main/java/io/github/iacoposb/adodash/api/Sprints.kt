@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class)

package io.github.iacoposb.adodash.api

import io.github.iacoposb.adodash.data.LocalDateSerializer
import io.github.iacoposb.adodash.data.InstantSerializer
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/** A team's sprint: [path] as in [WorkItemDetail.iterationPath], the days at midnight UTC. */
@Serializable
data class TeamIteration(
    val id: String,
    val name: String,
    val path: String,
    val start: Instant? = null,
    val finish: Instant? = null,
)

/**
 * A move in a team's sprint backlog, as the web's drag and drop sends it: [ids] go between
 * [previousId] and [nextId] (0: none, at that end) among the children of [parentId] (0: the
 * top level). Azure DevOps works out their new Stack Rank or Backlog Priority.
 */
data class BacklogReorder(
    val ids: List<Int>,
    val iterationPath: String,
    val parentId: Int = 0,
    val previousId: Int = 0,
    val nextId: Int = 0,
)

/** A work item of a sprint, with its parent when that is in the sprint too. */
data class IterationLink(val id: Int, val parentId: Int? = null)

/** A work item as the sprint backlog shows it. */
data class BacklogItem(
    val item: WorkItem,
    /** The assignee's identity id, to tell my items by id rather than by name. */
    val assigneeId: String? = null,
    /** Hours. */
    val remainingWork: Double? = null,
    /** Backlog order (Stack Rank or Backlog Priority): lower first. */
    val rank: Double? = null,
    /** System.Parent, wherever the parent is. */
    val parentId: Int? = null,
)

/** Days from [start] to [end], both included. */
@Serializable
data class DayRange(val start: LocalDate, val end: LocalDate) {
    operator fun contains(day: LocalDate) = !day.isBefore(start) && !day.isAfter(end)
}
