package io.github.iacoposb.adodash.api

import kotlinx.serialization.Serializable
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

    /** The project's area tree: the root is the project itself. */
    suspend fun areas(project: String): AreaNode

    /** The project's work item types with their states. */
    suspend fun workItemTypes(project: String): List<WorkItemTypeInfo>

    /** Most recent comments first. */
    suspend fun workItemComments(project: String, id: Int): List<Comment>

    /**
     * A page of a work item's comments for its details: the latest [CommentPage.PAGE_SIZE] (or,
     * with [continuation], those before them), oldest first.
     */
    suspend fun workItemCommentPage(project: String, id: Int, continuation: String? = null): CommentPage =
        CommentPage(workItemComments(project, id).reversed(), null)

    /** Adds a comment ([html] as Azure DevOps stores it) and returns it as saved. */
    suspend fun addWorkItemComment(project: String, id: Int, html: String): Comment =
        throw AdoException(403, "Not supported")

    suspend fun activePullRequests(project: String, filter: PrFilter): List<PullRequest>

    /** All comments of all threads, system ones excluded. */
    suspend fun pullRequestComments(pr: PullRequest): List<PrComment>

    /** The threads with their status and comments (system ones excluded). */
    suspend fun pullRequestThreads(pr: PullRequest): List<PrThread> =
        pullRequestComments(pr).groupBy { it.threadId }.map { (id, comments) -> PrThread(id, null, comments) }

    /** The status of each policy of [pr]; needs [PullRequest.projectId]. */
    suspend fun policyEvaluations(pr: PullRequest): List<PolicyEvaluation> = emptyList()

    /**
     * The project's latest builds, most recently queued first. Throws [AdoException] 401 or 403
     * when the credentials cannot read builds (a PAT without Build (read)).
     */
    suspend fun builds(project: String, top: Int = 50): List<Build> = emptyList()

    suspend fun build(project: String, id: Int): Build = throw AdoException(404, "No build $id")

    /**
     * All the pipelines of [project] with their latest run, by name. Throws [AdoException] 401
     * or 403 like [builds].
     */
    suspend fun pipelines(project: String): List<Pipeline> = emptyList()

    /** The latest [top] runs of a pipeline, most recently queued first. */
    suspend fun pipelineRuns(project: String, definitionId: Int, top: Int = 10): List<Build> = emptyList()

    suspend fun buildTimeline(project: String, id: Int): List<TimelineRecord> = emptyList()

    /** Reruns the failed jobs of a finished build. */
    suspend fun retryBuild(project: String, id: Int): Unit = throw AdoException(403, "Not supported")

    suspend fun testRuns(project: String, buildId: Int): List<TestRun> = emptyList()

    suspend fun failedTests(project: String, runId: Int, top: Int = 10): List<FailedTest> = emptyList()

    /** Repository id to its default branch (without refs/heads/). */
    suspend fun defaultBranches(project: String): Map<String, String> = emptyMap()

    /** The project's team names, sorted. */
    suspend fun teams(project: String): List<String> = emptyList()

    /**
     * The area paths of a team's board (its Team field values); null when the team field is
     * not the area path, so the board cannot be told apart from the rest of the project.
     */
    suspend fun teamAreas(project: String, team: String): List<TeamArea>? = null

    /** The name of the team's current sprint; null when it has none. */
    suspend fun currentSprint(project: String, team: String): String? = null

    /** The project's Git repository names, sorted. */
    suspend fun repositories(project: String): List<String> = emptyList()

    /** A work item with all its fields and its links, for its details. */
    suspend fun workItemDetail(id: Int): WorkItemDetail = throw AdoException(404, "No work item $id")

    /**
     * Changes one field of a work item if it is still at [rev] (see [WorkItemPatch]); returns it
     * as saved. Someone else's change in between fails with [AdoException] 409 or 412.
     */
    suspend fun updateWorkItem(id: Int, rev: Int, change: FieldChange): WorkItemDetail =
        throw AdoException(403, "Not supported")

    /** The reference names of the fields a work item type has. */
    suspend fun typeFields(project: String, type: String): Set<String> = emptySet()

    /** The project's iterations, in tree order, the project's root included. */
    suspend fun iterations(project: String): List<Iteration> = emptyList()

    /** People whose name or e-mail matches [query], from the organization's directory. */
    suspend fun searchPeople(query: String): List<Person> = emptyList()

    /** The members of a team. */
    suspend fun teamMembers(project: String, team: String): List<Person> = emptyList()

    /** A pull request by id alone, as a work item links it. */
    suspend fun pullRequestById(id: Int): LinkedPullRequest = throw AdoException(404, "No pull request $id")

    /** A pull request by id alone, for its page (opened from an event or a notification). */
    suspend fun pullRequest(id: Int): PullRequest = throw AdoException(404, "No pull request $id")

    /** [pr] read again with its description, labels and linked work items, for its page. */
    suspend fun pullRequestDetail(pr: PullRequest): PullRequestDetail = PullRequestDetail(pr)

    /** Sets the vote of [reviewerId] (me) on [pr], adding them as a reviewer if needed. */
    suspend fun votePullRequest(pr: PullRequest, reviewerId: String, vote: Int): Reviewer =
        throw AdoException(403, "Not supported")

    /** Replies in a thread of [pr]: [content] is markdown, mentions as `@<id>`. */
    suspend fun addPullRequestComment(pr: PullRequest, threadId: Int, parentId: Int, content: String): PrComment =
        throw AdoException(403, "Not supported")

    /** A new thread on [pr] as a whole (not on a file), active, with [content] as its comment. */
    suspend fun createPullRequestThread(pr: PullRequest, content: String): PrThread =
        throw AdoException(403, "Not supported")

    /** Changes a thread's status (see [ThreadStatus]); returns it as saved. */
    suspend fun setPullRequestThreadStatus(pr: PullRequest, threadId: Int, status: String): PrThread =
        throw AdoException(403, "Not supported")

    /** Display names of identities by id (those mentioned as `@<id>`); those not found are left out. */
    suspend fun identityNames(ids: Collection<String>): Map<String, String> = emptyMap()
}

