package io.github.iacoposb.adodash.api

import java.time.Instant

/** The part of the Azure DevOps REST API the dashboard uses. */
interface AdoApi {
    /** The user the PAT belongs to. */
    suspend fun me(): Identity

    suspend fun projects(): List<String>

    /** Ids matching a WIQL query run at organization level. */
    suspend fun queryWorkItemIds(wiql: String, top: Int = 200): List<Int>

    suspend fun workItems(ids: List<Int>): List<WorkItem>

    /** State name to its category (Proposed, InProgress, Resolved, Completed, Removed). */
    suspend fun stateCategories(project: String, type: String): Map<String, String>

    /** Most recent comments first. */
    suspend fun workItemComments(project: String, id: Int): List<Comment>

    suspend fun activePullRequests(project: String, filter: PrFilter): List<PullRequest>

    /** All comments of all threads, system ones excluded. */
    suspend fun pullRequestComments(pr: PullRequest): List<PrComment>
}

/** [message] is English; a 401 is shown to the user translated (see errorText). */
class AdoException(val code: Int, message: String) : Exception(message)

data class Identity(val id: String, val name: String)

data class WorkItem(
    val id: Int,
    val project: String,
    val type: String,
    val title: String,
    val state: String,
    val priority: Int?,
    val assignedTo: String?,
    val changedAt: Instant,
    val url: String,
)

data class Comment(
    val id: Int,
    val workItemId: Int,
    val author: Identity,
    val text: String,
    val createdAt: Instant,
)

sealed interface PrFilter {
    data class Reviewer(val id: String) : PrFilter
    data class Creator(val id: String) : PrFilter
}

data class Reviewer(val id: String, val name: String, val vote: Int, val required: Boolean)

data class PullRequest(
    val id: Int,
    val project: String,
    val repoId: String,
    val repoName: String,
    val title: String,
    val author: Identity,
    val sourceBranch: String,
    val targetBranch: String,
    val draft: Boolean,
    val reviewers: List<Reviewer>,
    val createdAt: Instant,
    val url: String,
)

data class PrComment(
    val threadId: Int,
    val id: Int,
    val author: Identity,
    val text: String,
    val publishedAt: Instant,
)

/** Reviewer votes as Azure DevOps encodes them. */
object Vote {
    const val APPROVED = 10
    const val APPROVED_WITH_SUGGESTIONS = 5
    const val NONE = 0
    const val WAITING = -5
    const val REJECTED = -10
}
