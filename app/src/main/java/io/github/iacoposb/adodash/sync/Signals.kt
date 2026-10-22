package io.github.iacoposb.adodash.sync

import kotlinx.serialization.Serializable
import io.github.iacoposb.adodash.api.Build
import io.github.iacoposb.adodash.api.BuildState
import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PrStatus
import io.github.iacoposb.adodash.api.PrThread
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.Vote

/** The build of a pull request as its row shows it. */
@Serializable
enum class BuildSignal {
    SUCCEEDED, FAILED, RUNNING,
    /** A required build outdated (the target branch moved on): to be queued again. */
    EXPIRED,
    NONE,
}

/** What a pull request's row says besides its title: build, threads, conflicts, ready. */
@Serializable
data class PrSignals(
    val build: BuildSignal = BuildSignal.NONE,
    /** Null when the threads could not be read. */
    val activeThreads: Int? = null,
    val conflicts: Boolean = false,
    val readyToComplete: Boolean = false,
    /** A blocking "Comments must be resolved" policy not met. */
    val commentsBlocking: Boolean = false,
    /** A blocking "Linked work items" policy not met. */
    val workItemsMissing: Boolean = false,
    /** A blocking reviewer policy not met, or a required reviewer who has not approved. */
    val approvalsMissing: Boolean = false,
    /** Names of the checks other than builds that failed: blocking policies and posted statuses. */
    val failedChecks: List<String> = emptyList(),
    /** Names of the blocking checks other than builds still waiting or running. */
    val pendingChecks: List<String> = emptyList(),
)

/** Derives [PrSignals] from what Azure DevOps says of a pull request. Pure, for the tests. */
object Signals {

    /**
     * [evaluations] null when the policies could not be read, [threads] likewise; [builds] are
     * the project's latest builds, used only when no build policy tells the build's status;
     * [statuses] the latest each service posted ([PrStatus.latest]), null when not read.
     */
    fun of(
        pr: PullRequest,
        evaluations: List<PolicyEvaluation>?,
        threads: List<PrThread>?,
        builds: List<Build>,
        statuses: List<PrStatus>? = null,
    ): PrSignals {
        val build = buildOf(pr, evaluations, builds)
        val active = threads?.count { it.active }
        val blocking = evaluations.orEmpty().filter { it.enabled && it.blocking && !passes(it) }
        fun unmet(type: String) = blocking.any { it.typeId.equals(type, ignoreCase = true) }
        val other = blocking.filter { e -> !e.isBuild && OWN_SIGNAL.none { e.typeId.equals(it, ignoreCase = true) } }
        // A status a policy waits for is named once, by the policy
        val backed = evaluations.orEmpty().filter { it.enabled && it.status != "notApplicable" }.mapNotNull { it.statusKey }.toSet()
        val failedStatuses = statuses.orEmpty().filter { it.key !in backed && it.state in FAILED_STATUS }.map { it.label }
        return PrSignals(
            build = build,
            activeThreads = active,
            conflicts = pr.hasConflicts,
            readyToComplete = readyToComplete(pr, evaluations, active, build),
            commentsBlocking = unmet(PolicyEvaluation.COMMENTS_POLICY),
            workItemsMissing = unmet(PolicyEvaluation.WORK_ITEMS_POLICY),
            approvalsMissing = !pr.draft && (
                unmet(PolicyEvaluation.MIN_REVIEWERS_POLICY) || unmet(PolicyEvaluation.REQUIRED_REVIEWERS_POLICY) ||
                    pr.reviewers.any { it.required && it.vote < Vote.APPROVED_WITH_SUGGESTIONS }
                ),
            failedChecks = (other.filter { it.status == "rejected" || it.status == "broken" }.map { it.name } + failedStatuses).distinct(),
            pendingChecks = other.filter { it.status == "queued" || it.status == "running" }.map { it.name }.distinct(),
        )
    }

    /** Met, or not asked of this pull request; an outdated pass is not met. */
    private fun passes(e: PolicyEvaluation) = (e.status == "approved" && !e.expired) || e.status == "notApplicable"

    /** From the build policies if there are any, otherwise from the pull request's own builds. */
    fun buildOf(pr: PullRequest, evaluations: List<PolicyEvaluation>?, builds: List<Build>): BuildSignal {
        val policies = evaluations.orEmpty().filter { it.enabled && it.isBuild }
        if (policies.isNotEmpty()) return combine(policies.map(::signalOf))
        val own = latestPerDefinition(builds.filter { it.prId == pr.id && (it.repoId.isEmpty() || it.repoId == pr.repoId) })
        return combine(own.map { signalOf(it.state) })
    }

    /** The latest build of each pipeline among [builds]. */
    fun latestPerDefinition(builds: List<Build>): List<Build> =
        builds.groupBy { it.definitionId }.values.map { runs -> runs.maxBy { it.queuedAt ?: it.at } }

