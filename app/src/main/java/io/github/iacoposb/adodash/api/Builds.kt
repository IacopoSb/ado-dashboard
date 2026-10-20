@file:UseSerializers(InstantSerializer::class)

package io.github.iacoposb.adodash.api

import io.github.iacoposb.adodash.data.InstantSerializer
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** Where a build or a pipeline step stands, from Azure DevOps' status and result. */
@Serializable
enum class BuildState {
    SUCCEEDED,
    /** Succeeded with issues: some steps failed but were allowed to. */
    PARTIAL,
    FAILED,
    CANCELED,
    RUNNING,
    /** Not started yet. */
    QUEUED,
    ;

    val finished get() = this == SUCCEEDED || this == PARTIAL || this == FAILED || this == CANCELED
}

/** A pipeline run. [prId] is set for pull request validation builds. */
@Serializable
data class Build(
    val id: Int,
    val number: String,
    val project: String,
    val definitionId: Int,
    val definitionName: String,
    val repoId: String,
    val repoName: String,
    /** "refs/heads/main", or "refs/pull/305/merge" for a pull request build. */
    val sourceBranch: String,
    /** "pullRequest", "individualCI", "manual"… */
    val reason: String,
    val prId: Int?,
    /** The pull request's own branch, without refs/heads/. */
    val prSourceBranch: String?,
    val requestedBy: String,
    val state: BuildState,
    val queuedAt: Instant?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    /** The build's results page. */
    val url: String,
    /** The pipeline's list of runs. */
    val definitionUrl: String = "",
) {
    /** What the build ran on, as people read it. */
    val branch: String get() = prSourceBranch ?: sourceBranch.removePrefix("refs/heads/")

    /** When it was last heard of: finished, else started, else queued. */
    val at: Instant get() = finishedAt ?: startedAt ?: queuedAt ?: Instant.EPOCH

    fun duration(now: Instant): Duration? = startedAt?.let { Duration.between(it, finishedAt ?: now) }

    /** The results page on its logs. */
    val logUrl: String get() = if (url.isEmpty()) "" else "$url&view=logs"
}

/**
 * A pipeline (build definition) of a project. [folder] is its path as Azure DevOps keeps it:
 * "\\" for the root, "\\Backend\\CI" for a folder; [latest] its latest run, if any.
 */
data class Pipeline(
    val id: Int,
    val name: String,
    val folder: String,
    val project: String,
    val latest: Build? = null,
    /** Its runs in Azure DevOps. */
    val url: String = "",
)

/** A record of a build's timeline: a stage, a phase, a job or a task. */
data class TimelineRecord(
    val id: String,
    val parentId: String?,
    /** "Stage", "Phase", "Job", "Task", "Checkpoint"… */
    val type: String,
    val name: String,
    val order: Int,
    val state: BuildState,
    /** Skipped: never ran because of a condition or an earlier failure. */
    val skipped: Boolean,
    val startedAt: Instant?,
    val finishedAt: Instant?,
)

/** A test run of a build and how many of its tests failed. */
data class TestRun(val id: Int, val name: String, val failed: Int)

/** A failed test: "Class.method" and the first part of its error. */
data class FailedTest(val name: String, val message: String)

/**
 * The status of one of a pull request's policies. [typeId] tells the kind of policy
 * ([BUILD_POLICY] for builds); [buildId] is the build behind a build policy, if queued.
 */
