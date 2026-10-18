package io.github.iacoposb.adodash.api

/**
 * A pull request as its page shows it: [description] is markdown, [workItemIds] the work items
 * linked to it; [status] "active", "completed" or "abandoned".
 */
data class PullRequestDetail(
    val pr: PullRequest,
    val description: String = "",
    val labels: List<String> = emptyList(),
    val workItemIds: List<Int> = emptyList(),
    val status: String = "active",
)

/** Thread statuses as Azure DevOps names them. */
object ThreadStatus {
    const val ACTIVE = "active"
    const val FIXED = "fixed"
    const val WONT_FIX = "wontFix"
    const val CLOSED = "closed"
    const val BY_DESIGN = "byDesign"
    const val PENDING = "pending"

    /** In the order the status menu offers them. */
    val all = listOf(ACTIVE, PENDING, FIXED, WONT_FIX, CLOSED, BY_DESIGN)

    /** Still to be acted on: what the "Active" filter shows. */
    fun isOpen(status: String?) = status == ACTIVE || status == PENDING
}
