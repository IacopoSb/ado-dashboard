package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PrStatus
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.Vote
import java.time.Instant

/** How a check stands, for its icon. */
enum class CheckState { OK, FAILED, RUNNING, WAITING, WARNING }

/** What a row of "Checks and policies" is about, with what its text needs. */
sealed interface CheckKind {
    /** [name] the pipeline's, null when the policy does not say; [evaluationId] to queue it again. */
    data class Build(val name: String?, val buildId: Int?, val status: String, val expired: Boolean, val evaluationId: String = "") : CheckKind
    data class Reviewers(val approvals: Int, val required: Int) : CheckKind
    /** A "Required reviewers" policy: [approvals] of the [required] approvals it wants. */
    data class RequiredReviewers(val approvals: Int, val required: Int) : CheckKind
    /** [active] null when the threads are not read yet. */
    data class Comments(val active: Int?) : CheckKind
    data class WorkItems(val ids: List<Int>) : CheckKind
    /**
     * A status posted by a service ("SonarQube/quality-gate"), or a "Status check" policy waiting
     * for one: [description] and [url] are the status's, null when it has not been posted.
     */
    data class Status(val name: String, val description: String? = null, val url: String? = null) : CheckKind
    data class Other(val name: String) : CheckKind
    /** "succeeded", "conflicts", "queued", "failure"… */
    data class Merge(val status: String, val target: String) : CheckKind
}

data class CheckRow(val kind: CheckKind, val state: CheckState, val blocking: Boolean = true, val at: Instant? = null)

/** The rows of "Checks and policies" from the policies' evaluations. Pure. */
object PrChecks {
    /**
     * One row per policy that applies (builds first, then reviewers, comments, work items, status
     * checks, only when failed, and the rest), one per failed [statuses] no policy waits for
     * (optional: they do not block), and the merge's state last.
     */
    fun of(
        pr: PullRequest,
        evaluations: List<PolicyEvaluation>,
        activeThreads: Int?,
        workItemIds: List<Int>,
        statuses: List<PrStatus> = emptyList(),
    ): List<CheckRow> {
        val byKey = statuses.associateBy { it.key }
        val applying = evaluations.filter { it.enabled && it.status != "notApplicable" }
        val rows = applying.map { e ->
            val status = e.statusKey?.let(byKey::get)
            val kind: CheckKind = when (e.typeId.lowercase()) {
                PolicyEvaluation.BUILD_POLICY -> CheckKind.Build(e.displayName, e.buildId, e.status, e.expired, e.evaluationId)
                PolicyEvaluation.MIN_REVIEWERS_POLICY -> CheckKind.Reviewers(
                    approvals = pr.reviewers.count { !it.group && approves(it) && counts(pr, it, e) },
                    required = e.minApprovers ?: 1,
                )
                PolicyEvaluation.REQUIRED_REVIEWERS_POLICY -> requiredReviewers(pr, e)
                PolicyEvaluation.COMMENTS_POLICY -> CheckKind.Comments(activeThreads)
                PolicyEvaluation.WORK_ITEMS_POLICY -> CheckKind.WorkItems(workItemIds)
                PolicyEvaluation.STATUS_POLICY -> CheckKind.Status(e.name, status?.description, status?.url)
                else -> CheckKind.Other(e.name)
            }
            CheckRow(kind, stateOf(kind, e), e.blocking, status?.at ?: e.completedAt)
        }
        // A status a policy waits for is that policy's row
        val backed = applying.mapNotNull { it.statusKey }.toSet()
        val unbacked = statuses.filter { it.key !in backed }.map { s ->
            CheckRow(CheckKind.Status(s.label, s.description, s.url), stateOf(s), blocking = false, at = s.at)
        }
        val order = listOf(
            CheckKind.Build::class, CheckKind.Reviewers::class, CheckKind.RequiredReviewers::class,
            CheckKind.Comments::class, CheckKind.WorkItems::class, CheckKind.Status::class,
        )
        // Status checks only when they failed: the passing ones are noise (long service names)
        val shown = (rows + unbacked).filter { it.kind !is CheckKind.Status || it.state == CheckState.FAILED }
        val sorted = shown.sortedBy { r -> order.indexOf(r.kind::class).let { if (it < 0) order.size else it } }
        val merge = when (pr.mergeStatus) {
            "succeeded" -> CheckState.OK
            "conflicts", "failure", "rejectedByPolicy" -> CheckState.FAILED
            "queued" -> CheckState.RUNNING
            else -> null
        }
        return sorted + listOfNotNull(merge?.let { CheckRow(CheckKind.Merge(pr.mergeStatus.orEmpty(), pr.targetBranch), it) })
    }

