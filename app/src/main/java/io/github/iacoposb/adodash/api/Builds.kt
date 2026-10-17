package io.github.iacoposb.adodash.api

import java.time.Duration
import java.time.Instant

/** Where a build or a pipeline step stands, from Azure DevOps' status and result. */
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
) {
    val isBuild get() = typeId.equals(BUILD_POLICY, ignoreCase = true)

    companion object {
        const val BUILD_POLICY = "0609b952-1397-4640-95ec-e00a01b2c241"
    }
}

/** A comment thread of a pull request; [status] is null for system threads. */
data class PrThread(val id: Int, val status: String?, val comments: List<PrComment>) {
    /** Still to be resolved: what Azure DevOps counts as an active comment. */
    val active get() = (status == "active" || status == "pending") && comments.isNotEmpty()
}