/** An area path of a team, with or without the areas below it. */
data class TeamArea(val path: String, val includeChildren: Boolean)

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
    /** "Project\\Area\\Sub-area"; the project name for items at the root. */
    val areaPath: String = project,
)

/** An area; [path] is as in [WorkItem.areaPath] and in WIQL ("Project\\Area"). */
@Serializable
data class AreaNode(val name: String, val path: String, val children: List<AreaNode> = emptyList())

data class WorkItemTypeInfo(
    val name: String,
    /** Hex RGB without '#', as Azure Boards shows the type. */
    val color: String?,
    /** In the process' order. */
    val states: List<StateInfo>,
    /** In Microsoft.HiddenCategory (Code Review Request and the like): not for users. */
    val hidden: Boolean = false,
)

data class StateInfo(val name: String, val category: String)

data class Comment(
    val id: Int,
    val workItemId: Int,
    val author: Identity,
    /** HTML, mentions as `<a href="#" data-vss-mention="version:2.0,<id>">@Name</a>`. */
    val text: String,
    val createdAt: Instant,
    /** Changed after it was posted. */
    val edited: Boolean = false,
)

/** Comments oldest first; [continuation] reads those before them, null when there are none. */
data class CommentPage(val comments: List<Comment>, val continuation: String?, val total: Int = comments.size) {
    companion object {
        const val PAGE_SIZE = 50
    }
}

sealed interface PrFilter {
    data class Reviewer(val id: String) : PrFilter
    data class Creator(val id: String) : PrFilter
    /** Every active PR, for views that show all of them. */
    data object All : PrFilter
}

data class Reviewer(
    val id: String,
    val name: String,
    val vote: Int,
    val required: Boolean,
    /** A group or a team rather than a person. */
    val group: Boolean = false,
)

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
    val projectId: String = "",
    /** "succeeded", "conflicts", "queued", "failure", "rejectedByPolicy"; null if unknown. */
    val mergeStatus: String? = null,
    /** The source branch's last commit merged: what the builds and policies ran on. */
    val lastCommit: String? = null,
) {
    val hasConflicts get() = mergeStatus == "conflicts"
}

data class PrComment(
    val threadId: Int,
    val id: Int,
    val author: Identity,
    /** Markdown, mentions as `@<id>`. */
    val text: String,
    val publishedAt: Instant,
    /** The comment it replies to; 0 for the thread's first. */
    val parentId: Int = 0,
)

/** Reviewer votes as Azure DevOps encodes them. */
object Vote {
    const val APPROVED = 10
    const val APPROVED_WITH_SUGGESTIONS = 5
    const val NONE = 0
    const val WAITING = -5
    const val REJECTED = -10
}
