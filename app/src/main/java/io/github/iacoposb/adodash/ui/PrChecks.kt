package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.Vote
import java.time.Instant

/** How a check stands, for its icon. */
enum class CheckState { OK, FAILED, RUNNING, WAITING, WARNING }

/** What a row of "Checks and policies" is about, with what its text needs. */
sealed interface CheckKind {
    /** [name] the pipeline's, null when the policy does not say. */
    data class Build(val name: String?, val buildId: Int?, val status: String, val expired: Boolean) : CheckKind
    data class Reviewers(val approvals: Int, val required: Int) : CheckKind
    /** A "Required reviewers" policy: [approvals] of the [required] approvals it wants. */
    data class RequiredReviewers(val approvals: Int, val required: Int) : CheckKind
    /** [active] null when the threads are not read yet. */
    data class Comments(val active: Int?) : CheckKind
    data class WorkItems(val ids: List<Int>) : CheckKind
    data class Other(val name: String) : CheckKind
    /** "succeeded", "conflicts", "queued", "failure"… */
    data class Merge(val status: String, val target: String) : CheckKind
}

data class CheckRow(val kind: CheckKind, val state: CheckState, val blocking: Boolean = true, val at: Instant? = null)

/** The rows of "Checks and policies" from the policies' evaluations. Pure. */
object PrChecks {
    /**
     * One row per policy that applies (builds first, then reviewers, comments, work items and
     * the rest), and the merge's state last.
     */
    fun of(pr: PullRequest, evaluations: List<PolicyEvaluation>, activeThreads: Int?, workItemIds: List<Int>): List<CheckRow> {
        val rows = evaluations.filter { it.enabled && it.status != "notApplicable" }.map { e ->
            val kind: CheckKind = when (e.typeId.lowercase()) {
                PolicyEvaluation.BUILD_POLICY -> CheckKind.Build(e.displayName, e.buildId, e.status, e.expired)
                PolicyEvaluation.MIN_REVIEWERS_POLICY -> CheckKind.Reviewers(
                    approvals = pr.reviewers.count { !it.group && approves(it) && counts(pr, it, e) },
                    required = e.minApprovers ?: 1,
                )
                PolicyEvaluation.REQUIRED_REVIEWERS_POLICY -> requiredReviewers(pr, e)
                PolicyEvaluation.COMMENTS_POLICY -> CheckKind.Comments(activeThreads)
                PolicyEvaluation.WORK_ITEMS_POLICY -> CheckKind.WorkItems(workItemIds)
                else -> CheckKind.Other(e.displayName ?: e.typeName)
            }
            CheckRow(kind, stateOf(kind, e), e.blocking, e.completedAt)
        }
        val order = listOf(
            CheckKind.Build::class, CheckKind.Reviewers::class, CheckKind.RequiredReviewers::class,
            CheckKind.Comments::class, CheckKind.WorkItems::class,
        )
        val sorted = rows.sortedBy { r -> order.indexOf(r.kind::class).let { if (it < 0) order.size else it } }
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

    private fun approves(r: Reviewer) = r.vote >= Vote.APPROVED_WITH_SUGGESTIONS

    /** The author's vote only where the policy lets it count. */
    private fun counts(pr: PullRequest, r: Reviewer, e: PolicyEvaluation) =
        e.creatorVoteCounts || !r.id.equals(pr.author.id, ignoreCase = true)

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