    /**
     * "Required reviewers": with a minimum count, the approvals of the people named or voting for
     * a group named, up to that count; without one, every reviewer named must approve (a group
     * by the approval of one of its members).
     */
    fun requiredReviewers(pr: PullRequest, e: PolicyEvaluation): CheckKind.RequiredReviewers {
        val ids = e.requiredReviewerIds.map { it.lowercase() }.toSet()
        val people = pr.reviewers.filter { !it.group && approves(it) && counts(pr, it, e) }
        fun approvalFor(id: String) = people.any { it.id.lowercase() == id || it.votedFor.any { g -> g.lowercase() == id } }
        val minimum = e.minApprovers?.takeIf { it > 0 }
        return if (minimum != null) {
            val approvals = people.count { p -> p.id.lowercase() in ids || p.votedFor.any { it.lowercase() in ids } }
            CheckKind.RequiredReviewers(approvals.coerceAtMost(minimum), minimum)
        } else {
            val required = ids.ifEmpty { pr.reviewers.filter { it.required }.map { it.id.lowercase() }.toSet() }
            CheckKind.RequiredReviewers(required.count(::approvalFor), required.size)
        }
    }

    /**
     * The evaluation [row] can be queued again with, as Azure DevOps offers it: a build check
     * expired, or whose build failed; null for the others.
     */
    fun requeueId(row: CheckRow): String? {
        val kind = row.kind as? CheckKind.Build ?: return null
        val offered = row.state == CheckState.FAILED || (kind.expired && row.state == CheckState.WAITING)
        return kind.evaluationId.takeIf { offered && it.isNotEmpty() }
    }

    /** The build checks "Re-queue all expired" queues again. */
    fun expiredBuilds(evaluations: List<PolicyEvaluation>?): List<String> =
        evaluations.orEmpty().filter { it.isBuild && it.enabled && it.expired && it.status != "running" && it.status != "notApplicable" }
            .map { it.evaluationId }.filter { it.isNotEmpty() }.distinct()

    private fun approves(r: Reviewer) = r.vote >= Vote.APPROVED_WITH_SUGGESTIONS

    /** The author's vote only where the policy lets it count. */
    private fun counts(pr: PullRequest, r: Reviewer, e: PolicyEvaluation) =
        e.creatorVoteCounts || !r.id.equals(pr.author.id, ignoreCase = true)

    /** "error" is the service failing to check: a failure all the same. */
    fun stateOf(s: PrStatus): CheckState = when (s.state) {
        "succeeded" -> CheckState.OK
        "failed", "error" -> CheckState.FAILED
        "pending" -> CheckState.RUNNING
        else -> CheckState.WAITING
    }

    private fun stateOf(kind: CheckKind, e: PolicyEvaluation): CheckState = when (kind) {
        is CheckKind.Build -> when (e.status) {
            "approved" -> if (e.expired) CheckState.WAITING else CheckState.OK
            "rejected", "broken" -> CheckState.FAILED
            "running" -> CheckState.RUNNING
            "queued" -> if (e.buildId != null && !e.expired) CheckState.RUNNING else CheckState.WAITING
            else -> CheckState.WAITING
        }
        is CheckKind.Comments, is CheckKind.WorkItems -> if (e.status == "approved") CheckState.OK else CheckState.WARNING
        is CheckKind.Reviewers, is CheckKind.RequiredReviewers -> if (e.status == "approved") CheckState.OK else CheckState.WAITING
        else -> when (e.status) {
            "approved" -> CheckState.OK
            "rejected", "broken" -> CheckState.FAILED
            "running" -> CheckState.RUNNING
            else -> CheckState.WAITING
        }
    }
}