data class PolicyEvaluation(
    val typeId: String,
    val typeName: String,
    val blocking: Boolean,
    val enabled: Boolean,
    /** "approved", "rejected", "running", "queued", "notApplicable", "broken". */
    val status: String,
    val buildId: Int? = null,
    /** A build policy whose build is outdated (the target branch moved on). */
    val expired: Boolean = false,
    /** The name the policy was given (a build policy's "backend-ci"); null when it has none. */
    val displayName: String? = null,
    /**
     * Approvals a "Minimum number of reviewers" policy wants; for "Required reviewers", how many
     * of [requiredReviewerIds] must approve (null: all of them).
     */
    val minApprovers: Int? = null,
    /** The people and groups a "Required reviewers" policy names. */
    val requiredReviewerIds: List<String> = emptyList(),
    /** The author's own approval counts towards the reviewer policies. */
    val creatorVoteCounts: Boolean = false,
    /** When it was last evaluated, for "4 min ago". */
    val completedAt: Instant? = null,
    /** The status a "Status check" policy waits for: "SonarQube" and "quality-gate". */
    val statusGenre: String? = null,
    val statusName: String? = null,
) {
    val isBuild get() = typeId.equals(BUILD_POLICY, ignoreCase = true)
    val isStatus get() = typeId.equals(STATUS_POLICY, ignoreCase = true)

    /** The [PrStatus.key] of the status a "Status check" policy waits for; null for other policies. */
    val statusKey: String? get() = statusName?.takeIf { isStatus && it.isNotBlank() }?.let { PrStatus.key(statusGenre, it) }

    /** The name to show: the policy's own, the status it waits for, or its type's. */
    val name: String get() = displayName
        ?: statusName?.takeIf { isStatus && it.isNotBlank() }?.let { PrStatus.label(statusGenre, it) }
        ?: typeName

    companion object {
        const val BUILD_POLICY = "0609b952-1397-4640-95ec-e00a01b2c241"
        const val MIN_REVIEWERS_POLICY = "fa4e907d-c16b-4a4c-9dfa-4906e5d171dd"
        const val REQUIRED_REVIEWERS_POLICY = "fd2167ab-b0be-447a-8ec8-39368250530e"
        const val COMMENTS_POLICY = "c6a1889d-b943-4856-b76f-9e46bb6b0df2"
        const val WORK_ITEMS_POLICY = "40e92b44-2fe1-4dd6-b3d8-74a9c21d0c6e"
        const val STATUS_POLICY = "cbdc66da-9728-4af8-aada-9a5a32e4a226"
    }
}

/**
 * A status posted on a pull request by a service (SonarQube's quality gate, a scanner…), with or
 * without a policy that waits for it. [state]: "pending", "succeeded", "failed", "error".
 */
data class PrStatus(
    val genre: String?,
    val name: String,
    val state: String,
    val description: String? = null,
    val url: String? = null,
    val at: Instant? = null,
    val id: Int = 0,
) {
    /** What tells two services apart: a newer status with the same key replaces the older. */
    val key get() = key(genre, name)
    val label get() = label(genre, name)

    companion object {
        fun key(genre: String?, name: String) = label(genre, name).lowercase()
        fun label(genre: String?, name: String) = listOfNotNull(genre?.takeIf { it.isNotBlank() }, name).joinToString("/")

        /**
         * The latest status of each service among all those posted (Azure DevOps keeps them all),
         * in the order they were first posted; a service whose latest says it does not apply, or
         * says nothing, is left out.
         */
        fun latest(statuses: List<PrStatus>): List<PrStatus> =
            statuses.groupBy { it.key }.values
                .map { posts -> posts.maxWith(compareBy<PrStatus>({ it.at }, { it.id })) }
                .filter { it.state != "notApplicable" && it.state != "notSet" && it.state.isNotBlank() }
    }
}

/**
 * A comment thread of a pull request; [status] is null for system threads. [filePath] and [line]
 * place a thread on a file ("/src/Gateway.kt", line 88); null for the pull request as a whole.
 */
data class PrThread(
    val id: Int,
    val status: String?,
    val comments: List<PrComment>,
    val filePath: String? = null,
    val line: Int? = null,
    /** [line] is on the base's side (a deleted line) rather than the pull request's. */
    val leftSide: Boolean = false,
) {
    /** Still to be resolved: what Azure DevOps counts as an active comment. */
    val active get() = (status == "active" || status == "pending") && comments.isNotEmpty()
}
