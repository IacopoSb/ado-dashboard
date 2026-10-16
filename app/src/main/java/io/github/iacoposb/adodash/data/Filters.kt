package io.github.iacoposb.adodash.data

import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.api.WorkItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the whole app shows: the dashboard, its counters, the activity feed and the
 * notifications. The defaults filter nothing.
 */
@Serializable
data class Filters(
    /**
     * Selected projects and area paths ("Project", "Project\Area"); each includes everything
     * below it, areas added later too. Null selects every project, present and future.
     */
    val areas: Set<String>? = null,
    /** Work item type to the states hidden for it. */
    val hiddenStates: Map<String, Set<String>> = emptyMap(),
    /** Drafts among the pull requests to review. */
    val draftsToReview: Boolean = true,
    val hideApprovedByMe: Boolean = false,
) {
    val selectsNothing get() = areas?.isEmpty() == true

    /** [path] is the selected node or below one. */
    fun coversArea(path: String): Boolean = areas == null || areas.any { isUnder(path, it) }

    /** Something in the project is selected: its pull requests are shown. */
    fun includesProject(project: String): Boolean =
        areas == null || areas.any { it.substringBefore(SEPARATOR).equals(project, ignoreCase = true) }

    fun hides(type: String, state: String): Boolean = hiddenStates.any { (t, states) ->
        t.equals(type, ignoreCase = true) && states.any { it.equals(state, ignoreCase = true) }
    }

    fun accepts(wi: WorkItem): Boolean = coversArea(wi.areaPath.ifEmpty { wi.project }) && !hides(wi.type, wi.state)

    fun acceptsMine(pr: PullRequest): Boolean = includesProject(pr.project)

    fun acceptsReview(pr: PullRequest, me: Identity): Boolean {
        if (!includesProject(pr.project)) return false
        if (pr.draft && !draftsToReview) return false
        val myVote = pr.reviewers.firstOrNull { it.id.equals(me.id, ignoreCase = true) }?.vote
        return !(hideApprovedByMe && (myVote == Vote.APPROVED || myVote == Vote.APPROVED_WITH_SUGGESTIONS))
    }

    /** Events stored before 0.0.4 know neither project nor area: always shown. */
    fun acceptsEvent(event: Event): Boolean = when {
        event.area != null -> coversArea(event.area)
        event.project != null -> includesProject(event.project)
        else -> true
    }

    /**
     * WIQL conditions (each starting with AND) that leave out what these filters hide, so the
     * server's top 200 are all items to show. Not for [selectsNothing]: no query is needed.
     */
    fun wiqlConditions(): String = buildString {
        if (areas != null && areas.isNotEmpty()) {
            append(" AND (")
            append(areas.sorted().joinToString(" OR ") { "[System.AreaPath] UNDER ${quote(it)}" })
            append(")")
        }
        hiddenStates.toSortedMap().forEach { (type, states) ->
            if (states.isEmpty()) return@forEach
            append(" AND ([System.WorkItemType] <> ${quote(type)} OR [System.State] NOT IN (")
            append(states.sorted().joinToString(",") { quote(it) })
            append("))")
        }
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val SEPARATOR = '\\'
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String?): Filters =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: Filters()

        fun isUnder(path: String, ancestor: String) =
            path.equals(ancestor, ignoreCase = true) || path.startsWith("$ancestor$SEPARATOR", ignoreCase = true)

        private fun quote(value: String) = "'" + value.replace("'", "''") + "'"
    }
}
