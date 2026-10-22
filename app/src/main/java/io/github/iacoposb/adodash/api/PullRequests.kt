package io.github.iacoposb.adodash.api

import java.time.Instant

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
    /** Who set auto-complete: it completes by itself once the policies pass; null when not set. */
    val autoCompleteSetBy: Identity? = null,
    /** The options it completes (or was set to auto-complete) with; null when none were given. */
    val completionOptions: CompletionOptions? = null,
    /** Why the last merge failed, as Azure DevOps says it; null when it did not. */
    val mergeFailure: String? = null,
    /** Who completed or abandoned it. */
    val closedBy: Identity? = null,
) {
    companion object {
        const val ACTIVE = "active"
        const val COMPLETED = "completed"
        const val ABANDONED = "abandoned"
    }
}

/** How a pull request is merged into its target, as Azure DevOps names it ([api]). */
enum class MergeStrategy(val api: String) {
    NO_FAST_FORWARD("noFastForward"),
    SQUASH("squash"),
    REBASE("rebase"),
    REBASE_MERGE("rebaseMerge");

    /** Rebase and fast-forward makes no merge commit: no message to write. */
    val hasMessage get() = this != REBASE

    companion object {
        fun of(api: String?): MergeStrategy? = entries.firstOrNull { it.api.equals(api, ignoreCase = true) }
    }
}

/**
 * How a pull request is completed: [bypassReason] goes with [bypassPolicy], which completes it
 * whatever its policies say (a permission of its own).
 */
data class CompletionOptions(
    val mergeStrategy: MergeStrategy = MergeStrategy.NO_FAST_FORWARD,
    val mergeCommitMessage: String? = null,
    val deleteSourceBranch: Boolean = false,
    val transitionWorkItems: Boolean = false,
    val bypassPolicy: Boolean = false,
    val bypassReason: String? = null,
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

/**
 * A push to a pull request: [sourceCommit] its source branch's commit, [baseCommit] where it
 * branched from the target (what the diff is against, as Azure DevOps shows it).
 */
data class PrIteration(val id: Int, val sourceCommit: String, val baseCommit: String)

enum class ChangeType { ADD, EDIT, DELETE, RENAME }

/**
 * A file the pull request changes: [path] as Azure DevOps writes it ("/src/Gateway.kt"),
 * [originalPath] where a renamed file was; [changeTrackingId] follows it across pushes.
 */
data class PrFileChange(
    val path: String,
    val type: ChangeType,
    val originalPath: String? = null,
    val changeTrackingId: Int = 0,
) {
    /** Where the file is in the base: none for an added one. */
    val basePath: String? get() = if (type == ChangeType.ADD) null else originalPath ?: path

    /** Where it is in the pull request: none for a deleted one. */
    val targetPath: String? get() = if (type == ChangeType.DELETE) null else path

    val folder: String get() = path.substringBeforeLast('/', "").ifEmpty { "/" }
    val name: String get() = path.substringAfterLast('/')

    companion object {
        /** "edit", "add", "delete", "rename", "edit, rename"… */
        fun typeOf(text: String): ChangeType {
            val parts = text.split(',').map { it.trim().lowercase() }
            return when {
                "delete" in parts -> ChangeType.DELETE
                "add" in parts || "undelete" in parts || "branch" in parts -> ChangeType.ADD
                "rename" in parts -> ChangeType.RENAME
                else -> ChangeType.EDIT
            }
        }
    }
}

/** A file's content at a commit, as far as a diff can use it. */
sealed interface FileContent {
    data class Text(val text: String) : FileContent
    data object Binary : FileContent
    /** Over [MAX_BYTES]: not read. */
    data object TooLarge : FileContent
    /** Not at that commit. */
    data object Missing : FileContent

    companion object {
        const val MAX_BYTES = 1_000_000L
        const val MAX_LINES = 20_000
    }
}

/** A commit of a pull request: [message] its first line. */
data class PrCommit(val id: String, val message: String, val author: String, val date: Instant, val url: String) {
    val shortId get() = id.take(8)
}

/** Where a new thread goes in a file: [line] on the right side, or on the left one for a deleted line. */
data class FileAnchor(val path: String, val line: Int, val left: Boolean = false)
