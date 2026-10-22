package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.CompletionOptions
import io.github.iacoposb.adodash.api.MergeStrategy
import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.PullRequestDetail
import io.github.iacoposb.adodash.api.Vote

/** What closing a pull request from its page does. */
enum class CloseAction { COMPLETE, AUTO_COMPLETE, CANCEL_AUTO_COMPLETE, ABANDON, REACTIVATE }

enum class CloseProblem {
    /** 401 or 403: no permission (to complete, to bypass the policies…), or a PAT without Code (write). */
    DENIED,
    /** 409 or 412: the pull request changed meanwhile (a push), and was read again. */
    STALE,
    /** Completed, but the merge failed (conflicts, a policy): the message says why. */
    MERGE_FAILED,
    /** Completed, and still merging after a while: the page shows how it went once read again. */
    SLOW,
    OTHER,
}

/** Why [action] did not go through; [bypass] when it was to bypass the policies. */
data class CloseError(val action: CloseAction, val problem: CloseProblem, val message: String = "", val bypass: Boolean = false)

/**
 * The complete dialog's choices, as in Azure DevOps' own: the merge type among those [allowed],
 * the merge commit's [message], and the policies bypassed with a reason.
 */
data class CompleteDraft(
    val strategy: MergeStrategy,
    val allowed: List<MergeStrategy> = MergeStrategy.entries,
    val message: String = "",
    val deleteSourceBranch: Boolean = true,
    val transitionWorkItems: Boolean = true,
    val bypass: Boolean = false,
    val bypassReason: String = "",
) {
    /** Auto-complete never bypasses: it waits for the policies instead. */
    fun options(bypassing: Boolean = bypass) = CompletionOptions(
        mergeStrategy = strategy,
        mergeCommitMessage = message.trim().takeIf { strategy.hasMessage && it.isNotEmpty() },
        deleteSourceBranch = deleteSourceBranch,
        transitionWorkItems = transitionWorkItems,
        bypassPolicy = bypassing,
        bypassReason = bypassReason.trim().takeIf { bypassing },
    )
}

/**
 * Completing, auto-completing or abandoning the pull request open: [dialog] while the complete
 * dialog is open, [busy] while an action is sent, [merging] while Azure DevOps merges it.
 */
data class CloseState(
    val dialog: CompleteDraft? = null,
    val busy: CloseAction? = null,
    val merging: Boolean = false,
    val error: CloseError? = null,
) {
    val completing get() = busy == CloseAction.COMPLETE || merging
}

/** What the complete dialog offers and allows. Pure. */
object PrCompletion {
    /**
     * The merge types the target branch's "Require a merge strategy" policies allow, in Azure
     * DevOps' order; all of them without such a policy (or with one that allows none).
     */
    fun allowed(evaluations: List<PolicyEvaluation>?): List<MergeStrategy> {
        val sets = evaluations.orEmpty().filter { it.enabled && it.status != "notApplicable" }.mapNotNull { it.mergeStrategies }
        if (sets.isEmpty()) return MergeStrategy.entries
        val both = sets.reduce { a, b -> a intersect b }
        return MergeStrategy.entries.filter { it in both }.ifEmpty { MergeStrategy.entries }
    }

    /** "Merged PR 311: Retry handling", as Azure DevOps writes it. */
    fun message(pr: PullRequest) = "Merged PR ${pr.id}: ${pr.title}"

    /**
     * The dialog as it opens: the options auto-complete was set with, if it was; otherwise the
     * first merge type allowed, the source branch deleted and the work items completed.
     */
    fun draft(pr: PullRequest, detail: PullRequestDetail?, evaluations: List<PolicyEvaluation>?): CompleteDraft {
        val allowed = allowed(evaluations)
        val set = detail?.completionOptions?.takeIf { detail.autoCompleteSetBy != null }
        return CompleteDraft(
            strategy = set?.mergeStrategy?.takeIf { it in allowed } ?: allowed.first(),
            allowed = allowed,
            message = set?.mergeCommitMessage?.takeIf { it.isNotBlank() } ?: message(pr),
            deleteSourceBranch = set?.deleteSourceBranch ?: true,
            transitionWorkItems = set?.transitionWorkItems ?: true,
        )
    }

    /**
     * Something keeps it from completing: a blocking policy not passed (or outdated), conflicts,
     * a required reviewer who has not approved, or someone who rejected it. Policies not read yet
     * block nothing: Azure DevOps refuses if they do.
     */
    fun blocked(pr: PullRequest, evaluations: List<PolicyEvaluation>?): Boolean {
        val policies = evaluations.orEmpty().filter { it.enabled && it.blocking }.any { !passes(it) }
        val reviewers = pr.reviewers.any { (it.required && it.vote < Vote.APPROVED_WITH_SUGGESTIONS) || it.vote == Vote.REJECTED }
        return policies || reviewers || pr.hasConflicts
    }

    private fun passes(e: PolicyEvaluation) = (e.status == "approved" && !e.expired) || e.status == "notApplicable"

    /** The rows of "Checks and policies" that keep it from completing. */
    fun blockers(rows: List<CheckRow>): List<CheckRow> = rows.filter { it.blocking && it.state != CheckState.OK }

    /**
     * Complete can be pressed: an active pull request, not a draft, whose last commit is known;
     * when [blocked], only bypassing the policies with a reason.
     */
    fun canComplete(pr: PullRequest, status: String, blocked: Boolean, draft: CompleteDraft): Boolean =
        status == PullRequestDetail.ACTIVE && !pr.draft && pr.lastCommit != null &&
            (if (draft.bypass) draft.bypassReason.isNotBlank() else !blocked)

    /** Auto-complete waits for the policies: for a pull request they block, never bypassing them. */
    fun canAutoComplete(pr: PullRequest, status: String, draft: CompleteDraft): Boolean =
        status == PullRequestDetail.ACTIVE && !pr.draft && !draft.bypass

    /** The merge failed: the pull request stays active with this merge status. */
    fun mergeFailed(pr: PullRequest) = pr.mergeStatus in FAILED_MERGE

    private val FAILED_MERGE = setOf("conflicts", "failure", "rejectedByPolicy")
}