    /**
     * Ready to complete: not a draft, no conflicts, no active threads, every blocking policy
     * passed (none expired), every required reviewer approved and no one rejected, and no build
     * failing or still running. Unknown policies or threads are not ready.
     */
    fun readyToComplete(pr: PullRequest, evaluations: List<PolicyEvaluation>?, activeThreads: Int?, build: BuildSignal): Boolean {
        if (pr.draft || evaluations == null || activeThreads == null || activeThreads > 0) return false
        if (pr.mergeStatus in BLOCKING_MERGE) return false
        if (build == BuildSignal.FAILED || build == BuildSignal.RUNNING) return false
        val policiesPass = evaluations.filter { it.enabled && it.blocking }.all(::passes)
        val reviewersPass = pr.reviewers.filter { it.required }.all { it.vote >= Vote.APPROVED_WITH_SUGGESTIONS } &&
            pr.reviewers.none { it.vote == Vote.REJECTED }
        return policiesPass && reviewersPass
    }

    private fun signalOf(e: PolicyEvaluation): BuildSignal = when (e.status) {
        "rejected", "broken" -> BuildSignal.FAILED
        "running" -> BuildSignal.RUNNING
        // Queued without a build: a manual build not requested yet, or one expired
        "queued" -> when {
            e.expired -> expired(e)
            e.buildId != null -> BuildSignal.RUNNING
            else -> BuildSignal.NONE
        }
        "approved" -> if (e.expired) expired(e) else BuildSignal.SUCCEEDED
        else -> BuildSignal.NONE
    }

    /** An optional build left outdated does not hold the pull request back. */
    private fun expired(e: PolicyEvaluation) = if (e.blocking) BuildSignal.EXPIRED else BuildSignal.NONE

    fun signalOf(state: BuildState): BuildSignal = when (state) {
        BuildState.SUCCEEDED -> BuildSignal.SUCCEEDED
        BuildState.FAILED, BuildState.PARTIAL -> BuildSignal.FAILED
        BuildState.RUNNING, BuildState.QUEUED -> BuildSignal.RUNNING
        BuildState.CANCELED -> BuildSignal.NONE
    }

    /** A failure wins, then a build still running, then one expired; succeeded only if one did. */
    private fun combine(signals: List<BuildSignal>): BuildSignal = when {
        BuildSignal.FAILED in signals -> BuildSignal.FAILED
        BuildSignal.RUNNING in signals -> BuildSignal.RUNNING
        BuildSignal.EXPIRED in signals -> BuildSignal.EXPIRED
        BuildSignal.SUCCEEDED in signals -> BuildSignal.SUCCEEDED
        else -> BuildSignal.NONE
    }

    /** Policies whose result may still change without a new commit: read them again. */
    fun pending(evaluations: List<PolicyEvaluation>) = evaluations.any { it.status == "running" || it.status == "queued" }

    /** Statuses whose result may still change: read them again. */
    fun pendingStatuses(statuses: List<PrStatus>) = statuses.any { it.state == "pending" }

    private val BLOCKING_MERGE = setOf("conflicts", "failure", "rejectedByPolicy")
    private val FAILED_STATUS = setOf("failed", "error")

    /** Policies with a signal of their own on the row, not counted among the other checks. */
    private val OWN_SIGNAL = listOf(
        PolicyEvaluation.COMMENTS_POLICY, PolicyEvaluation.WORK_ITEMS_POLICY,
        PolicyEvaluation.MIN_REVIEWERS_POLICY, PolicyEvaluation.REQUIRED_REVIEWERS_POLICY,
    )
}

/** The pipeline tile: which builds it shows and how it counts them. Pure, for the tests. */
object Pipelines {
    /** Dots in the tile. */
    const val DOTS = 6

    data class Counts(val failed: Int, val running: Int, val ok: Int)

    /**
     * The latest build of each pipeline for each of [myPrs], and the latest build of the default
     * branch of each repository [acceptsRepo] lets in ([defaultBranches]: repository id to branch).
     * Most recent first.
     */
    fun tile(
        builds: List<Build>,
        myPrs: List<PullRequest>,
        defaultBranches: Map<String, String>,
        acceptsRepo: (Build) -> Boolean,
    ): List<Build> {
        val prIds = myPrs.associateBy { it.id }
        val prBuilds = builds.filter { b -> b.prId != null && prIds[b.prId]?.let { b.repoId.isEmpty() || it.repoId == b.repoId } == true }
            .groupBy { it.prId }.values.flatMap { Signals.latestPerDefinition(it) }
        val branchBuilds = builds.filter { b ->
            b.prId == null && acceptsRepo(b) &&
                defaultBranches[b.repoId]?.let { b.sourceBranch.removePrefix("refs/heads/") == it } == true
        }.groupBy { it.repoId }.values.map { runs -> runs.maxBy { it.queuedAt ?: it.at } }
        return (prBuilds + branchBuilds).distinctBy { it.id }.sortedByDescending { it.queuedAt ?: it.at }
    }

    /** Cancelled builds are not counted. */
    fun counts(builds: List<Build>) = Counts(
        failed = builds.count { Signals.signalOf(it.state) == BuildSignal.FAILED },
        running = builds.count { Signals.signalOf(it.state) == BuildSignal.RUNNING },
        ok = builds.count { it.state == BuildState.SUCCEEDED },
    )
}
